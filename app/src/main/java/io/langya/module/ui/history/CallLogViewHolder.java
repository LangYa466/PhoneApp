package io.langya.module.ui.history;

import static android.R.attr.colorError;

import static com.google.android.material.R.attr.colorOnPrimaryContainer;
import static com.google.android.material.R.attr.colorOnSecondaryContainer;
import static com.google.android.material.R.attr.colorOnSurfaceVariant;
import static com.google.android.material.R.attr.colorOnTertiaryContainer;
import static com.google.android.material.R.attr.colorPrimaryContainer;
import static com.google.android.material.R.attr.colorSecondaryContainer;
import static com.google.android.material.R.attr.colorTertiaryContainer;

import java.util.Map;
import java.util.function.Consumer;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import io.langya.module.R;
import io.langya.module.callerid.CallerIdCache;
import io.langya.module.calllog.CallLogEntry;
import io.langya.module.contacts.ContactsRepository;
import io.langya.module.databinding.ItemCallLogBinding;
import io.langya.module.ui.ContactAvatars;
import io.langya.module.ui.Md3Icons;

final class CallLogViewHolder extends RecyclerView.ViewHolder {

    private static final int HISTORY_ICON_DP = 14;
    private static final int ROW_ICON_DP = 16;
    private static final int CALLBACK_ICON_DP = 20;
    private static final int ACTION_ICON_DP = 18;

    private final ItemCallLogBinding b;
    private final Map<String, String> liveNames;
    private final Consumer<String> onCallBack;

    CallLogViewHolder(ItemCallLogBinding b,
                      Map<String, String> liveNames,
                      Consumer<String> onCallBack) {
        super(b.getRoot());
        this.b = b;
        this.liveNames = liveNames;
        this.onCallBack = onCallBack;
    }

    void bind(CallLogAdapter.EntryGroup g, boolean expanded, Runnable onToggle) {
        var ctx = itemView.getContext();
        var head = g.head();
        int count = g.count();
        final String number = head.number();
        final boolean hasNumber = number != null && !number.isEmpty();

        String primary = hasNumber ? number : ctx.getString(R.string.calllog_unknown);
        String sub = formatSub(ctx, head);
        String contactPhotoUri = null;
        boolean isKnownContact = false;

        if (hasNumber) {
            var contactName = ContactsRepository.lookupName(ctx, number);
            if (contactName != null) {
                primary = contactName;
                sub = number + "  ·  " + sub;
                contactPhotoUri = ContactsRepository.lookupPhotoUri(ctx, number);
                isKnownContact = true;
            } else {
                var identification = liveNames.get(number);
                if (identification == null || identification.isEmpty()) {
                    CallerIdCache.init(ctx);
                    var cached = CallerIdCache.get(number);
                    if (cached != null && !cached.isEmpty()) identification = cached;
                }
                if (identification != null && !identification.isEmpty()) {
                    primary = identification;
                    sub = number + "  ·  " + sub;
                }
            }
        }

        // 折叠计数 同号码连续多通在同名右侧加 (N)
        String display = count > 1 ? primary + " (" + count + ")" : primary;
        b.tvNumber.setText(display);
        b.tvSub.setText(sub);
        b.tvAvatar.setText(initialOf(primary));
        applyAvatarPalette(ctx, b.avatarBg.getBackground(), b.tvAvatar,
                avatarSeed(hasNumber ? number : primary));

        // 头像 重置回字母态 再按需异步加载联系人照片 (view 复用必须先重置 否则旧 bitmap 闪现)
        ContactAvatars.clear(b.ivAvatar);
        b.ivAvatar.setVisibility(View.GONE);
        b.avatarBg.setVisibility(View.VISIBLE);
        b.tvAvatar.setVisibility(View.VISIBLE);
        if (contactPhotoUri != null) {
            ContactAvatars.load(ctx, contactPhotoUri, b.ivAvatar, success -> {
                if (!success) return;
                b.ivAvatar.setVisibility(View.VISIBLE);
                b.avatarBg.setVisibility(View.GONE);
                b.tvAvatar.setVisibility(View.GONE);
            });
        }

        // direction icon  (colorError 是 framework attr, 不在 material R 里)
        int errColor = resolveAttr(ctx, colorError);
        int subColor = resolveAttr(ctx, colorOnSurfaceVariant);
        int iconColor = head.isMissed() ? errColor : subColor;
        b.ivType.setImageDrawable(Md3Icons.of(ctx, iconKeyFor(head), ROW_ICON_DP));
        b.ivType.setImageTintList(ColorStateList.valueOf(iconColor));
        b.tvSub.setTextColor(head.isMissed() ? errColor : subColor);

        b.btnCallBack.setIcon(Md3Icons.of(ctx, "mso-call", CALLBACK_ICON_DP));
        b.btnCallBack.setOnClickListener(v -> onCallBack.accept(number));

        // 行点击 单通直接回拨 多通展开/收起
        if (count > 1) {
            b.rowHeader.setOnClickListener(v -> onToggle.run());
        } else {
            b.rowHeader.setOnClickListener(v -> { if (hasNumber) onCallBack.accept(number); });
        }

        // 展开面板
        boolean canExpand = count > 1;
        if (canExpand && expanded) {
            b.expandedContent.setVisibility(View.VISIBLE);
            populateHistory(ctx, g, subColor);
            setupActions(ctx, number, hasNumber, isKnownContact);
        } else {
            b.expandedContent.setVisibility(View.GONE);
            b.historyList.removeAllViews();
        }
    }

