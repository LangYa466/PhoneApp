package io.langya.module.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.ContactsContract;
import timber.log.Timber;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统通讯录查询。
 *
 * 两层匹配：
 *  1. ContactsContract.PhoneLookup —— 系统标准，绝大多数情况命中
 *  2. 内存预构建 Phone 表 HashMap 索引 —— PhoneLookup 漏掉时（+86 / 空格 / 国家码差异）
 *     用归一化后的数字串和「末 11 位」双 key 命中
 *
 * 索引懒加载并永久缓存；权限或联系人变化时调用 invalidate()。
 */
public final class ContactsRepository {

    private static final int CHINA_MOBILE_LEN = 11;

    private static volatile Map<String, String> phoneIndex = null;
    private static final Object indexLock = new Object();

    private ContactsRepository() {}

    public static String lookupName(Context ctx, String number) {
        if (number == null || number.isEmpty()) return null;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            Timber.w("READ_CONTACTS not granted, skip: " + number);
            return null;
        }

        // 1) PhoneLookup —— 最快、系统已做归一化
        var name = phoneLookup(ctx, number);
        if (name != null) return name;

        // 2) Fallback：自建索引 + 多种归一化 key 命中
        var idx = ensureIndex(ctx);
        var normalized = PhoneNormalizer.normalize(number);
        if (normalized.isEmpty()) return null;

        name = idx.get(normalized);
        if (name != null) {
            Timber.d("fallback hit (full): " + number + " -> " + name);
            return name;
        }
        if (normalized.length() > CHINA_MOBILE_LEN) {
            var tail = normalized.substring(normalized.length() - CHINA_MOBILE_LEN);
            name = idx.get(tail);
            if (name != null) {
                Timber.d("fallback hit (last-11): " + number + " -> " + name);
                return name;
            }
        }

        Timber.d("no match for: " + number + " (index size=" + idx.size() + ")");
        return null;
    }

    /** 权限变化、或用户在系统通讯录新增/修改联系人后调用，下次 lookup 时重建。 */
    public static void invalidate() {
        synchronized (indexLock) { phoneIndex = null; }
    }

    private static String phoneLookup(Context ctx, String number) {
        var uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number));
        try (var c = ctx.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                var name = c.getString(0);
                Timber.d("PhoneLookup hit: " + number + " -> " + name);
                return name;
            }
        } catch (Throwable t) {
            Timber.w(t, "PhoneLookup failed for %s", number);
        }
        return null;
    }

    private static Map<String, String> ensureIndex(Context ctx) {
        var idx = phoneIndex;
        if (idx != null) return idx;
        synchronized (indexLock) {
            if (phoneIndex != null) return phoneIndex;
            phoneIndex = buildIndex(ctx);
            return phoneIndex;
        }
    }

    private static Map<String, String> buildIndex(Context ctx) {
        var map = new HashMap<String, String>();
        try (var c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                },
                null, null, null)) {
            if (c == null) {
                Timber.w("Phone provider returned null cursor");
                return map;
            }
            int numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER);
            int nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            while (c.moveToNext()) {
                var num = c.getString(numIdx);
                var name = c.getString(nameIdx);
                if (num == null || num.isEmpty() || name == null) continue;
                var normalized = PhoneNormalizer.normalize(num);
                if (normalized.isEmpty()) continue;
                map.putIfAbsent(normalized, name);
                if (normalized.length() > CHINA_MOBILE_LEN) {
                    map.putIfAbsent(
                            normalized.substring(normalized.length() - CHINA_MOBILE_LEN),
                            name);
                }
            }
        } catch (Throwable t) {
            Timber.w(t, "buildIndex failed");
        }
        Timber.d("Phone index built, keys=" + map.size());
        return map;
    }

    /** 让 UI 主动暖起索引并返回索引 key 数（用于诊断 toast）。 */
    public static int prefetchAndCount(Context ctx) {
        invalidate();
        return ensureIndex(ctx).size();
    }
}
