package io.langya.module.data;

import static io.langya.module.data.WebQuery.BUILTIN_DB;
import static io.langya.module.data.WebQuery.SPECIAL_NUMBERS;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import java.util.HashMap;

public final class WebQueryHelper {

    private static final String TAG = "CallerID_Web";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String HEADER_MARKER = "搜号码、企业客服、维修电话、公共热线";
    private static final String POLL_JS = """
            (function(){var b=document.body;if(!b)return 'NOT_READY';\
            var t=b.innerText||'';\
            if(t.indexOf('更多服务')>=0||t.indexOf('查询号码')>=0)return t;\
            return 'NOT_READY';})();""";
    private static final String EXTRACT_JS =
            "(function(){return document.body?(document.body.innerText||''):'NO_BODY';})();";

    private static final int POLL_INTERVAL_MS = 500;
    private static final int POLL_MAX_MS = 12_000;
    private static final int TIMEOUT_MS = 15_000;

    public interface Callback {
        void onResult(String result);
    }
    public void query(Context ctx, String number, Callback cb) {
        Log.d(TAG, "query() called, number=" + number);
        var main = new Handler(Looper.getMainLooper());

        for (var entry : SPECIAL_NUMBERS) {
            if (entry[0].equals(number)) {
                Log.d(TAG, "SPECIAL hit: " + entry[1]);
                main.post(() -> cb.onResult(entry[1]));
                return;
            }
        }

        var builtin = BUILTIN_DB.get(number);
        if (builtin != null) {
            Log.d(TAG, "BUILTIN hit: " + number + " -> " + builtin);
            main.post(() -> cb.onResult(builtin));
            return;
        }

        CacheStore.init(ctx);
        var cached = CacheStore.get(number);
        // 空字符串 = 之前查询过但未识别，仍算缓存命中，避免重复发起网络请求
        if (cached != null) {
            Log.d(TAG, "CACHE hit: " + number + " -> " + (cached.isEmpty() ? "(empty)" : cached));
            final String result = cached.isEmpty() ? null : cached;
            main.post(() -> cb.onResult(result));
            return;
        }

        main.post(() -> startWebViewQuery(ctx, number, cb, main));
    }

    private void startWebViewQuery(Context ctx, String number, Callback cb, Handler main) {
        var wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            cb.onResult(null);
            return;
        }

        WebView wv;
        try {
            wv = new WebView(ctx);
        } catch (Throwable e) {
            Log.e(TAG, "create WebView failed", e);
            cb.onResult(null);
            return;
        }

        final boolean[] done = {false};
        final boolean[] attached = {false};
        final int[] pollMs = {0};

        var lp = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        try {
            wv.setAlpha(0.01f);
            wm.addView(wv, lp);
            attached[0] = true;
        } catch (Throwable e) {
            Log.e(TAG, "add WebView failed", e);
            try {
                wv.destroy();
            } catch (Exception ignored) {
            }
            cb.onResult(null);
            return;
        }

