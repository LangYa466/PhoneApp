package io.langya.module.data;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

/**
 * 阻塞式 HTTP 号码归属地查询。**不要在主线程调用。**
 * 数据源：淘宝公开手机号段 JSONP 接口（GBK 编码，无需鉴权）。
 *
 * 用 OkHttp 替代 HttpURLConnection 后：连接池、超时、重试、UA 默认头
 * 全部由客户端实例管理，本类只剩 URL 拼装 + GBK regex。
 */
public final class RemoteHttpLookup {

    private static final String TAOBAO_URL =
            "https://tcc.taobao.com/cc/json/mobile_tel_segment.htm?tel=";
    private static final Charset GBK = Charset.forName("GBK");
    private static final long MAX_BODY_BYTES = 8_192L;

    private static final Pattern P_PROVINCE = Pattern.compile("province\\s*:\\s*'([^']*)'");
    private static final Pattern P_CARRIER = Pattern.compile("carrier\\s*:\\s*'([^']*)'");
    private static final Pattern P_CAT_NAME = Pattern.compile("catName\\s*:\\s*'([^']*)'");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private RemoteHttpLookup() {}

    /**
     * 查 11 位手机号归属地 + 运营商。命中返回 "山东移动"；查不到返回 null。
     * 线程安全；阻塞调用方线程直到收到响应或超时。
     */
    public static String lookupMobile(String number) {
        if (!MobileSegment.isChinaMobile(number)) return null;
        var body = fetch(TAOBAO_URL + number);
        if (body == null || body.isEmpty()) return null;
        return parseTaobao(body);
    }

    private static String parseTaobao(String body) {
        var province = group(P_PROVINCE, body);
        var carrier = group(P_CARRIER, body);
        if (carrier == null) carrier = group(P_CAT_NAME, body);
        if (province == null && carrier == null) return null;

        var sb = new StringBuilder();
        if (province != null && !province.isEmpty()) sb.append(province);
        if (carrier != null && !carrier.isEmpty()) {
            if (sb.length() > 0 && !carrier.contains(province == null ? "" : province)) {
                sb.append(' ');
            } else if (sb.length() > 0) {
                sb.setLength(0);
            }
            sb.append(carrier);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String fetch(String url) {
        var req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; CallerID/1.0)")
                .header("Accept", "*/*")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                Timber.w("http %d for %s", resp.code(), url);
                return null;
            }
            ResponseBody rb = resp.body();
            if (rb == null) return null;
            var bytes = rb.source().peek().readByteArray(MAX_BODY_BYTES);
            return new String(bytes, GBK);
        } catch (Throwable e) {
            Timber.w(e, "fetch failed: %s", url);
            return null;
        }
    }
}
