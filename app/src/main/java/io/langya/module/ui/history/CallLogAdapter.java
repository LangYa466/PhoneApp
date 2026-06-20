package io.langya.module.ui.history;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import io.langya.module.R;
import io.langya.module.callerid.PhoneNumbers;
import io.langya.module.calllog.CallLogEntry;
import io.langya.module.databinding.ItemCallLogBinding;

public final class CallLogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int TYPE_HEADER = 0;
    static final int TYPE_ENTRY = 1;

    public enum Filter { ALL, MISSED, INCOMING, OUTGOING }

    private final List<CallLogEntry> source;
    private final Consumer<String> onCallBack;
    /** 由 CallerIdBatchQueue 异步填充 bind() 同步读取 */
    private final Map<String, String> liveNames = new HashMap<>();
    /** 实际展示用扁平 list (Header / EntryGroup 混合) */
    private final List<Object> rows = new ArrayList<>();
    /** 当前已展开的 group head id 集合 (折叠多通时点击展开) */
    private final Set<Long> expandedIds = new HashSet<>();

    private Filter filter = Filter.ALL;

    public CallLogAdapter(List<CallLogEntry> items, Consumer<String> onCallBack) {
        this.source = items;
        this.onCallBack = onCallBack;
        rebuildRows();
    }

    public void setFilter(Filter f) {
        if (filter == f) return;
        filter = f;
        expandedIds.clear();
        rebuildRows();
        notifyDataSetChanged();
    }

    /** 后台识别完成后调用：把结果塞进 session 缓存并刷新所有匹配的 group 行 */
    public void setLiveName(String number, String name) {
        if (number == null) return;
        if (name == null || name.isEmpty()) return;
        liveNames.put(number, name);
        for (int i = 0; i < rows.size(); i++) {
            Object r = rows.get(i);
            if (r instanceof EntryGroup g && number.equals(g.head().number())) notifyItemChanged(i);
        }
    }

    /**
     * 把原始 entries 折叠成显示行
     *  - 先按日期分组 (今天 / 昨天 / 更早) 插入 header
     *  - 同一 bucket 内连续相邻且号码归一化后相等的 entry 合并为一个 EntryGroup
     *    (空号码不参与合并 否则一堆 unknown 全挤一起)
     *  - 合并后 head 取最新一条 (列表按时间倒序 即第一个) 用于展示
     */
    private void rebuildRows() {
        rows.clear();
        String prevBucket = null;
        List<CallLogEntry> pending = null;
        String pendingKey = null;

        for (var e : source) {
            if (!matchesFilter(e)) continue;
            String bucket = bucketKeyFor(e.date());
            String key = mergeKey(e);

            boolean bucketChanged = !bucket.equals(prevBucket);
            boolean canMerge = !bucketChanged && pending != null
                    && pendingKey != null && pendingKey.equals(key);

            if (canMerge) {
                pending.add(e);
                continue;
            }

            flushPending(pending);
            pending = null;
            pendingKey = null;

            if (bucketChanged) {
                rows.add(new Header(bucket));
                prevBucket = bucket;
            }

            pending = new ArrayList<>();
            pending.add(e);
            pendingKey = key;
        }
        flushPending(pending);
    }

    private void flushPending(List<CallLogEntry> items) {
        if (items != null && !items.isEmpty()) rows.add(new EntryGroup(items));
    }

    /** 空号码归一化为 唯一 hash 防止误合并 否则一堆 unknown 全聚一起 */
    private static String mergeKey(CallLogEntry e) {
        String num = e.number();
        if (num == null || num.isEmpty()) return "__unknown@" + e.id();
        String n = PhoneNumbers.normalize(num);
        return n.isEmpty() ? num : n;
    }

    private boolean matchesFilter(CallLogEntry e) {
        return switch (filter) {
            case ALL -> true;
            case MISSED -> e.isMissed();
            case INCOMING -> e.isIncoming();
            case OUTGOING -> e.isOutgoing();
        };
    }

    /** "today" / "yesterday" / "earlier" 用作分组 key, 渲染时再 i18n */
    private static String bucketKeyFor(long ts) {
        var now = Calendar.getInstance();
        var c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        if (sameDay(now, c)) return "today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(now, c)) return "yesterday";
        return "earlier";
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof Header ? TYPE_HEADER : TYPE_ENTRY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            var v = inflater.inflate(R.layout.item_call_log_header, parent, false);
            return new HeaderVH(v);
        }
        var b = ItemCallLogBinding.inflate(inflater, parent, false);
        return new CallLogViewHolder(b, liveNames, onCallBack);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        Object row = rows.get(position);
        if (h instanceof HeaderVH hv && row instanceof Header hd) {
            hv.bind(hv.itemView.getContext(), hd.key);
        } else if (h instanceof CallLogViewHolder cv && row instanceof EntryGroup g) {
            long gid = g.head().id();
            boolean expanded = expandedIds.contains(gid);
            cv.bind(g, expanded, () -> {
                if (expandedIds.contains(gid)) expandedIds.remove(gid);
                else expandedIds.add(gid);
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
            });
        }
    }

    @Override
    public int getItemCount() { return rows.size(); }

    private record Header(String key) {}

    /** 折叠组 entries 按时间倒序 head=entries[0] 是最新一通 */
    static final class EntryGroup {
        private final List<CallLogEntry> entries;
        EntryGroup(List<CallLogEntry> entries) { this.entries = entries; }
        CallLogEntry head() { return entries.get(0); }
        int count() { return entries.size(); }
        List<CallLogEntry> entries() { return entries; }
    }

    static final class HeaderVH extends RecyclerView.ViewHolder {
        private final TextView tv;
        HeaderVH(View v) {
            super(v);
            tv = (TextView) v;
        }
        void bind(Context ctx, String key) {
            int res = switch (key) {
                case "today" -> R.string.calllog_header_today;
                case "yesterday" -> R.string.calllog_header_yesterday;
                default -> R.string.calllog_header_earlier;
            };
            tv.setText(ctx.getString(res));
        }
    }
}
