package io.langya.module.contacts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import androidx.core.content.ContextCompat;
import timber.log.Timber;

import io.langya.module.callerid.PhoneNumbers;

/**
 * 拨号盘顶部命中搜索
 *  - 通讯录命中: 按号码归一化后包含输入数字
 *  - 历史命中: 通话记录里包含但不在通讯录的号码
 *
 * 比单纯 Phone.NUMBER LIKE 更鲁棒 因为通讯录里号码格式各异 (+86 / 空格 / 横杠)
 * 走内存归一化后 contains 匹配 量级 200-500 条联系人完全够用
 */
public final class DialerMatchRepository {

    private static final int CONTACT_LIMIT = 8;
    private static final int OTHERS_LIMIT = 4;

    private DialerMatchRepository() {}

    public record ContactMatch(long id, String name, String number, String label, String photoUri) {}

    public record OtherMatch(String number, long lastCallDate) {}

    public static List<ContactMatch> findContacts(Context ctx, String digits) {
        var out = new ArrayList<ContactMatch>();
        if (digits == null || digits.isEmpty()) return out;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return out;

        try (var c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.LABEL,
                        ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
                },
                null, null, null)) {
            if (c == null) return out;
            int idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
            int nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER);
            int typeIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE);
            int labelIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL);
            int photoIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI);
            var seen = new HashSet<Long>();
            while (c.moveToNext() && out.size() < CONTACT_LIMIT) {
                var num = c.getString(numIdx);
                if (num == null) continue;
                var normalized = PhoneNumbers.normalize(num);
                if (!normalized.contains(digits)) continue;
                long cid = c.getLong(idIdx);
                if (!seen.add(cid)) continue;
                var name = c.getString(nameIdx);
                int type = c.getInt(typeIdx);
                var customLabel = c.getString(labelIdx);
                var label = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(ctx.getResources(), type, customLabel).toString();
                out.add(new ContactMatch(cid, name, num, label, c.getString(photoIdx)));
            }
        } catch (Throwable t) {
            Timber.w(t, "findContacts failed: %s", digits);
        }
        return out;
    }

    public static List<OtherMatch> findOthers(Context ctx, String digits, Set<String> excludeNormalized) {
        var out = new ArrayList<OtherMatch>();
        if (digits == null || digits.isEmpty()) return out;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) return out;

        try (var c = ctx.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.DATE},
                null, null,
                CallLog.Calls.DATE + " DESC")) {
            if (c == null) return out;
            int numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
            int dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE);
            var seen = new HashSet<String>();
            while (c.moveToNext() && out.size() < OTHERS_LIMIT) {
                var num = c.getString(numIdx);
                if (num == null || num.isEmpty()) continue;
                var normalized = PhoneNumbers.normalize(num);
                if (!normalized.contains(digits)) continue;
                if (excludeNormalized.contains(normalized)) continue;
                if (!seen.add(normalized)) continue;
                out.add(new OtherMatch(num, c.getLong(dateIdx)));
            }
        } catch (Throwable t) {
            Timber.w(t, "findOthers failed: %s", digits);
        }
        return out;
    }
}
