package io.langya.module.service;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.util.Log;

import io.langya.module.call.CallManager;
import io.langya.module.ui.InCallActivity;

/**
 * 系统在本应用成为默认电话应用后绑定本服务，每一通来去电都会回调到这里。
 * 直接拉起 InCallActivity，不发通知。
 */
public class PhoneCallService extends InCallService {

    private static final String TAG = "PhoneCallService";

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Log.d(TAG, "onCallAdded " + call);
        CallManager.get().attach(this);
        CallManager.get().addCall(call);

        var i = new Intent(this, InCallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Log.d(TAG, "onCallRemoved " + call);
        CallManager.get().removeCall(call);
        if (CallManager.get().getCalls().isEmpty()) {
            CallManager.get().detach();
        }
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        super.onCallAudioStateChanged(audioState);
        CallManager.get().updateAudioState(audioState);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        CallManager.get().detach();
        return super.onUnbind(intent);
    }
}
