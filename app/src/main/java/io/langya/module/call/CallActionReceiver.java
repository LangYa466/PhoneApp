package io.langya.module.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.CallAudioState;

import io.langya.module.R;

import timber.log.Timber;

/**
 * 系统通知里 挂断 / 接听 / 拒接 / 免提 / 静音 按钮点击的入口
 * 直接调 {@link CallController} 单例操作当前 primary 通话
 *
 * 每个 ACTION 常量都通过 {@link #label(Context, String)} 映射到中文显示标签
 * Speaker / Mute 标签会真正贴到 {@link android.app.Notification.Action}
 * 挂断 / 接听 / 拒接 由系统 {@link android.app.Notification.CallStyle}
 * 内置渲染（跟随设备系统语言）App 改不了
 */
public class CallActionReceiver extends BroadcastReceiver {

    public static final String ACTION_HANGUP  = "io.langya.module.call.HANGUP";
    public static final String ACTION_ANSWER  = "io.langya.module.call.ANSWER";
    public static final String ACTION_REJECT  = "io.langya.module.call.REJECT";
    public static final String ACTION_SPEAKER = "io.langya.module.call.SPEAKER";
    public static final String ACTION_MUTE    = "io.langya.module.call.MUTE";

    /**
     * 给定 action 常量返回它的中文显示标签
     * 把每个 ACTION → 中文的对应关系压在一处 别处只引用这个方法 不再散落 R.string
     */
    public static String label(Context ctx, String action) {
        int res = switch (action) {
            case ACTION_HANGUP  -> R.string.incall_hangup;
            case ACTION_ANSWER  -> R.string.incall_answer;
            case ACTION_REJECT  -> R.string.incall_reject;
            case ACTION_SPEAKER -> R.string.incall_dock_speaker;
            case ACTION_MUTE    -> R.string.incall_mute;
            default -> 0;
        };
        return res == 0 ? "" : ctx.getString(res);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        var action = intent.getAction();
        if (action == null) return;

        var ctrl = CallController.get();
        var call = ctrl.primary();
        Timber.d("notification action %s call=%s", action, call);

        switch (action) {
            case ACTION_HANGUP -> ctrl.hangup(call);
            case ACTION_ANSWER -> ctrl.answer(call);
            case ACTION_REJECT -> ctrl.reject(call);
            case ACTION_SPEAKER -> {
                var state = ctrl.getAudioState();
                boolean on = state != null && (state.getRoute() & CallAudioState.ROUTE_SPEAKER) != 0;
                ctrl.setSpeaker(!on);
                CallNotifier.refresh(ctx);
            }
            case ACTION_MUTE -> {
                var state = ctrl.getAudioState();
                ctrl.setMuted(state == null || !state.isMuted());
                CallNotifier.refresh(ctx);
            }
            default -> Timber.w("unknown notification action %s", action);
        }
    }
}
