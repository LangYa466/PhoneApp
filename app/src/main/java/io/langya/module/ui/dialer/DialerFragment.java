package io.langya.module.ui.dialer;

import static com.google.android.material.R.attr.colorOnPrimaryContainer;
import static com.google.android.material.R.attr.colorOnSecondaryContainer;
import static com.google.android.material.R.attr.colorOnTertiaryContainer;
import static com.google.android.material.R.attr.colorPrimaryContainer;
import static com.google.android.material.R.attr.colorSecondaryContainer;
import static com.google.android.material.R.attr.colorTertiaryContainer;

import java.util.HashSet;
import java.util.List;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.i18n.phonenumbers.AsYouTypeFormatter;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

import io.langya.module.R;
import io.langya.module.callerid.CallerIdResolver;
import io.langya.module.callerid.PhoneNumbers;
import io.langya.module.contacts.DialerMatchRepository;
import io.langya.module.databinding.DialButtonBinding;
import io.langya.module.databinding.FragmentDialerBinding;
import io.langya.module.ui.ContactAvatars;
import io.langya.module.ui.Md3Icons;

public class DialerFragment extends Fragment {

    private static final int AVATAR_DP = 40;
    private static final int CALLER_ID_LOOKUP_MIN_DIGITS = 3;

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
        b.btnOverflow.setIcon(Md3Icons.of(requireContext(), "mso-more_vert"));
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

