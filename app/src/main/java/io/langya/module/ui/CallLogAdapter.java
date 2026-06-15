package io.langya.module.ui;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.langya.module.R;
import io.langya.module.data.CacheStore;
import io.langya.module.data.CallLogEntry;
import io.langya.module.data.ContactsRepository;
import io.langya.module.databinding.ItemCallLogBinding;

public class CallLogAdapter extends RecyclerView.Adapter<CallLogAdapter.VH> {

    private final List<CallLogEntry> items;
    private final Consumer<String> onCallBack;
    /** 由 CallerIdBatchResolver 异步填充，bind() 同步读取。 */
    private final Map<String, String> liveNames = new HashMap<>();

    public CallLogAdapter(List<CallLogEntry> items, Consumer<String> onCallBack) {
        this.items = items;
        this.onCallBack = onCallBack;
    }

    /** 后台识别完成后调用：把结果塞进 session 缓存并刷新所有匹配的行。 */
    public void setLiveName(String number, String name) {
        if (number == null) return;
        if (name == null || name.isEmpty()) return;
        liveNames.put(number, name);
        for (int i = 0; i < items.size(); i++) {
            if (number.equals(items.get(i).number())) notifyItemChanged(i);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var b = ItemCallLogBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.bind(items.get(position));
    }

    @Override
    public int getItemCount() { return items.size(); }

    class VH extends RecyclerView.ViewHolder {
        private final ItemCallLogBinding b;
        VH(ItemCallLogBinding b) { super(b.getRoot()); this.b = b; }

        void bind(CallLogEntry e) {
            var ctx = itemView.getContext();
            final String number = e.number();
            final boolean hasNumber = number != null && !number.isEmpty();

            b.tvNumber.setText(hasNumber ? number : "未知");
            b.tvSub.setText(formatSub(ctx, e));
            b.ivType.setImageResource(iconFor(e));
            b.btnCallBack.setOnClickListener(v -> onCallBack.accept(number));

            if (!hasNumber) return;

            // 1. 通讯录命中：标题用备注名，副标题展示原号码 + 类型时间
            var contactName = ContactsRepository.lookupName(ctx, number);
            if (contactName != null) {
                b.tvNumber.setText(contactName);
                b.tvSub.setText(number + "  ·  " + formatSub(ctx, e));
                return;
            }

            // 2. WebQuery 识别结果（session live 缓存 → 持久缓存）
            var identification = liveNames.get(number);
            if (identification == null || identification.isEmpty()) {
                CacheStore.init(ctx);
                var cached = CacheStore.get(number);
                if (cached != null && !cached.isEmpty()) identification = cached;
            }
            if (identification != null && !identification.isEmpty()) {
                b.tvSub.setText(identification + "  ·  " + formatSub(ctx, e));
            }
        }

        private int iconFor(CallLogEntry e) {
            if (e.isMissed())   return android.R.drawable.sym_call_missed;
            if (e.isIncoming()) return android.R.drawable.sym_call_incoming;
            if (e.isOutgoing()) return android.R.drawable.sym_call_outgoing;
            return android.R.drawable.sym_action_call;
        }

        private String formatSub(Context ctx, CallLogEntry e) {
            var type = switch (e.type()) {
                case android.provider.CallLog.Calls.INCOMING_TYPE -> ctx.getString(R.string.calllog_incoming);
                case android.provider.CallLog.Calls.OUTGOING_TYPE -> ctx.getString(R.string.calllog_outgoing);
                case android.provider.CallLog.Calls.MISSED_TYPE -> ctx.getString(R.string.calllog_missed);
                case android.provider.CallLog.Calls.REJECTED_TYPE -> ctx.getString(R.string.calllog_rejected);
                default -> "";
            };
            var when = DateUtils.getRelativeTimeSpanString(
                    e.date(), System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            var dur = e.durationSec() > 0 ? "  ·  " + formatDuration(e.durationSec()) : "";
            return type + "  ·  " + when + dur;
        }

        private String formatDuration(long sec) {
            long m = sec / 60, s = sec % 60;
            if (m == 0) return s + "秒";
            return m + "分" + s + "秒";
        }
    }
}
