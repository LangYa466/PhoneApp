package io.langya.module.ui.history;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.langya.module.calllog.CallLogEntry;
import io.langya.module.databinding.ItemCallLogBinding;

public final class CallLogAdapter extends RecyclerView.Adapter<CallLogViewHolder> {

    private final List<CallLogEntry> items;
    private final Consumer<String> onCallBack;
    /** 由 CallerIdBatchQueue 异步填充 bind() 同步读取 */
    private final Map<String, String> liveNames = new HashMap<>();

    public CallLogAdapter(List<CallLogEntry> items, Consumer<String> onCallBack) {
        this.items = items;
        this.onCallBack = onCallBack;
    }

    /** 后台识别完成后调用：把结果塞进 session 缓存并刷新所有匹配的行 */
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
    public CallLogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var b = ItemCallLogBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new CallLogViewHolder(b, liveNames, onCallBack);
    }

    @Override
    public void onBindViewHolder(@NonNull CallLogViewHolder h, int position) {
        h.bind(items.get(position));
    }

    @Override
    public int getItemCount() { return items.size(); }
}
