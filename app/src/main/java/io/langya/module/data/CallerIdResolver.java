package io.langya.module.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 来电号码识别统一入口。优先级：
 *   1. 系统通讯录备注名（同步、即刻）
 *   2. WebQueryHelper：内置 DB / 缓存 / 离线号段 / 在线手机归属地
 *
 * 所有 UI 现在都用这个，不要再直接 new WebQueryHelper()。
 */
public final class CallerIdResolver {

    public interface Callback {
        /**
         * @param displayName 用于主显示的姓名（联系人名 / 识别标签 / 原号码 / null）
         * @param fromContacts 是否来自通讯录
         */
        void onResult(String displayName, boolean fromContacts);
    }

    private CallerIdResolver() {}

    /** 异步解析；回调在主线程触发。 */
    public static void resolve(Context ctx, String number, Callback cb) {
        if (number == null || number.isEmpty()) {
            post(() -> cb.onResult(null, false));
            return;
        }
        var contactName = ContactsRepository.lookupName(ctx, number);
        if (contactName != null && !contactName.isEmpty()) {
            post(() -> cb.onResult(contactName, true));
            return;
        }
        new WebQueryHelper().query(ctx, number, result -> cb.onResult(result, false));
    }

    /** 仅查通讯录的快捷方式（同步）。 */
    public static String contactNameOf(Context ctx, String number) {
        return ContactsRepository.lookupName(ctx, number);
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
