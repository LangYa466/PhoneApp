package io.langya.module.ui.dialer;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.TextWatcher;
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

import com.google.i18n.phonenumbers.AsYouTypeFormatter;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

import io.langya.module.R;
import io.langya.module.callerid.CallerIdResolver;
import io.langya.module.databinding.DialButtonBinding;
import io.langya.module.databinding.FragmentDialerBinding;
import io.langya.module.ui.Md3Icons;

public class DialerFragment extends Fragment {

    private FragmentDialerBinding b;
    /** 原始输入 (digits / + / * / #); EditText 显示的是 formatForDisplay 后的字符串 */
    private final StringBuilder buffer = new StringBuilder();
    private String lastLookupNumber = "";
    private boolean updatingDisplay = false;
    private final AsYouTypeFormatter formatter =
            PhoneNumberUtil.getInstance().getAsYouTypeFormatter("CN");

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
        wireKey(b.key1, '1', "");
        wireKey(b.key2, '2', "ABC");
        wireKey(b.key3, '3', "DEF");
        wireKey(b.key4, '4', "GHI");
        wireKey(b.key5, '5', "JKL");
        wireKey(b.key6, '6', "MNO");
        wireKey(b.key7, '7', "PQRS");
        wireKey(b.key8, '8', "TUV");
        wireKey(b.key9, '9', "WXYZ");
        wireKey(b.keyStar, '*', "");
        wireKey(b.key0, '0', "+");
        wireKey(b.keyHash, '#', "");

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

        b.suggestCreate.setOnClickListener(v -> launchCreateContact());
        b.suggestAdd.setOnClickListener(v -> launchAddToContact());
        b.suggestMessage.setOnClickListener(v -> launchSendMessage());

        // 支持粘贴/手动编辑 EditText 自身有焦点但软键盘禁用 用本地拨号盘输入
        b.tvDisplay.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (updatingDisplay) return;
                var sanitized = sanitize(s.toString());
                buffer.setLength(0);
                buffer.append(sanitized);
                updateDisplay();
            }
        });
        b.tvDisplay.setShowSoftInputOnFocus(false);
        b.tvDisplay.requestFocus();

        updateDisplay();
    }

    /** 粘贴进来的号码里 - / 空格 / 括号都要剥掉 只留 digits + + * # */
    private static String sanitize(String raw) {
        var sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isDigit(c) || c == '+' || c == '*' || c == '#') sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }

    @Override
    public void onResume() {
        super.onResume();
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

    private void wireKey(DialButtonBinding key, char digit, String letters) {
        key.keyDigit.setText(String.valueOf(digit));
        key.keyLetters.setText(letters);
        key.getRoot().setOnClickListener(v -> {
            buffer.append(digit);
            updateDisplay();
        });
    }

    private void updateDisplay() {
        var raw = buffer.toString();
        var formatted = formatForDisplay(raw);
        if (!formatted.contentEquals(b.tvDisplay.getText())) {
            updatingDisplay = true;
            try {
                b.tvDisplay.setText(formatted);
                b.tvDisplay.setSelection(formatted.length());
            } finally {
                updatingDisplay = false;
            }
        }
        b.tvCallerId.setText("");
        b.suggestions.setVisibility(raw.isEmpty() ? View.GONE : View.VISIBLE);

        var contactName = CallerIdResolver.contactNameOf(requireContext(), raw);
        if (contactName != null) {
            b.tvCallerId.setText(contactName);
            lastLookupNumber = raw;
            return;
        }

        if (raw.length() >= 3 && !raw.equals(lastLookupNumber)) {
            lastLookupNumber = raw;
            b.tvCallerId.setText(R.string.incall_querying);
            CallerIdResolver.resolve(requireContext(), raw, (displayName, fromContacts) -> {
                if (!isAdded() || b == null || !raw.equals(buffer.toString())) return;
                requireActivity().runOnUiThread(() -> {
                    if (b == null) return;
                    if (displayName == null || displayName.isEmpty()) b.tvCallerId.setText("");
                    else b.tvCallerId.setText(displayName);
                });
            });
        }
    }

    /**
     * libphonenumber 边输入边格式化 国际号会显示 "+1 234 567 89" 国内号显示 "138 1234 5678"
     * 含 * # 等 DTMF 字符不走格式化 直接显示原文
     */
    private String formatForDisplay(String raw) {
        if (raw.isEmpty()) return "";
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isDigit(c) && c != '+') return raw;
        }
        formatter.clear();
        String result = "";
        for (int i = 0; i < raw.length(); i++) {
            result = formatter.inputDigit(raw.charAt(i));
        }
        return result;
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

    private void launchCreateContact() {
        var i = new Intent(Intent.ACTION_INSERT)
                .setType(ContactsContract.Contacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, buffer.toString());
        launchSafely(i);
    }

    private void launchAddToContact() {
        var i = new Intent(Intent.ACTION_INSERT_OR_EDIT)
                .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, buffer.toString());
        launchSafely(i);
    }

    private void launchSendMessage() {
        var i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + buffer.toString()));
        launchSafely(i);
    }

    private void launchSafely(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.toast_no_app_to_handle));
        }
    }

    private void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
