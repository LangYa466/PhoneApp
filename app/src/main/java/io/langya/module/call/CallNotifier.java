package io.langya.module.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;

import io.langya.module.R;
import io.langya.module.callerid.CallerIdCache;
import io.langya.module.contacts.ContactsRepository;
import io.langya.module.ui.incall.InCallActivity;

/**
 * 在通知栏发布一个 {@link Notification.CallStyle} 通知 跟系统电话应用展示效果一致：
 * 锁屏全屏来电 通话中 heads-up 控制条 通知中心常驻 ongoing
 *
 * 通知里的按钮 (Hang / Answer / Reject / Speaker / Mute) 经
 * {@link CallActionReceiver} 反射回 {@link CallController}
 */
@SuppressWarnings("deprecation")
public final class CallNotifier {

    public static final String CHANNEL_ID = "callerid_ongoing_call";
    public static final int NOTIFICATION_ID = 1001;

    private CallNotifier() {}

    public static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        var channel = new NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.fg_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setShowBadge(false);
        channel.setBypassDnd(true);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    public static void show(Context ctx, Call call) {
        if (call == null) { cancel(ctx); return; }
        var nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(ctx);
        nm.notify(NOTIFICATION_ID, build(ctx, call));
    }

    /** 当前 primary 通话状态变了 重新计算 */
    public static void refresh(Context ctx) {
        show(ctx, CallController.get().primary());
    }

    public static void cancel(Context ctx) {
        var nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    private static Notification build(Context ctx, Call call) {
        int state = call.getDetails().getState();
        String number = extractNumber(call);
        String name = resolveName(ctx, number);

        Person caller = new Person.Builder()
                .setName(name != null ? name : ctx.getString(R.string.incall_unknown))
                .setImportant(true)
                .build();

        PendingIntent contentIntent = PendingIntent.getActivity(
                ctx, 0,
                new Intent(ctx, InCallActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent hangupPi = pi(ctx, CallActionReceiver.ACTION_HANGUP, 1);
        PendingIntent answerPi = pi(ctx, CallActionReceiver.ACTION_ANSWER, 2);
        PendingIntent rejectPi = pi(ctx, CallActionReceiver.ACTION_REJECT, 3);

        Notification.CallStyle style = (state == Call.STATE_RINGING)
                ? Notification.CallStyle.forIncomingCall(caller, rejectPi, answerPi)
                : Notification.CallStyle.forOngoingCall(caller, hangupPi);

        var b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setColorized(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(style);

        // CallStyle 通知必须挂 fullScreenIntent 或 fg-service 否则系统抛 IllegalArgumentException
        // Ringing 是真用：锁屏直接拉起 Activity
        // Ongoing 则只挂着满足系统校验 屏幕没锁时不会被触发
        b.setFullScreenIntent(contentIntent, state == Call.STATE_RINGING);

        if (state != Call.STATE_RINGING) {
            b.addAction(speakerAction(ctx));
            b.addAction(muteAction(ctx));
            b.setWhen(call.getDetails().getConnectTimeMillis())
                    .setUsesChronometer(state == Call.STATE_ACTIVE);
        }
        return b.build();
    }

    private static Notification.Action speakerAction(Context ctx) {
        var state = CallController.get().getAudioState();
        boolean on = state != null && (state.getRoute() & CallAudioState.ROUTE_SPEAKER) != 0;
        // 默认 ACTION_SPEAKER 的中文 = 「免提」；speaker 已开时换成「听筒」提示反向操作
        String label = on
                ? ctx.getString(R.string.incall_speaker_off)
                : CallActionReceiver.label(ctx, CallActionReceiver.ACTION_SPEAKER);
        int icon = on
                ? android.R.drawable.stat_sys_phone_call
                : android.R.drawable.stat_sys_speakerphone;
        return new Notification.Action.Builder(
                icon, label,
                pi(ctx, CallActionReceiver.ACTION_SPEAKER, 4)).build();
    }

    private static Notification.Action muteAction(Context ctx) {
        var state = CallController.get().getAudioState();
        boolean muted = state != null && state.isMuted();
        // 默认 ACTION_MUTE 的中文 = 「静音」；当前已静音时换成「取消静音」
        String label = muted
                ? ctx.getString(R.string.incall_unmute)
                : CallActionReceiver.label(ctx, CallActionReceiver.ACTION_MUTE);
        int icon = muted
                ? android.R.drawable.ic_btn_speak_now
                : android.R.drawable.ic_lock_silent_mode;
        return new Notification.Action.Builder(
                icon, label,
                pi(ctx, CallActionReceiver.ACTION_MUTE, 5)).build();
    }

    private static PendingIntent pi(Context ctx, String action, int requestCode) {
        var i = new Intent(ctx, CallActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String extractNumber(Call call) {
        var h = call.getDetails().getHandle();
        if (h == null) return null;
        var scheme = h.getScheme();
        if (!"tel".equalsIgnoreCase(scheme) && !"voicemail".equalsIgnoreCase(scheme)) return null;
        return h.getSchemeSpecificPart();
    }

    private static String resolveName(Context ctx, String number) {
        if (number == null || number.isEmpty()) return null;
        var contact = ContactsRepository.lookupName(ctx, number);
        if (contact != null) return contact;
        var cached = CallerIdCache.get(number);
        if (cached != null && !cached.isEmpty()) return number + " · " + cached;
        return number;
    }
}
