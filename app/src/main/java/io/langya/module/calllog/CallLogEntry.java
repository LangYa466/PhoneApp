package io.langya.module.calllog;

import android.provider.CallLog;

public record CallLogEntry(long id, String number, int type, long date, long durationSec) {

    public boolean isMissed()   { return type == CallLog.Calls.MISSED_TYPE; }
    public boolean isIncoming() { return type == CallLog.Calls.INCOMING_TYPE; }
    public boolean isOutgoing() { return type == CallLog.Calls.OUTGOING_TYPE; }
}
