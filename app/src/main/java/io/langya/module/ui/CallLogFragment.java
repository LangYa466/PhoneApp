package io.langya.module.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import io.langya.module.R;
import io.langya.module.data.CallerIdBatchResolver;
import io.langya.module.data.CallLogRepository;
import io.langya.module.databinding.FragmentCallLogBinding;

public class CallLogFragment extends Fragment {

    private FragmentCallLogBinding b;

    private final androidx.activity.result.ActivityResultLauncher<String[]> permsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    r -> { if (b != null) reload(); });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentCallLogBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        b.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        b.btnGrantCallLog.setOnClickListener(v -> permsLauncher.launch(new String[]{
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS
        }));
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    /** BottomNav 切到本 tab 时 onResume 不会重新触发，必须监听 hidden 变化。 */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && b != null) reload();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }

    private void reload() {
        var granted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            b.recycler.setVisibility(View.GONE);
            b.emptyState.setVisibility(View.VISIBLE);
            return;
        }
        final var items = CallLogRepository.load(requireContext(), 200);
        if (items.isEmpty()) {
            b.recycler.setVisibility(View.GONE);
            b.emptyState.setVisibility(View.VISIBLE);
            b.btnGrantCallLog.setVisibility(View.GONE);
            return;
        }
        b.emptyState.setVisibility(View.GONE);
        b.recycler.setVisibility(View.VISIBLE);
        final var adapter = new CallLogAdapter(items, this::callBack);
        b.recycler.setAdapter(adapter);

        // 历史号码后台串行识别：一次只跑一个 WebView，识别完即喂给 adapter
        final var appCtx = requireContext().getApplicationContext();
        for (var e : items) {
            CallerIdBatchResolver.enqueue(appCtx, e.number(),
                    (number, name) -> adapter.setLiveName(number, name));
        }
    }

    private void callBack(String number) {
        if (number == null || number.isEmpty()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(getContext(), R.string.toast_need_call_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        var tm = (TelecomManager) requireContext().getSystemService(android.content.Context.TELECOM_SERVICE);
        if (tm == null) return;
        try {
            tm.placeCall(Uri.fromParts("tel", number, null), new Bundle());
        } catch (SecurityException e) {
            Toast.makeText(getContext(), R.string.toast_need_default_dialer, Toast.LENGTH_SHORT).show();
        }
    }
}
