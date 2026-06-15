package io.langya.module.data;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.CallLog;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public final class CallLogRepository {

    private static final String[] PROJECTION = {
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
    };

    private CallLogRepository() {}

    public static List<CallLogEntry> load(Context ctx, int limit) {
        var list = new ArrayList<CallLogEntry>();
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) return list;

        // Android 11+ 不允许 sort 里塞 LIMIT，必须走 Bundle 参数
        var args = new Bundle();
        args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{CallLog.Calls.DATE});
        args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);

        try (var c = ctx.getContentResolver().query(
                CallLog.Calls.CONTENT_URI, PROJECTION, args, null)) {
            if (c == null) return list;
            int idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID);
            int numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
            int typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE);
            int dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE);
            int durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION);
            while (c.moveToNext()) {
                list.add(new CallLogEntry(
                        c.getLong(idIdx),
                        c.getString(numIdx),
                        c.getInt(typeIdx),
                        c.getLong(dateIdx),
                        c.getLong(durIdx)));
            }
        }
        return list;
    }
}
