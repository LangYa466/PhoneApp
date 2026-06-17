package io.langya.module.ui.dialer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import io.langya.module.R;
import io.langya.module.callerid.CallerIdResolver;
import io.langya.module.databinding.DialButtonBinding;
import io.langya.module.databinding.FragmentDialerBinding;
import io.langya.module.ui.Md3Icons;

public class DialerFragment extends Fragment {

    private FragmentDialerBinding b;
    private final StringBuilder buffer = new StringBuilder();
    private String lastLookupNumber = "";

    private final ActivityResultLauncher<String> callPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) placeCall();
                else toast(getString(R.string.toast_need_call_permission));
            });

    private final ActivityResultLauncher<String> contactsPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) updateDisplay();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentDialerBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        wireKey(b.key0, '0'); wireKey(b.key1, '1'); wireKey(b.key2, '2');
        wireKey(b.key3, '3'); wireKey(b.key4, '4'); wireKey(b.key5, '5');
        wireKey(b.key6, '6'); wireKey(b.key7, '7'); wireKey(b.key8, '8');
        wireKey(b.key9, '9'); wireKey(b.keyStar, '*'); wireKey(b.keyHash, '#');

        b.btnBackspace.setOnClickListener(v -> {
            if (buffer.length() == 0) return;
            buffer.deleteCharAt(buffer.length() - 1);
            updateDisplay();
        });
        b.btnBackspace.setOnLongClickListener(v -> {
            buffer.setLength(0);
            updateDisplay();
            return true;
        });
        b.btnBackspace.setIcon(Md3Icons.of(requireContext(), "mso-backspace"));
        b.btnCall.setIcon(Md3Icons.of(requireContext(), "mso-call"));
        b.btnCall.setOnClickListener(v -> attemptPlaceCall());
        updateDisplay();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 一次性把通讯录权限要了 —— 用户家人来电时才显示备注
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS);
        }
    }

    public void setNumber(String number) {
        buffer.setLength(0);
        if (number != null) buffer.append(number);
        if (b != null) updateDisplay();
    }

    private void wireKey(DialButtonBinding key, char digit) {
        var btn = key.getRoot();
        btn.setText(String.valueOf(digit));
        btn.setOnClickListener(v -> {
            buffer.append(digit);
            updateDisplay();
        });
    }

    private void updateDisplay() {
        var n = buffer.toString();
        b.tvDisplay.setText(n);
        b.tvCallerId.setText("");

        var contactName = CallerIdResolver.contactNameOf(requireContext(), n);
        if (contactName != null) {
            b.tvCallerId.setText(contactName);
            lastLookupNumber = n;
            return;
        }

        if (n.length() >= 3 && !n.equals(lastLookupNumber)) {
            lastLookupNumber = n;
            b.tvCallerId.setText(R.string.incall_querying);
            CallerIdResolver.resolve(requireContext(), n, (displayName, fromContacts) -> {
                if (!isAdded() || b == null || !n.equals(buffer.toString())) return;
                requireActivity().runOnUiThread(() -> {
                    if (b == null) return;
                    if (displayName == null || displayName.isEmpty()) b.tvCallerId.setText("");
                    else b.tvCallerId.setText(displayName);
                });
            });
        }
    }

    private void attemptPlaceCall() {
        if (buffer.length() == 0) { toast(getString(R.string.toast_no_input)); return; }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            callPermLauncher.launch(Manifest.permission.CALL_PHONE);
            return;
        }
        placeCall();
    }

    private void placeCall() {
        var tm = (TelecomManager) requireContext().getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return;
        var uri = Uri.fromParts("tel", buffer.toString(), null);
        try {
            tm.placeCall(uri, new Bundle());
        } catch (SecurityException e) {
            toast(getString(R.string.toast_need_default_dialer));
        }
    }

    private void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
