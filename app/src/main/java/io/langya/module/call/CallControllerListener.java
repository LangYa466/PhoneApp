package io.langya.module.call;

import android.telecom.Call;
import android.telecom.CallAudioState;

@SuppressWarnings("deprecation")
public interface CallControllerListener {
    void onCallAdded(Call call);
    void onCallRemoved(Call call);
    void onCallChanged(Call call);
    void onAudioStateChanged(CallAudioState state);
}
