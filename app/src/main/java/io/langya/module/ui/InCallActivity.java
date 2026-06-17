package io.langya.module.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.mikepenz.iconics.IconicsDrawable;

import io.langya.module.R;
import io.langya.module.call.CallManager;
import io.langya.module.data.CacheStore;
import io.langya.module.data.ContactsRepository;
import io.langya.module.data.WebQueryHelper;
import io.langya.module.databinding.ActivityInCallBinding;
import io.langya.module.databinding.InCallDockItemBinding;

// CallAudioState 路径在 minSdk=33 仍是唯一可用 API
public class InCallActivity extends AppCompatActivity implements CallManager.Listener {

    private static final int DOCK_DIALPAD = 0, DOCK_MUTE = 1, DOCK_SPEAKER = 2, DOCK_MORE = 3;

    private ActivityInCallBinding b;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() { tick(); main.postDelayed(this, 1_000L); }
    };

    private BottomSheetDialog dialpadSheet;
    private BottomSheetDialog moreSheet;
    private TextView dtmfDisplay;
    private TextView holdLabel;

    /** 当前正在显示的号码 —— 用作 WebQuery 异步回调的有效性判据，避免旧请求覆盖新号码。 */
    private String activeNumber = "";

    public static void launch(Context ctx) {
        var i = new Intent(ctx, InCallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyColor(this);
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        b = ActivityInCallBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        applyEdgeInsets();

        bindDock(b.dockDialpad, "mso-dialpad", R.string.incall_dock_dialpad,
                v -> { showDialpadSheet(); markDock(DOCK_DIALPAD); });
        bindDock(b.dockMute, "mso-mic", R.string.incall_dock_mute, v -> toggleMute());
        bindDock(b.dockSpeaker, "mso-volume_up", R.string.incall_dock_speaker, v -> toggleSpeaker());
        bindDock(b.dockMore, "mso-more_vert", R.string.incall_dock_more,
                v -> { showMoreSheet(); markDock(DOCK_MORE); });

        // 不立即 finish() —— 等 onCallRemoved 回来再走 (#11)
        b.btnHangup.setOnClickListener(v -> CallManager.get().hangup(primary()));
        b.btnAnswer.setOnClickListener(v -> CallManager.get().answer(primary()));
        b.btnReject.setOnClickListener(v -> CallManager.get().reject(primary()));

        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallManager.get().addListener(this);
        main.post(tickRunnable);
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CallManager.get().removeListener(this);
        main.removeCallbacks(tickRunnable);
    }

    @Override
    protected void onDestroy() {
        if (dialpadSheet != null) { dialpadSheet.dismiss(); dialpadSheet = null; }
        if (moreSheet != null)    { moreSheet.dismiss();    moreSheet = null; }
        super.onDestroy();
    }

    private Call primary() { return CallManager.get().primary(); }

    /** Android 15+ 强制 edge-to-edge，给挂断/接听行加上系统手势栏 inset，避免被挡。 */
    private void applyEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            b.tvDuration.setPadding(0, bars.top, 0, 0);
            ((android.view.ViewGroup.MarginLayoutParams) b.hangupRow.getLayoutParams())
                    .bottomMargin = bars.bottom + dp(24);
            b.hangupRow.requestLayout();
            return insets;
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void bindDock(InCallDockItemBinding dock, String iconKey, int labelRes, View.OnClickListener l) {
        dock.dockIcon.setImageDrawable(new IconicsDrawable(this, iconKey));
        dock.dockLabel.setText(labelRes);
        dock.getRoot().setOnClickListener(l);
    }

    private void markDock(int which) {
        b.dockDialpad.getRoot().setSelected(which == DOCK_DIALPAD);
        b.dockMute.getRoot().setSelected(which == DOCK_MUTE);
        b.dockSpeaker.getRoot().setSelected(which == DOCK_SPEAKER);
        b.dockMore.getRoot().setSelected(which == DOCK_MORE);
    }

    private void syncDockSelection() {
        var s = CallManager.get().getAudioState();
        boolean muted = s != null && s.isMuted();
        boolean speaker = s != null && (s.getRoute() & CallAudioState.ROUTE_SPEAKER) != 0;
        b.dockMute.getRoot().setSelected(muted);
        b.dockMute.dockIcon.setImageDrawable(new IconicsDrawable(this,
                muted ? "mso-mic_off" : "mso-mic"));
        b.dockSpeaker.getRoot().setSelected(speaker);
        b.dockDialpad.getRoot().setSelected(dialpadSheet != null && dialpadSheet.isShowing());
        b.dockMore.getRoot().setSelected(moreSheet != null && moreSheet.isShowing());
    }

    private void toggleMute() {
        var s = CallManager.get().getAudioState();
        CallManager.get().setMuted(s == null || !s.isMuted());
    }

    private void toggleSpeaker() {
        var s = CallManager.get().getAudioState();
        boolean on = s != null && (s.getRoute() & CallAudioState.ROUTE_SPEAKER) != 0;
        CallManager.get().setSpeaker(!on);
    }

    private void toggleHold() {
        var call = primary();
        if (call == null) return;
        int state = call.getDetails().getState();
        if (state == Call.STATE_HOLDING) call.unhold();
        else if (state == Call.STATE_ACTIVE) call.hold();
    }

    private void addCall() {
        var i = new Intent(Intent.ACTION_DIAL);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void showDialpadSheet() {
        if (dialpadSheet != null && dialpadSheet.isShowing()) return;
        var view = LayoutInflater.from(this).inflate(R.layout.sheet_in_call_dialpad, null);
        dialpadSheet = new BottomSheetDialog(this);
        dialpadSheet.setContentView(view);
        dialpadSheet.setOnDismissListener(d -> { dialpadSheet = null; syncDockSelection(); });

        dtmfDisplay = view.findViewById(R.id.tvDtmf);
        var btnClose = (com.google.android.material.button.MaterialButton)
                view.findViewById(R.id.btnCloseDialpad);
        btnClose.setIcon(new IconicsDrawable(this, "mso-close"));
        btnClose.setOnClickListener(v -> dialpadSheet.dismiss());

        wireDtmf(view, R.id.dtmf1, '1', "");
        wireDtmf(view, R.id.dtmf2, '2', "ABC");
        wireDtmf(view, R.id.dtmf3, '3', "DEF");
        wireDtmf(view, R.id.dtmf4, '4', "GHI");
        wireDtmf(view, R.id.dtmf5, '5', "JKL");
        wireDtmf(view, R.id.dtmf6, '6', "MNO");
        wireDtmf(view, R.id.dtmf7, '7', "PQRS");
        wireDtmf(view, R.id.dtmf8, '8', "TUV");
        wireDtmf(view, R.id.dtmf9, '9', "WXYZ");
        wireDtmf(view, R.id.dtmfStar, '*', "");
        wireDtmf(view, R.id.dtmf0, '0', "+");
        wireDtmf(view, R.id.dtmfHash, '#', "");

        dialpadSheet.show();
    }

    private void wireDtmf(View parent, int rootId, char digit, String sub) {
        var item = parent.findViewById(rootId);
        ((TextView) item.findViewById(R.id.keyDigit)).setText(String.valueOf(digit));
        var s = (TextView) item.findViewById(R.id.keySub);
        s.setText(sub);
        s.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        item.setOnClickListener(v -> {
            CallManager.get().sendDtmf(primary(), digit);
            if (dtmfDisplay != null) {
                dtmfDisplay.setText(dtmfDisplay.getText().toString() + digit);
            }
        });
    }

    private void showMoreSheet() {
        if (moreSheet != null && moreSheet.isShowing()) return;
        var view = LayoutInflater.from(this).inflate(R.layout.sheet_in_call_more, null);
        moreSheet = new BottomSheetDialog(this);
        moreSheet.setContentView(view);
        moreSheet.setOnDismissListener(d -> { moreSheet = null; syncDockSelection(); });

        holdLabel = view.findViewById(R.id.tvHoldLabel);
        refreshHoldRow();

        var btnClose = (com.google.android.material.button.MaterialButton)
                view.findViewById(R.id.btnCloseMore);
        btnClose.setIcon(new IconicsDrawable(this, "mso-close"));
        btnClose.setOnClickListener(v -> moreSheet.dismiss());
        view.findViewById(R.id.rowAddCall).setOnClickListener(v -> { addCall(); moreSheet.dismiss(); });
        view.findViewById(R.id.rowHold).setOnClickListener(v -> { toggleHold(); refreshHoldRow(); });
        moreSheet.show();
    }

    private void refreshHoldRow() {
        if (holdLabel == null) return;
        var call = primary();
        boolean holding = call != null && call.getDetails().getState() == Call.STATE_HOLDING;
        holdLabel.setText(holding ? R.string.incall_more_unhold : R.string.incall_more_hold);
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        var call = primary();
        if (call == null) { finish(); return; }

        var number = extractNumber(call);
        int state = call.getDetails().getState();

        boolean ringing = state == Call.STATE_RINGING;
        b.ringingRow.setVisibility(ringing ? View.VISIBLE : View.GONE);
        b.btnHangup.setVisibility(ringing ? View.GONE : View.VISIBLE);
        b.dockRow.setVisibility(ringing ? View.INVISIBLE : View.VISIBLE);

        switch (state) {
            case Call.STATE_RINGING       -> b.tvDuration.setText(R.string.incall_state_ringing);
            case Call.STATE_DIALING       -> b.tvDuration.setText(R.string.incall_state_dialing);
            case Call.STATE_CONNECTING    -> b.tvDuration.setText(R.string.incall_state_connecting);
            case Call.STATE_HOLDING       -> b.tvDuration.setText(R.string.incall_state_holding);
            case Call.STATE_DISCONNECTING -> b.tvDuration.setText(R.string.incall_state_disconnecting);
            default -> { /* active → tick() updates */ }
        }

        // 号码没变就不重做识别 —— 避免 onCallChanged 高频触发时反复查询竞态 (#3)
        var safeNumber = (number == null || number.isEmpty()) ? "" : number;
        if (safeNumber.equals(activeNumber)) {
            syncDockSelection();
            refreshHoldRow();
            return;
        }
        activeNumber = safeNumber;

        if (safeNumber.isEmpty()) {
            b.tvName.setText(R.string.incall_unknown);
            b.tvCallerId.setText("");
        } else {
            // tvName 永远显示「主名称」：通讯录备注名优先，否则原号码
            var contactName = ContactsRepository.lookupName(this, safeNumber);
            b.tvName.setText(contactName != null ? contactName : safeNumber);

            // tvCallerId 永远显示「识别副信息」：WebQuery 结果（地区/运营商/企业等）
            b.tvCallerId.setText(R.string.incall_querying);
            // 同步先看缓存
            var cached = CacheStore.get(safeNumber);
            if (cached != null && !cached.isEmpty()) {
                b.tvCallerId.setText(cached);
            } else if (cached != null) {
                // 空字符串表示「曾查过、未知」
                b.tvCallerId.setText("");
            } else {
                // 异步识别
                new WebQueryHelper().query(this, safeNumber, result -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!safeNumber.equals(activeNumber)) return;
                    b.tvCallerId.setText(result == null || result.isEmpty() ? "" : result);
                }));
            }
        }

        syncDockSelection();
        refreshHoldRow();
    }

    private void tick() {
        var call = primary();
        if (call == null) return;
        if (call.getDetails().getState() != Call.STATE_ACTIVE) return;
        long start = call.getDetails().getConnectTimeMillis();
        if (start <= 0) return;
        long sec = (System.currentTimeMillis() - start) / 1000L;
        b.tvDuration.setText(String.format("%02d:%02d", sec / 60, sec % 60));
    }

    private String extractNumber(Call call) {
        var handle = call.getDetails().getHandle();
        if (handle == null) return null;
        var scheme = handle.getScheme();
        // 只处理 tel:/voicemail:，sip: 等忽略号码部分 (#10)
        if (scheme == null) return null;
        if (!"tel".equalsIgnoreCase(scheme) && !"voicemail".equalsIgnoreCase(scheme)) return null;
        return handle.getSchemeSpecificPart();
    }

    @Override public void onCallAdded(Call call) { runOnUiThread(this::render); }
    @Override public void onCallRemoved(Call call) {
        runOnUiThread(() -> { if (primary() == null) finish(); else render(); });
    }
    @Override public void onCallChanged(@NonNull Call call) {
        runOnUiThread(() -> { render(); refreshHoldRow(); });
    }
    @Override public void onAudioStateChanged(CallAudioState state) {
        runOnUiThread(this::syncDockSelection);
    }
}
