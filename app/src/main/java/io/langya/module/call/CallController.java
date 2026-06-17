package io.langya.module.call;

import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.VideoProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import timber.log.Timber;

/**
 * 全局通话状态枢纽：CallerIdInCallService 把 Call 喂进来 InCallActivity 消费并控制
 *
 * CallAudioState / InCallService.setMuted / setAudioRoute 在 API 35 后被
 * CallEndpoint 取代 但 minSdk=33 仍需老 API 整类抑制 deprecation
 */
@SuppressWarnings("deprecation")
public final class CallController {

    private static final CallController INSTANCE = new CallController();
    public static CallController get() { return INSTANCE; }

    private final List<Call> calls = new ArrayList<>();
    private final List<CallControllerListener> listeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private InCallService service;
    private CallAudioState audioState;

    private final Call.Callback callCallback = new Call.Callback() {
        @Override public void onStateChanged(Call call, int newState) { fireChanged(call); }
        @Override public void onDetailsChanged(Call call, Call.Details details) { fireChanged(call); }
    };

    private CallController() {}

    public void attach(InCallService svc) { this.service = svc; }
    public void detach() { this.service = null; }

    public void addCall(Call call) {
        if (calls.contains(call)) return;
        calls.add(call);
        // 指定主线程 Handler 避免 Call 回调跑在 binder 线程上 (#15)
        call.registerCallback(callCallback, mainHandler);
        Timber.d("addCall %s state=%d", call, call.getDetails().getState());
        for (var l : new ArrayList<>(listeners)) l.onCallAdded(call);
    }

    public void removeCall(Call call) {
        calls.remove(call);
        try { call.unregisterCallback(callCallback); } catch (Throwable ignored) {}
        for (var l : new ArrayList<>(listeners)) l.onCallRemoved(call);
    }

    public void updateAudioState(CallAudioState state) {
        this.audioState = state;
        for (var l : new ArrayList<>(listeners)) l.onAudioStateChanged(state);
    }

    public List<Call> getCalls() { return Collections.unmodifiableList(calls); }
    public Call primary() { return calls.isEmpty() ? null : calls.get(0); }
    public CallAudioState getAudioState() { return audioState; }

    public void addListener(CallControllerListener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(CallControllerListener l) { listeners.remove(l); }

    public void answer(Call call) {
        if (call == null) return;
        call.answer(VideoProfile.STATE_AUDIO_ONLY);
    }
    public void reject(Call call) {
        if (call == null) return;
        call.reject(false, null);
    }
    public void hangup(Call call) {
        if (call == null) return;
        call.disconnect();
    }
    public void setMuted(boolean muted) {
        if (service != null) service.setMuted(muted);
    }
    public void setSpeaker(boolean on) {
        if (service == null) return;
        service.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE);
    }
    public void sendDtmf(Call call, char digit) {
        if (call == null) return;
        call.playDtmfTone(digit);
        call.stopDtmfTone();
    }

    private void fireChanged(Call call) {
        for (var l : new ArrayList<>(listeners)) l.onCallChanged(call);
    }
}
