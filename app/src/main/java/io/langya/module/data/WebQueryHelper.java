package io.langya.module.data;

import static io.langya.module.data.WebQuery.BUILTIN_DB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import timber.log.Timber;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 号码识别管线。流水线（顺序短路）：
 *   1) BUILTIN_DB        （内置 3000+ 条，含报警/急救/客服等原 SPECIAL_NUMBERS）
 *   2) CacheStore        （磁盘缓存，包括"已查过但未识别"的负缓存）
 *   3) 离线手机号段       （3 位前缀 → 运营商；4 位前缀 → 虚拟运营商）
 *   4) 在线手机归属地     （淘宝 JSONP，仅 11 位手机号；GBK regex）
 *
 * 与历史版本（WebView + 网页 innerText 抓取）的本质区别：
 *   - 不再起任何 WebView / 系统悬浮窗，不需要 SYSTEM_ALERT_WINDOW；
 *   - 全部 IO 走单线程 ExecutorService（拨号期间不会和 UI 抢主循环）；
 *   - 不再做 DOM/innerText 的 substring 启发式解析（旧版崩溃根源）；
 *   - 公共 API（{@link #query} / {@link Callback}）保持兼容，调用方无需改动。
 *
 * 线程模型：所有 Callback 都在主线程触发。
 */
public final class WebQueryHelper {


    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();
        @Override public Thread newThread(Runnable r) {
            var t = new Thread(r, "CallerID-IO-" + seq.incrementAndGet());
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setDaemon(true);
            return t;
        }
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(String result);
    }

    /**
     * 异步查询。Callback 在主线程触发。多次重入安全，但不会去重——
     * 串行去重在更上层（{@link CallerIdBatchResolver}）做。
     */
    public void query(Context ctx, String number, Callback cb) {
        Timber.d("query: %s", number);
        if (number == null || number.isEmpty()) {
            MAIN.post(() -> cb.onResult(null));
            return;
        }

        // —— 同步快路径 ——
        var quick = quickHit(number);
        if (quick != null) {
            Timber.d("FAST hit: %s -> %s", number, quick.value);
            final String value = quick.value;
            MAIN.post(() -> cb.onResult(value.isEmpty() ? null : value));
            return;
        }

        // —— 缓存 ——
        CacheStore.init(ctx);
        var cached = CacheStore.get(number);
        if (cached != null) {
            Timber.d("CACHE hit: %s -> %s", number, cached.isEmpty() ? "(negative)" : cached);
            final String value = cached;
            MAIN.post(() -> cb.onResult(value.isEmpty() ? null : value));
            return;
        }

        // —— 后台执行 ——
        IO.execute(() -> {
            String result = null;
            try {
                result = resolveRemote(number);
            } catch (Throwable e) {
                Timber.e(e, "resolveRemote crashed for %s", number);
            }
            final String value = result;
            // 负缓存：未识别同样写入 ""，避免反复发起请求
            CacheStore.put(number, value == null ? "" : value);
            Timber.d("REMOTE result %s -> %s", number, value);
            MAIN.post(() -> cb.onResult(value));
        });
    }

    /** 同步、纯内存的快路径。返回 null 表示需要继续走异步链路。 */
    private static QuickHit quickHit(String number) {
        var bi = BUILTIN_DB.get(number);
        return bi != null ? new QuickHit(bi) : null;
    }

    /** 异步链路：离线号段 → 在线 JSONP。任一返回非空即截止。 */
    private static String resolveRemote(String number) {
        // 1. 在线优先：能拿到"山东 山东移动"这种地区 + 运营商组合
        var online = RemoteHttpLookup.lookupMobile(number);
        if (online != null && !online.isEmpty()) return online;

        // 2. 离线兜底：拿不到地区起码给出运营商
        var carrier = MobileSegment.carrierOf(number);
        if (carrier != null) return carrier;

        return null;
    }

    private record QuickHit(String value) {}
}
