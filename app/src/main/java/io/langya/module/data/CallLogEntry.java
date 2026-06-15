package io.langya.module.data;

public record CallLogEntry(long id, String number, int type, long date, long durationSec) {

    public boolean isMissed()   { return type == android.provider.CallLog.Calls.MISSED_TYPE; }
    public boolean isIncoming() { return type == android.provider.CallLog.Calls.INCOMING_TYPE; }
    public boolean isOutgoing() { return type == android.provider.CallLog.Calls.OUTGOING_TYPE; }
}
