package io.langya.module.callerid;

import static io.langya.module.callerid.BuiltInDirectory.BUILTIN_DB;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import timber.log.Timber;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 号码识别管线流水线（顺序短路）：
 *   1) BUILTIN_DB        （内置 3000+ 条 含报警/急救/客服等原 SPECIAL_NUMBERS）
 *   2) CallerIdCache        （磁盘缓存 包括"已查过但未识别"的负缓存）
 *   3) 离线手机号段       （3 位前缀 → 运营商；4 位前缀 → 虚拟运营商）
 *   4) 百度网页抓取       （{@link BaiduWebLookup} 离屏 WebView 跑百度搜索
 *                          innerText substring 解析全程 try-catch 失败回 null
 *                          不需要 SYSTEM_ALERT_WINDOW淘宝 JSONP 已废弃）
 *
 * 线程模型：
 *   - 全部 IO 走单线程 ExecutorService（拨号期间不会和 UI 抢主循环）；
 *   - WebView 抓取在主线程创建IO 线程 CountDownLatch 等结果 8 秒超时；
 *   - 所有 Callback 都在主线程触发；
 *   - 公共 API（{@link #query} / {@link CallerIdLookupCallback}）保持兼容 调用方无需改动
 */
public final class CallerIdLookup {

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

    /**
     * 异步查询Callback 在主线程触发多次重入安全 但不会去重——
     * 串行去重在更上层（{@link CallerIdBatchQueue}）做
     */
    public void query(Context ctx, String number, CallerIdLookupCallback cb) {
        Timber.d("query: %s", number);
        if (number == null || number.isEmpty()) {
            MAIN.post(() -> cb.onResult(null));
            return;
        }

        var quick = quickHit(number);
        if (quick != null) {
            Timber.d("FAST hit: %s -> %s", number, quick.value());
            final String value = quick.value();
            MAIN.post(() -> cb.onResult(value.isEmpty() ? null : value));
            return;
        }

        CallerIdCache.init(ctx);
        var cached = CallerIdCache.get(number);
        if (cached != null) {
            Timber.d("CACHE hit: %s -> %s", number, cached.isEmpty() ? "(negative)" : cached);
            final String value = cached;
            MAIN.post(() -> cb.onResult(value.isEmpty() ? null : value));
            return;
        }

        var appCtx = ctx.getApplicationContext();
        IO.execute(() -> {
            String result = null;
            try {
                result = resolveRemote(appCtx, number);
            } catch (Throwable e) {
                Timber.e(e, "resolveRemote crashed for %s", number);
            }
            final String value = result;
            // 负缓存：未识别同样写入 "" 避免反复发起请求
            CallerIdCache.put(number, value == null ? "" : value);
            Timber.d("REMOTE result %s -> %s", number, value);
            MAIN.post(() -> cb.onResult(value));
        });
    }

    /** 同步、纯内存的快路径返回 null 表示需要继续走异步链路 */
    private static CallerIdQuickHit quickHit(String number) {
        var bi = BUILTIN_DB.get(number);
        return bi != null ? new CallerIdQuickHit(bi) : null;
    }

    /** 异步链路：离线号段 → 百度网页抓取任一返回非空即截止 */
    private static String resolveRemote(Context appCtx, String number) {
        // 1. 离线兜底：先拿运营商 (毫秒级 无网络也能用)
        var carrier = CarrierSegments.carrierOf(number);
        if (carrier != null) return carrier;

        // 2. 百度网页抓取 innerText substring (try-catch in BaiduWebLookup)
        var web = BaiduWebLookup.lookup(appCtx, number);
        if (web != null && !web.isEmpty()) return web;

        return null;
    }
}
