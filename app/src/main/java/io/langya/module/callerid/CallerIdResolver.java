package io.langya.module.callerid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import io.langya.module.contacts.ContactsRepository;

/**
 * 来电号码识别统一入口优先级：
 *   1. 系统通讯录备注名（同步、即刻）
 *   2. CallerIdLookup：内置 DB / 缓存 / 离线号段 / 在线手机归属地
 *
 * 所有 UI 现在都用这个 不要再直接 new CallerIdLookup()
 */
public final class CallerIdResolver {

    private CallerIdResolver() {}

    /** 异步解析；回调在主线程触发 */
    public static void resolve(Context ctx, String number, CallerIdResolverCallback cb) {
        if (number == null || number.isEmpty()) {
            post(() -> cb.onResult(null, false));
            return;
        }
        var contactName = ContactsRepository.lookupName(ctx, number);
        if (contactName != null && !contactName.isEmpty()) {
            post(() -> cb.onResult(contactName, true));
            return;
        }
        new CallerIdLookup().query(ctx, number, result -> cb.onResult(result, false));
    }

    /** 仅查通讯录的快捷方式（同步） */
    public static String contactNameOf(Context ctx, String number) {
        return ContactsRepository.lookupName(ctx, number);
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
