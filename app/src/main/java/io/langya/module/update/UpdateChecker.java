package io.langya.module.update;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.MainThread;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.langya.module.BuildConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import timber.log.Timber;

/**
 * 调 GitHub releases/latest 比版本号
 *
 * tag_name 形如 v1.6, 跟 BuildConfig.VERSION_NAME 按 . 分段比较
 * 自动触发的检查 12 小时内不重复, 用户手动检查不受限
 */
public final class UpdateChecker {

    public static final String REPO = "LangYa466/PhoneApp";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String PREF_LAST_CHECK = "update_last_check_ms";
    private static final long AUTO_INTERVAL_MS = TimeUnit.HOURS.toMillis(12);

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    public interface Callback {
        @MainThread void onResult(Result result);
    }

    public static final class Result {
        public final boolean hasUpdate;
        public final String latestTag;
        public final String latestVersion;
        public final String htmlUrl;
        public final String body;

        Result(boolean hasUpdate, String latestTag, String latestVersion,
               String htmlUrl, String body) {
            this.hasUpdate = hasUpdate;
            this.latestTag = latestTag;
            this.latestVersion = latestVersion;
            this.htmlUrl = htmlUrl;
            this.body = body;
        }
    }

    private UpdateChecker() {}

    public static void checkAuto(Context ctx, Callback cb) {
        var sp = PreferenceManager.getDefaultSharedPreferences(ctx);
        long last = sp.getLong(PREF_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) {
            Timber.d("update auto check skipped (last=%d)", last);
            return;
        }
        checkNow(ctx, cb);
    }

    public static void checkNow(Context ctx, Callback cb) {
        var app = ctx.getApplicationContext();
        new Thread(() -> {
            Result r = fetch();
            if (r != null) {
                PreferenceManager.getDefaultSharedPreferences(app)
                        .edit()
                        .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                        .apply();
            }
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> cb.onResult(r));
        }, "update-check").start();
    }

    private static Result fetch() {
        var req = new Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PhoneApp/" + BuildConfig.VERSION_NAME)
                .build();
        try (var resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                Timber.w("GitHub releases HTTP %d", resp.code());
                return null;
            }
            var json = new JSONObject(resp.body().string());
            var tag = json.optString("tag_name", "");
            var url = json.optString("html_url", "");
            var body = json.optString("body", "");
            if (tag.isEmpty()) return null;
            var version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            boolean newer = compareVersion(version, BuildConfig.VERSION_NAME) > 0;
            return new Result(newer, tag, version, url, body);
        } catch (IOException | JSONException | RuntimeException e) {
            Timber.w(e, "update check failed");
            return null;
        }
    }

    /** 按 . 分段数字比较 非数字段按字符串比 缺位补 0 */
    static int compareVersion(String a, String b) {
        if (a.equals(b)) return 0;
        var pa = a.split("\\.");
        var pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            var sa = i < pa.length ? pa[i] : "0";
            var sb = i < pb.length ? pb[i] : "0";
            try {
                int ia = Integer.parseInt(sa);
                int ib = Integer.parseInt(sb);
                if (ia != ib) return Integer.compare(ia, ib);
            } catch (NumberFormatException e) {
                int cmp = sa.compareTo(sb);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }
}