        boolean hasInput = !raw.isEmpty();
        b.suggestions.setVisibility(hasInput ? View.VISIBLE : View.GONE);
        refreshMatches(raw);
        refreshCallerId(raw);
    }

    /** caller id 在新版布局里给空就好 顶部命中区已经显示联系人 这里只在没匹配时显示识别中 */
    private void refreshCallerId(String raw) {
        if (raw.length() < CALLER_ID_LOOKUP_MIN_DIGITS) {
            lastLookupNumber = "";
            return;
        }
        if (raw.equals(lastLookupNumber)) return;
        lastLookupNumber = raw;
        CallerIdResolver.resolve(requireContext(), raw, (displayName, fromContacts) -> {
            // 不渲染 顶部已经覆盖了通讯录命中 在线识别静默缓存
        });
    }

    private void refreshMatches(String digits) {
        if (b == null) return;
        b.matchedContacts.removeAllViews();
        b.matchedOthers.removeAllViews();
        if (digits.isEmpty()) {
            b.tvMatchHeaderContacts.setVisibility(View.GONE);
            b.matchedContacts.setVisibility(View.GONE);
            b.tvMatchHeaderOthers.setVisibility(View.GONE);
            b.matchedOthers.setVisibility(View.GONE);
            return;
        }
        var ctx = requireContext();
        var contacts = DialerMatchRepository.findContacts(ctx, digits);
        var seenNormalized = new HashSet<String>();
        for (var m : contacts) seenNormalized.add(PhoneNumbers.normalize(m.number()));
        var others = DialerMatchRepository.findOthers(ctx, digits, seenNormalized);

        renderContactMatches(ctx, digits, contacts);
        renderOtherMatches(ctx, digits, others);
    }

    private void renderContactMatches(Context ctx, String digits, List<DialerMatchRepository.ContactMatch> list) {
        if (list.isEmpty()) {
            b.tvMatchHeaderContacts.setVisibility(View.GONE);
            b.matchedContacts.setVisibility(View.GONE);
            return;
        }
        b.tvMatchHeaderContacts.setVisibility(View.VISIBLE);
        b.matchedContacts.setVisibility(View.VISIBLE);
        var inflater = LayoutInflater.from(ctx);
        for (var m : list) {
            var row = inflater.inflate(R.layout.item_dialer_match, b.matchedContacts, false);
            bindMatchRow(ctx, row, m.name() != null ? m.name() : m.number(),
                    buildContactSub(m.label(), m.number(), digits),
                    m.number(), m.photoUri());
            b.matchedContacts.addView(row);
        }
    }

    private void renderOtherMatches(Context ctx, String digits, List<DialerMatchRepository.OtherMatch> list) {
        if (list.isEmpty()) {
            b.tvMatchHeaderOthers.setVisibility(View.GONE);
            b.matchedOthers.setVisibility(View.GONE);
            return;
        }
        b.tvMatchHeaderOthers.setVisibility(View.VISIBLE);
        b.matchedOthers.setVisibility(View.VISIBLE);
        var inflater = LayoutInflater.from(ctx);
        for (var m : list) {
            var row = inflater.inflate(R.layout.item_dialer_match, b.matchedOthers, false);
            var when = DateUtils.getRelativeTimeSpanString(m.lastCallDate(),
                    System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString();
            bindMatchRow(ctx, row, boldMatched(m.number(), digits), when, m.number(), null);
            b.matchedOthers.addView(row);
        }
    }

    private void bindMatchRow(Context ctx, View row, CharSequence name, CharSequence sub,
                              String number, String photoUri) {
        var tvName = (TextView) row.findViewById(R.id.tvName);
        var tvSub = (TextView) row.findViewById(R.id.tvSub);
        var avatarBg = row.findViewById(R.id.avatarBg);
        var tvAvatar = (TextView) row.findViewById(R.id.tvAvatar);
        var ivAvatar = (ImageView) row.findViewById(R.id.ivAvatar);
        var btnCall = (MaterialButton) row.findViewById(R.id.btnCallMatch);

        tvName.setText(name);
        tvSub.setText(sub);
        tvAvatar.setText(initialOf(name.toString()));
        applyAvatarPalette(ctx, avatarBg.getBackground(), tvAvatar,
                photoUri != null ? photoUri : number);

        ContactAvatars.clear(ivAvatar);
        ivAvatar.setVisibility(View.GONE);
        avatarBg.setVisibility(View.VISIBLE);
        tvAvatar.setVisibility(View.VISIBLE);
        if (photoUri != null) {
            ContactAvatars.load(ctx, photoUri, ivAvatar, success -> {
                if (!success) return;
                ivAvatar.setVisibility(View.VISIBLE);
                avatarBg.setVisibility(View.GONE);
                tvAvatar.setVisibility(View.GONE);
            });
        }

        btnCall.setIcon(Md3Icons.of(ctx, "mso-call", 20));
        btnCall.setOnClickListener(v -> directCall(number));
        row.setOnClickListener(v -> directCall(number));
    }

    /** 联系人副标题 把输入的 digits 在号码里加粗 例如 "Mobile 11451**4**" */
    private CharSequence buildContactSub(String label, String number, String digits) {
        var prefix = (label == null ? "" : label + " ");
        var sb = new SpannableStringBuilder(prefix);
        sb.append(boldMatched(number, digits));
        return sb;
    }

    /** 输入数字在号码里命中的位置加粗 数字间允许 -/空格/+/( 等格式字符穿插 */
    private CharSequence boldMatched(String number, String digits) {
        var sb = new SpannableStringBuilder(number);
        if (digits == null || digits.isEmpty()) return sb;
        int di = 0, start = -1;
        for (int i = 0; i < number.length() && di < digits.length(); i++) {
            char c = number.charAt(i);
            if (c == digits.charAt(di)) {
                if (start < 0) start = i;
                di++;
                if (di == digits.length()) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, i + 1, 0);
                    break;
                }
            } else if (Character.isDigit(c)) {
                // 数字不匹配 重头开始扫
                di = 0; start = -1;
                if (c == digits.charAt(0)) { di = 1; start = i; }
            }
        }
        return sb;
    }

    private void directCall(String number) {
        if (number == null || number.isEmpty()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.toast_need_call_permission));
            return;
        }
        var tm = (TelecomManager) requireContext().getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return;
        try {
            tm.placeCall(Uri.fromParts("tel", number, null), new Bundle());
        } catch (SecurityException e) {
            toast(getString(R.string.toast_need_default_dialer));
        }
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

    /**
     * 按 hash 在 primary / secondary / tertiary container 三色里轮转
     * 同号码每次出现颜色一致 不同号码颜色分散
     * 同一 drawable XML inflate 出的实例共享 ConstantState 必须 mutate() 否则污染其它行
     */
    private static void applyAvatarPalette(Context ctx, Drawable bg, TextView label, String seed) {
        int[][] palette = {
                {colorPrimaryContainer, colorOnPrimaryContainer},
                {colorSecondaryContainer, colorOnSecondaryContainer},
                {colorTertiaryContainer, colorOnTertiaryContainer},
        };
        int idx = Math.floorMod(seed == null ? 0 : seed.hashCode(), palette.length);
        int bgColor = resolveAttr(ctx, palette[idx][0]);
        int fgColor = resolveAttr(ctx, palette[idx][1]);
        if (bg instanceof GradientDrawable gd) {
            gd.mutate();
            gd.setColor(bgColor);
        }
        label.setTextColor(ColorStateList.valueOf(fgColor));
    }

    private static int resolveAttr(Context ctx, int attr) {
        var tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return ContextCompat.getColor(ctx, tv.resourceId);
            return tv.data;
        }
        return Color.GRAY;
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
