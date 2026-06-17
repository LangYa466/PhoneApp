package io.langya.module.callerid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.WorkerThread;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * 历史方案的兼容兜底——离屏 WebView 跑百度搜索 把 innerText 里的
 * "号码归属"/"标记为" 等子串 substring 出来
 *
 * 任意环节抛异常都吞掉返回 null 不影响主流水线 (Taobao + 离线号段已经在
 * 上游覆盖大部分场景)
 *
 * 主线程上创建 WebView (Android 要求)IO 线程通过 CountDownLatch 等结果
 * 8 秒超时
 */
public final class BaiduWebLookup {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** "号码归属：北京 移动" / "归属地: 北京移动" / "标记为：快递" */
    private static final Pattern[] PATTERNS = {
            Pattern.compile("号码归属地?[:：]\\s*([\\u4e00-\\u9fa5A-Za-z0-9 ]{2,30})"),
            Pattern.compile("归属地[:：]\\s*([\\u4e00-\\u9fa5A-Za-z0-9 ]{2,30})"),
            Pattern.compile("用户标记[:：]\\s*([\\u4e00-\\u9fa5A-Za-z0-9 ]{2,30})"),
            Pattern.compile("标记为?[:：]\\s*([\\u4e00-\\u9fa5A-Za-z0-9 ]{2,30})"),
            Pattern.compile("([\\u4e00-\\u9fa5]{2,8}(?:快递|外卖|中介|客服|银行|保险|推销|诈骗|骚扰))"),
    };

    private BaiduWebLookup() {}

    @WorkerThread
    public static String lookup(Context ctx, String number) {
        try {
            return lookupInternal(ctx.getApplicationContext(), number);
        } catch (Throwable t) {
            Timber.w(t, "baidu webview lookup failed for %s", number);
            return null;
        }
    }

    private static String lookupInternal(Context appCtx, String number) throws Exception {
        if (number == null || number.length() < 7) return null;
        var latch = new CountDownLatch(1);
        var result = new AtomicReference<String>();
        var main = new Handler(Looper.getMainLooper());
        var url = "https://m.baidu.com/s?wd="
                + URLEncoder.encode(number, StandardCharsets.UTF_8);

        main.post(() -> {
            WebView wv = null;
            try {
                wv = new WebView(appCtx);
                var settings = wv.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setUserAgentString(UA);
                settings.setLoadsImagesAutomatically(false);
                settings.setBlockNetworkImage(true);
                final WebView finalWv = wv;
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String u) {
                        try {
                            v.evaluateJavascript(
                                    "(function(){try{return document.body.innerText}catch(e){return ''}})()",
                                    raw -> {
                                        try {
                                            result.set(extract(number, raw));
                                        } catch (Throwable ignored) {}
                                        latch.countDown();
                                        try { finalWv.stopLoading(); finalWv.destroy(); } catch (Throwable ignored) {}
                                    });
                        } catch (Throwable t) {
                            latch.countDown();
                            try { finalWv.destroy(); } catch (Throwable ignored) {}
                        }
                    }

                    @Override
                    public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                        if (req != null && req.isForMainFrame()) {
                            latch.countDown();
                            try { finalWv.destroy(); } catch (Throwable ignored) {}
                        }
                    }
                });
                wv.loadUrl(url);
            } catch (Throwable t) {
                Timber.w(t, "baidu webview init failed");
                if (wv != null) try { wv.destroy(); } catch (Throwable ignored) {}
                latch.countDown();
            }
        });

        if (!latch.await(8, TimeUnit.SECONDS)) {
            Timber.w("baidu webview lookup timeout: %s", number);
            return null;
        }
        var hit = result.get();
        if (hit != null) Timber.d("baidu webview hit: %s -> %s", number, hit);
        return hit;
    }

    /** evaluateJavascript 返回的是 JSON 字符串 ("text"), 去掉引号 + unescape */
    private static String extract(String number, String rawJsResult) {
        if (rawJsResult == null || rawJsResult.length() < 3) return null;
        var text = unescape(rawJsResult);
        if (text.isEmpty()) return null;
        for (var p : PATTERNS) {
            var m = p.matcher(text);
            if (m.find()) {
                var hit = m.group(1);
                if (hit != null) hit = hit.trim();
                if (hit != null && !hit.isEmpty() && !hit.equals(number)) return hit;
            }
        }
        return null;
    }

    private static String unescape(String raw) {
        var s = raw;
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        var out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append(n);
                            }
                        } else {
                            out.append(n);
                        }
                        break;
                    default: out.append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