        final WebView wvRef = wv;
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (done[0]) return;
            pollMs[0] += POLL_INTERVAL_MS;
            if (pollMs[0] > POLL_MAX_MS) {
                Log.e(TAG, "POLL_MAX reached, force extract");
                extractAndFinish(wvRef, number, done, wm, attached, cb);
                return;
            }
            wvRef.evaluateJavascript(POLL_JS, val -> {
                if (done[0]) return;
                if (val != null && !val.contains("NOT_READY")) {
                    Log.d(TAG, "POLL ready at " + pollMs[0] + "ms");
                    extractAndFinish(wvRef, number, done, wm, attached, cb);
                } else {
                    main.postDelayed(poll[0], POLL_INTERVAL_MS);
                }
            });
        };

        try {
            var s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setBlockNetworkImage(true);
            s.setLoadsImagesAutomatically(false);
            s.setUserAgentString(UA);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            WebView.setWebContentsDebuggingEnabled(true);

            wv.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage m) {
                    Log.d(TAG, "console: " + m.message());
                    return true;
                }
            });
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    Log.d(TAG, "onPageFinished: " + url);
                    if (url.contains("mhaoma.baidu.com")) {
                        main.postDelayed(poll[0], POLL_INTERVAL_MS);
                    }
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                    if (req != null && req.isForMainFrame()) {
                        Log.e(TAG, "error: " + err.getErrorCode());
                    }
                }
            });

            var url = "https://mhaoma.baidu.com/pages/search-result/search-result?search="
                    + number + "&srcid=swan-search";
            Log.d(TAG, "loadUrl=" + url);
            wv.loadUrl(url);

            main.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                Log.e(TAG, "TIMEOUT");
                cb.onResult(null);
                cleanup(wm, wvRef, attached);
            }, TIMEOUT_MS);
        } catch (Throwable e) {
            Log.e(TAG, "crashed", e);
            if (!done[0]) {
                done[0] = true;
                cb.onResult(null);
            }
            cleanup(wm, wvRef, attached);
        }
    }

    private void extractAndFinish(WebView wv, String number, boolean[] done,
                                  WindowManager wm, boolean[] attached, Callback cb) {
        wv.evaluateJavascript(EXTRACT_JS, val -> {
            if (done[0]) return;
            done[0] = true;
            Log.d(TAG, "CARD_RAW=" + val);
            String result;
            try {
                result = parseCard(val, number);
            } catch (Throwable e) {
                Log.e(TAG, "parseCard crashed, number=" + number, e);
                result = null;
            }
            Log.d(TAG, "PARSED=" + result);
            CacheStore.put(number, result != null ? result : "");
            cb.onResult(result);
            cleanup(wm, wv, attached);
        });
    }

    private static String safeSub(String s, int begin, int end) {
        if (s == null) return "";
        int len = s.length();
        if (begin < 0) begin = 0;
        if (end > len) end = len;
        if (begin > end) begin = end;
        return s.substring(begin, end);
    }

    private static String safeSub(String s, int begin) {
        if (s == null) return "";
        int len = s.length();
        if (begin < 0) begin = 0;
        if (begin > len) begin = len;
        return s.substring(begin);
    }

    private void cleanup(WindowManager wm, WebView wv, boolean[] attached) {
        try {
            wv.stopLoading();
            wv.loadUrl("about:blank");
        } catch (Exception ignored) {
        }
        try {
            if (attached[0]) {
                wm.removeViewImmediate(wv);
                attached[0] = false;
            }
        } catch (Exception ignored) {
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                wv.clearHistory();
                wv.removeAllViews();
                wv.destroy();
            } catch (Exception ignored) {
            }
            Log.d(TAG, "cleanup done");
        }, 200L);
    }

    private String parseCard(String jsVal, String number) {
        if (jsVal == null || jsVal.isEmpty()) return null;
        if (number == null || number.isEmpty()) return null;
        var t = jsVal.replace("\\n", "\n").replace("\\t", " ")
                .replace("\\\"", "").replace("\"", "").trim();
        if (t.contains("NO_BODY") || t.length() < 5) return null;

        int headerEnd = t.indexOf(HEADER_MARKER);
        if (headerEnd >= 0) t = safeSub(t, headerEnd + HEADER_MARKER.length());
        int stopPos = t.indexOf("更多服务");
        if (stopPos > 0) t = safeSub(t, 0, stopPos);
        if (t.isEmpty()) return null;

        boolean isEnterprise = t.contains("网络收录号码") || t.contains("官方号码")
                || t.contains("网络收录数据") || t.contains("百度认证号码");
        var card2 = getCard(number, t, isEnterprise);
        Log.d(TAG, "CARD=" + card2);

        if (isEnterprise) {
            var enterpriseName = extractEnterpriseName(t);
            if (enterpriseName != null) {
                var sub = extractSubLine(t, number);
                var result = sub != null ? enterpriseName + " / " + sub : enterpriseName;
                Log.d(TAG, "ENTERPRISE_NAME=" + result);
                return result;
            }
        }

        var foundTag = matchTag(card2, isEnterprise);
        var province = isEnterprise ? null : findFirst(card2, PROVINCES);
        var city = isEnterprise ? null : findFirst(card2, CITIES);
        var carrier = matchCarrier(card2);

        if (foundTag != null) {
            var sb = new StringBuilder(foundTag);
            if (province != null) sb.append(' ').append(province);
            if (city != null && !city.equals(province)) sb.append(' ').append(city);
            if (carrier != null) sb.append(' ').append(carrier);
            return sb.toString().trim();
        }
        if (province != null || city != null || carrier != null) {
            var sb = new StringBuilder();
            if (province != null) sb.append(province);
            if (city != null && !city.equals(province)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(city);
            }
            if (carrier != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(carrier);
            }
            return sb.toString().trim();
        }
        if (number.startsWith("400") || number.startsWith("800")) return "企业服务号";
        return "";
    }

    @NonNull
    private static String getCard(String number, String t, boolean isEnterprise) {
        if (t == null || t.isEmpty() || number == null || number.isEmpty()) return "";
        int numPos = t.indexOf(number);
        String card;
        if (isEnterprise) {
            var before = numPos >= 0 ? safeSub(t, 0, numPos) : t;
            var after = numPos >= 0 ? safeSub(t, numPos + number.length()) : "";
            if (after.length() > 50) after = safeSub(after, 0, 50);
            card = (before.trim() + " " + after.trim()).trim();
        } else {
            card = numPos >= 0 ? safeSub(t, numPos + number.length()) : t;
        }
        return card.replaceAll("\\s+", " ").trim();
    }

    private String extractEnterpriseName(String t) {
        var lines = t.split("\n");
        int markerIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            var ln = lines[i].trim();
            if ("官方号码".equals(ln) || "网络收录号码".equals(ln)
                    || "网络收录数据".equals(ln) || "百度认证号码".equals(ln)) {
                markerIdx = i;
                break;
            }
        }
        for (int j = markerIdx - 1; j >= 0; j--) {
            var card = lines[j].trim();
            if (card.length() > 1 && !card.startsWith("http")) return card;
        }
        return null;
    }

    private String extractSubLine(String t, String number) {
        var lines = t.split("\n");
        boolean inNumInfo = false;
        for (int i = 0; i < lines.length; i++) {
            var ln = lines[i].trim();
            if ("号码信息".equals(ln)) {
                inNumInfo = true;
                continue;
            }
            if (!inNumInfo || !ln.equals(number) || i + 1 >= lines.length) continue;
            var cand = lines[i + 1].trim();
            if (cand.matches("\\d+")) continue;
            if (cand.length() <= 1) continue;
            if ("电话".equals(cand) || "服务".equals(cand)
                    || "热线".equals(cand) || "客服".equals(cand)) continue;
            return cand;
        }
        return null;
    }

    private String matchTag(String card2, boolean isEnterprise) {
        for (var tag : TAG_WORDS) {
            if (card2.contains(tag)) return normalizeTag(tag);
        }
        if (!isEnterprise) return null;
        if (card2.contains("客服")) return "客服热线";
        for (var kw : OFFICIAL_KEYWORDS) {
            if (card2.contains(kw)) return "官方热线";
        }
        return null;
    }

    private String matchCarrier(String card2) {
        if (card2.contains("中国移动")) return "中国移动";
        if (card2.contains("中国联通")) return "中国联通";
        if (card2.contains("中国电信")) return "中国电信";
        return null;
    }

    private String findFirst(String text, String[] words) {
        for (var w : words) if (text.contains(w)) return w;
        return null;
    }

    private static String normalizeTag(String tag) {
        return switch (tag) {
            case "快递外卖", "外卖送餐" -> "快递物流";
            case "诈骗电话" -> "疑似诈骗";
            case "广告推销", "广告营销" -> "广告营销";
            case "贷款推销", "金融推销" -> "贷款推销";
            default -> tag;
        };
    }

    private static final String[] TAG_WORDS = {
            "广告营销", "商业营销", "广告推销", "贷款推销", "金融推销",
            "教育推销", "装修推销", "快递物流", "外卖送餐", "快递外卖",
            "骚扰电话", "疑似诈骗", "诈骗电话", "房产中介", "保险推销",
            "催收电话", "政府机构", "公益热线", "政务服务"
    };

    private static final String[] OFFICIAL_KEYWORDS = {
            "官方号码", "政府机构", "司法", "公安", "法院",
            "税务", "社保", "医保", "教育局", "热线"
    };

    private static final String[] PROVINCES = {
            "北京", "天津", "上海", "重庆", "河北", "山西", "辽宁", "吉林",
            "黑龙江", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
            "湖北", "湖南", "广东", "海南", "四川", "贵州", "云南", "陕西",
            "甘肃", "青海", "台湾", "内蒙古", "广西", "西藏", "宁夏", "新疆",
            "香港", "澳门"
    };

    private static final String[] CITIES = {
            "广州", "深圳", "珠海", "佛山", "东莞", "中山", "惠州", "汕头", "汕尾",
            "江门", "肇庆", "清远", "韶关", "梅州", "潮州", "揭阳", "阳江", "茂名",
            "湛江", "北京", "上海", "天津", "重庆", "杭州", "宁波", "温州", "苏州",
            "南京", "无锡", "常州", "镇江", "扬州", "徐州", "武汉", "长沙", "郑州",
            "成都", "西安", "昆明", "贵阳", "南宁", "海口", "三亚", "福州", "厦门",
            "南昌", "济南", "青岛", "沈阳", "大连", "长春", "哈尔滨", "石家庄", "太原",
            "合肥", "兰州", "西宁", "银川", "呼和浩特", "乌鲁木齐", "拉萨", "烟台",
            "唐山", "保定", "洛阳", "南阳"
    };
}