    /** 列出该组所有时间戳, 每行一个方向 icon + 绝对时间 */
    private void populateHistory(Context ctx, CallLogAdapter.EntryGroup g, int subColor) {
        b.historyList.removeAllViews();
        var inflater = LayoutInflater.from(ctx);
        int errColor = resolveAttr(ctx, colorError);
        for (var entry : g.entries()) {
            var row = (LinearLayout) inflater.inflate(
                    R.layout.view_call_log_history_row, b.historyList, false);
            var icon = (ImageView) row.findViewById(R.id.ivHistoryType);
            var label = (TextView) row.findViewById(R.id.tvHistoryLine);
            int c = entry.isMissed() ? errColor : subColor;
            icon.setImageDrawable(Md3Icons.of(ctx, iconKeyFor(entry), HISTORY_ICON_DP));
            icon.setImageTintList(ColorStateList.valueOf(c));
            label.setText(formatHistoryLine(ctx, entry));
            label.setTextColor(c);
            b.historyList.addView(row);
        }
    }

    private void setupActions(Context ctx, String number, boolean hasNumber, boolean isKnownContact) {
        b.btnExpandMessage.setIcon(Md3Icons.of(ctx, "mso-chat", ACTION_ICON_DP));
        b.btnExpandContact.setIcon(Md3Icons.of(ctx,
                isKnownContact ? "mso-person" : "mso-person_add", ACTION_ICON_DP));

        b.btnExpandMessage.setEnabled(hasNumber);
        b.btnExpandContact.setEnabled(hasNumber);

        b.btnExpandMessage.setOnClickListener(v -> {
            if (!hasNumber) return;
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("smsto:" + number))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ex) {
                Toast.makeText(ctx, R.string.toast_no_app_to_handle, Toast.LENGTH_SHORT).show();
            }
        });

        b.btnExpandContact.setOnClickListener(v -> {
            if (!hasNumber) return;
            Intent intent = null;
            if (isKnownContact) {
                // PhoneLookup CONTENT_FILTER_URI 不能直接 ACTION_VIEW
                // 必须先解出真正的 contact lookup uri
                var contactUri = ContactsRepository.lookupContactUri(ctx, number);
                if (contactUri != null) {
                    intent = new Intent(Intent.ACTION_VIEW, contactUri);
                }
            }
            if (intent == null) {
                // 没联系人 / 解 URI 失败 → 新建 (用 CONTENT_TYPE 不是 ITEM_TYPE)
                intent = new Intent(Intent.ACTION_INSERT)
                        .setType(ContactsContract.Contacts.CONTENT_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, number);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent);
            } catch (ActivityNotFoundException ex) {
                Toast.makeText(ctx, R.string.toast_no_app_to_handle, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String iconKeyFor(CallLogEntry e) {
        if (e.isMissed())   return "mso-call_missed";
        if (e.isIncoming()) return "mso-call_received";
        if (e.isOutgoing()) return "mso-call_made";
        return "mso-call";
    }

    private static String initialOf(String s) {
        if (s == null) return "#";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) return String.valueOf(Character.toUpperCase(c));
            if (Character.isDigit(c)) return String.valueOf(c);
        }
        return "#";
    }

    private static String avatarSeed(String s) {
        return s == null ? "" : s;
    }

    /**
     * 按 hash 在 primary / secondary / tertiary container 三色里轮转
     * 同号码每次进列表颜色一致 不同号码颜色分散
     * 同一 drawable XML inflate 的实例共用 ConstantState 必须 mutate() 否则污染其它行
     */
    private static void applyAvatarPalette(Context ctx,
                                           Drawable bg,
                                           TextView label,
                                           String seed) {
        int[][] palette = {
                {colorPrimaryContainer, colorOnPrimaryContainer},
                {colorSecondaryContainer, colorOnSecondaryContainer},
                {colorTertiaryContainer, colorOnTertiaryContainer},
        };
        int idx = Math.floorMod(seed.hashCode(), palette.length);
        int bgColor = resolveAttr(ctx, palette[idx][0]);
        int fgColor = resolveAttr(ctx, palette[idx][1]);
        if (bg instanceof GradientDrawable gd) {
            gd.mutate();
            gd.setColor(bgColor);
        }
        label.setTextColor(fgColor);
    }

    private static int resolveAttr(Context ctx, int attr) {
        var tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return ContextCompat.getColor(ctx, tv.resourceId);
            return tv.data;
        }
        return Color.GRAY;
    }

    private static String formatSub(Context ctx, CallLogEntry e) {
        var type = switch (e.type()) {
            case CallLog.Calls.INCOMING_TYPE -> ctx.getString(R.string.calllog_incoming);
            case CallLog.Calls.OUTGOING_TYPE -> ctx.getString(R.string.calllog_outgoing);
            case CallLog.Calls.MISSED_TYPE -> ctx.getString(R.string.calllog_missed);
            case CallLog.Calls.REJECTED_TYPE -> ctx.getString(R.string.calllog_rejected);
            default -> "";
        };
        var when = DateUtils.getRelativeTimeSpanString(
                e.date(), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        var dur = e.durationSec() > 0 ? "  ·  " + formatDuration(ctx, e.durationSec()) : "";
        return type + "  ·  " + when + dur;
    }

    /** 展开面板里每行的时间 用绝对时间格式 例如 Wed 4:36 PM */
    private static String formatHistoryLine(Context ctx, CallLogEntry e) {
        int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_ABBREV_WEEKDAY;
        String when = DateUtils.formatDateTime(ctx, e.date(), flags);
        if (e.durationSec() > 0) {
            return when + "  ·  " + formatDuration(ctx, e.durationSec());
        }
        return when;
    }

    private static String formatDuration(Context ctx, long sec) {
        long m = sec / 60, s = sec % 60;
        if (m == 0) return ctx.getString(R.string.calllog_duration_sec, (int) s);
        return ctx.getString(R.string.calllog_duration_min_sec, (int) m, (int) s);
    }
}
