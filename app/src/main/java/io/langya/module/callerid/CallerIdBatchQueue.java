package io.langya.module.callerid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import timber.log.Timber;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import io.langya.module.contacts.ContactsRepository;

/**
 * 通话记录历史号码后台识别 —— 串行队列
 * 滚动期间多次入队同号会被去重每完成一次回调 UI 刷对应行
 */
public final class CallerIdBatchQueue {

    private static final Deque<CallerIdBatchJob> queue = new ArrayDeque<>();
    private static final Set<String> queued = new HashSet<>();
    private static final Object lock = new Object();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean running = false;

    private CallerIdBatchQueue() {}

    public static void enqueue(Context ctx, String number, CallerIdBatchListener listener) {
        if (number == null || number.isEmpty()) return;
        synchronized (lock) {
            if (queued.contains(number)) return;
            queued.add(number);
            queue.add(new CallerIdBatchJob(ctx.getApplicationContext(), number, listener));
            if (!running) {
                running = true;
                main.post(CallerIdBatchQueue::runNext);
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
        CallerIdBatchJob j;
        synchronized (lock) {
            j = queue.poll();
            if (j == null) { running = false; return; }
            queued.remove(j.number());
        }

        var contact = ContactsRepository.lookupName(j.ctx(), j.number());
        if (contact != null) {
            j.listener().onResolved(j.number(), contact);
            main.post(CallerIdBatchQueue::runNext);
            return;
        }
        CallerIdCache.init(j.ctx());
        var cached = CallerIdCache.get(j.number());
        if (cached != null) {
            j.listener().onResolved(j.number(), cached);
            main.post(CallerIdBatchQueue::runNext);
            return;
        }

        Timber.d("CallerIdLookup: %s", j.number());
        new CallerIdLookup().query(j.ctx(), j.number(), result -> {
            j.listener().onResolved(j.number(), result);
            main.post(CallerIdBatchQueue::runNext);
        });
    }
}
