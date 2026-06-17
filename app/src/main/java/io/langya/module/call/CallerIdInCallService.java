package io.langya.module.call;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import io.langya.module.ui.incall.InCallActivity;

import timber.log.Timber;

/**
 * 系统在本应用成为默认电话应用后绑定本服务 每一通来去电都会回调到这里
 * 把 Call 喂进 {@link CallController} UI 层从那里取
 *
 * 通过监听 {@link CallControllerListener} 转发到 {@link CallNotifier}
 * 让通知栏 / 锁屏 heads-up 跟系统电话应用一致：
 *  - 来电 Ringing 全屏 incoming-call 通知 + answer/decline 按钮
 *  - 通话中 ongoing-call 通知 + hangup/speaker/mute 按钮
 *  - 计时器自动更新 通话结束自动消失
 *
 * onCallAudioStateChanged + CallAudioState 在 API 35 起被标记 deprecated（被
 * CallEndpoint 取代） 但新 API 需要走 onAvailableCallEndpoints
 * 流程 而我们的 minSdk=33 必须双路兜底——这里整类抑制 deprecation
 */
@SuppressWarnings("deprecation")
public class CallerIdInCallService extends InCallService {

    private final CallControllerListener listener = new CallControllerListener() {
        @Override public void onCallAdded(Call call) {
            CallNotifier.show(CallerIdInCallService.this, call);
        }
        @Override public void onCallRemoved(Call call) {
            if (CallController.get().primary() == null) {
                CallNotifier.cancel(CallerIdInCallService.this);
            } else {
                CallNotifier.refresh(CallerIdInCallService.this);
            }
        }
        @Override public void onCallChanged(Call call) {
            CallNotifier.refresh(CallerIdInCallService.this);
        }
        @Override public void onAudioStateChanged(CallAudioState state) {
            CallNotifier.refresh(CallerIdInCallService.this);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        CallNotifier.ensureChannel(this);
        CallController.get().addListener(listener);
    }

    @Override
    public void onDestroy() {
        CallController.get().removeListener(listener);
        CallNotifier.cancel(this);
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Timber.d("onCallAdded %s", call);
        CallController.get().attach(this);
        CallController.get().addCall(call);

        // Ringing: 用 full-screen-intent 通过通知拉起锁屏 UI（背景启动 Activity 在新版本会被拦）
        // 其他状态（拨号 / 接通中 / 通话中）直接把 Activity 推到前台 用户能立即操作
        if (call.getDetails().getState() != Call.STATE_RINGING) {
            startActivity(new Intent(this, InCallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Timber.d("onCallRemoved %s", call);
        CallController.get().removeCall(call);
        if (CallController.get().getCalls().isEmpty()) {
            CallController.get().detach();
        }
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        super.onCallAudioStateChanged(audioState);
        CallController.get().updateAudioState(audioState);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        CallController.get().detach();
        return super.onUnbind(intent);
    }
}
