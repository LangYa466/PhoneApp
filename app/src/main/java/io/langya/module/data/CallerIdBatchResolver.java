package io.langya.module.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import timber.log.Timber;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 通话记录历史号码后台识别 —— 串行队列，每次只跑一个 WebView。
 * 滚动期间多次入队同号会被去重。每完成一次回调到 Listener，UI 刷对应行。
 */
public final class CallerIdBatchResolver {


    public interface Listener {
        /**
         * 识别完成，在主线程触发。
         * @param name 识别结果；null/空 = 未知号码
         */
        void onResolved(String number, String name);
    }

    private static final Deque<Job> queue = new ArrayDeque<>();
    private static final Set<String> queued = new HashSet<>();
    private static final Object lock = new Object();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean running = false;

    private record Job(Context ctx, String number, Listener listener) {}

    private CallerIdBatchResolver() {}

    public static void enqueue(Context ctx, String number, Listener listener) {
        if (number == null || number.isEmpty()) return;
        synchronized (lock) {
            if (queued.contains(number)) return;
            queued.add(number);
            queue.add(new Job(ctx.getApplicationContext(), number, listener));
            if (!running) {
                running = true;
                main.post(CallerIdBatchResolver::runNext);
            }
        }
    }

    public static void cancelAll() {
        synchronized (lock) {
            queue.clear();
            queued.clear();
        }
    }

    private static void runNext() {
        Job j;
        synchronized (lock) {
            j = queue.poll();
            if (j == null) { running = false; return; }
            queued.remove(j.number);
        }

        // 通讯录命中：跳过网络
        var contact = ContactsRepository.lookupName(j.ctx, j.number);
        if (contact != null) {
            j.listener.onResolved(j.number, contact);
            main.post(CallerIdBatchResolver::runNext);
            return;
        }
        // 缓存命中：跳过网络
        CacheStore.init(j.ctx);
        var cached = CacheStore.get(j.number);
        if (cached != null) {
            j.listener.onResolved(j.number, cached);
            main.post(CallerIdBatchResolver::runNext);
            return;
        }

        // 真正发起网络/内置 DB 查询（WebQueryHelper 内部对 BUILTIN/SPECIAL 命中走快路径）
        Timber.d("WebQuery: %s", j.number);
        new WebQueryHelper().query(j.ctx, j.number, result -> {
            j.listener.onResolved(j.number, result);
            main.post(CallerIdBatchResolver::runNext);
        });
    }
}
