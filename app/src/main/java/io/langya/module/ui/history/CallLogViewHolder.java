package io.langya.module.ui.history;

import android.content.Context;
import android.provider.CallLog;
import android.text.format.DateUtils;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Map;
import java.util.function.Consumer;

import io.langya.module.R;
import io.langya.module.callerid.CallerIdCache;
import io.langya.module.calllog.CallLogEntry;
import io.langya.module.contacts.ContactsRepository;
import io.langya.module.databinding.ItemCallLogBinding;
import io.langya.module.ui.Md3Icons;

final class CallLogViewHolder extends RecyclerView.ViewHolder {

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

    void bind(CallLogEntry e) {
        var ctx = itemView.getContext();
        final String number = e.number();
        final boolean hasNumber = number != null && !number.isEmpty();

        b.tvNumber.setText(hasNumber ? number : ctx.getString(R.string.calllog_unknown));
        b.tvSub.setText(formatSub(ctx, e));
        b.ivType.setImageDrawable(Md3Icons.of(ctx, iconKeyFor(e)));
        b.btnCallBack.setIcon(Md3Icons.of(ctx, "mso-call"));
        b.btnCallBack.setOnClickListener(v -> onCallBack.accept(number));

        if (!hasNumber) return;

        var contactName = ContactsRepository.lookupName(ctx, number);
        if (contactName != null) {
            b.tvNumber.setText(contactName);
            b.tvSub.setText(number + "  ·  " + formatSub(ctx, e));
            return;
        }

        var identification = liveNames.get(number);
        if (identification == null || identification.isEmpty()) {
            CallerIdCache.init(ctx);
            var cached = CallerIdCache.get(number);
            if (cached != null && !cached.isEmpty()) identification = cached;
        }
        if (identification != null && !identification.isEmpty()) {
            b.tvSub.setText(identification + "  ·  " + formatSub(ctx, e));
        }
    }

    private static String iconKeyFor(CallLogEntry e) {
        if (e.isMissed())   return "mso-call_missed";
        if (e.isIncoming()) return "mso-call_received";
        if (e.isOutgoing()) return "mso-call_made";
        return "mso-call";
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

    private static String formatDuration(Context ctx, long sec) {
        long m = sec / 60, s = sec % 60;
        if (m == 0) return ctx.getString(R.string.calllog_duration_sec, (int) s);
        return ctx.getString(R.string.calllog_duration_min_sec, (int) m, (int) s);
    }
}
