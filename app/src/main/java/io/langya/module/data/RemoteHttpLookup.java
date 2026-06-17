package io.langya.module.data;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阻塞式 HTTP 号码归属地查询。**不要在主线程调用。**
 * 唯一外部数据源：淘宝公开手机号段 JSONP 接口（GBK，无需鉴权）。
 *
 * 与旧版 WebView 方案的根本区别：
 *   - 不挂任何系统窗口；
 *   - 不解析 HTML / DOM，仅 regex 抓 JSONP 字段；
 *   - 完全同步，由调用方决定线程模型。
 */
public final class RemoteHttpLookup {

    private static final String TAG = "CallerID_Http";

    private static final String TAOBAO_URL =
            "https://tcc.taobao.com/cc/json/mobile_tel_segment.htm?tel=";
    private static final Charset GBK = Charset.forName("GBK");

    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 6_000;
    private static final int MAX_BODY_BYTES = 8_192;

    private static final Pattern P_PROVINCE = Pattern.compile("province\\s*:\\s*'([^']*)'");
    private static final Pattern P_CARRIER = Pattern.compile("carrier\\s*:\\s*'([^']*)'");
    private static final Pattern P_CAT_NAME = Pattern.compile("catName\\s*:\\s*'([^']*)'");

    private RemoteHttpLookup() {}

    /**
     * 查 11 位手机号归属地 + 运营商。命中返回 "山东 山东移动"；查不到返回 null。
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
        // carrier 通常已含省名（"山东移动"），单列 catName 仅"中国移动"无地区
        if (carrier == null) carrier = group(P_CAT_NAME, body);
        if (province == null && carrier == null) return null;

        var sb = new StringBuilder();
        if (province != null && !province.isEmpty()) sb.append(province);
        if (carrier != null && !carrier.isEmpty()) {
            if (sb.length() > 0 && !carrier.contains(province == null ? "" : province)) {
                sb.append(' ');
            } else if (sb.length() > 0) {
                // carrier 已含 province（如"山东移动"），直接覆盖避免"山东 山东移动"
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

    private static String fetch(String urlStr) {
        HttpURLConnection conn = null;
        try {
            var url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; CallerID/1.0)");
            conn.setRequestProperty("Accept", "*/*");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "http " + code + " for " + urlStr);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                var buf = new ByteArrayOutputStream(MAX_BODY_BYTES);
                var chunk = new byte[1024];
                int total = 0, n;
                while ((n = in.read(chunk)) > 0) {
                    if (total + n > MAX_BODY_BYTES) {
                        buf.write(chunk, 0, MAX_BODY_BYTES - total);
                        break;
                    }
                    buf.write(chunk, 0, n);
                    total += n;
                }
                return buf.toString(GBK);
            }
        } catch (Throwable e) {
            Log.w(TAG, "fetch failed: " + urlStr, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
