package io.langya.module.service;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import timber.log.Timber;

import io.langya.module.call.CallManager;
import io.langya.module.ui.InCallActivity;

/**
 * 系统在本应用成为默认电话应用后绑定本服务，每一通来去电都会回调到这里。
 * 直接拉起 InCallActivity，不发通知。
 *
 * onCallAudioStateChanged + CallAudioState 在 API 35 起被标记 deprecated（被
 * CallEndpoint 取代），但新 API 需要 InCallService 走 onAvailableCallEndpoints
 * 流程，而我们的 minSdk=33 必须双路兜底——这里整类抑制 deprecation。
 */
@SuppressWarnings("deprecation")
public class PhoneCallService extends InCallService {


    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Timber.d("onCallAdded %s", call);
        CallManager.get().attach(this);
        CallManager.get().addCall(call);

        var i = new Intent(this, InCallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Timber.d("onCallRemoved %s", call);
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
