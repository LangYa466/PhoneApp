package io.langya.module.ui.settings;

import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.net.Uri;

import io.langya.module.BuildConfig;
import io.langya.module.R;
import io.langya.module.callerid.CallerIdCache;
import io.langya.module.diagnostics.CrashLogger;
import io.langya.module.ui.Md3Icons;
import io.langya.module.ui.ThemeManager;
import io.langya.module.update.UpdateChecker;

public class SettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        bindList(ThemeManager.KEY_MODE, true);
        bindList(ThemeManager.KEY_COLOR, false);
        bindDefaultDialer();
        bindAction("clear_empty", () -> {
            CallerIdCache.clearEmpty();
            Toast.makeText(getContext(), R.string.toast_empty_cleared, Toast.LENGTH_SHORT).show();
        });
        bindAction("clear_cache", () -> {
            CallerIdCache.clearAll();
            Toast.makeText(getContext(), R.string.toast_cache_cleared, Toast.LENGTH_SHORT).show();
        });
        bindAction("crash_log", this::showCrashLog);
        bindCheckUpdate();
        bindAbout();
        applyMd3Icons();
    }

    private void bindCheckUpdate() {
        var pref = findPreference("check_update");
        if (pref == null) return;
        pref.setSummary(getString(R.string.pref_check_update_summary, BuildConfig.VERSION_NAME));
        pref.setOnPreferenceClickListener(p -> {
            var ctx = requireContext();
            Toast.makeText(ctx, R.string.update_checking, Toast.LENGTH_SHORT).show();
            UpdateChecker.checkNow(ctx, result -> {
                if (!isAdded()) return;
                if (result == null) {
                    Toast.makeText(ctx, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                } else if (result.hasUpdate) {
                    new MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.update_dialog_title)
                            .setMessage(getString(R.string.update_dialog_msg,
                                    BuildConfig.VERSION_NAME, result.latestVersion))
                            .setPositiveButton(R.string.update_dialog_open,
                                    (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW,
                                            Uri.parse(result.htmlUrl))))
                            .setNegativeButton(R.string.update_dialog_later, null)
                            .show();
                } else {
                    Toast.makeText(ctx,
                            getString(R.string.update_already_latest, BuildConfig.VERSION_NAME),
                            Toast.LENGTH_SHORT).show();
                }
            });
            return true;
        });
    }

    private void bindAbout() {
        var pref = findPreference("about");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(requireContext(), AboutActivity.class));
            return true;
        });
    }

    private void applyMd3Icons() {
        var ctx = requireContext();
        setPrefIcon(ctx, ThemeManager.KEY_MODE, "mso-contrast");
        setPrefIcon(ctx, ThemeManager.KEY_COLOR, "mso-palette");
        setPrefIcon(ctx, "default_dialer", "mso-call");
        setPrefIcon(ctx, "clear_empty", "mso-cleaning_services");
        setPrefIcon(ctx, "clear_cache", "mso-delete");
        setPrefIcon(ctx, "crash_log", "mso-bug_report");
        setPrefIcon(ctx, "check_update", "mso-system_update");
        setPrefIcon(ctx, "about", "mso-info");
    }

    private void setPrefIcon(Context ctx, String key, String iconKey) {
        var pref = findPreference(key);
        if (pref != null) pref.setIcon(Md3Icons.of(ctx, iconKey));
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDefaultDialerSummary();
        refreshCrashLogSummary();
    }

    private void refreshCrashLogSummary() {
        var pref = findPreference("crash_log");
        if (pref == null) return;
        pref.setSummary(CrashLogger.has(requireContext())
                ? R.string.pref_crash_log_present
                : R.string.pref_crash_log_empty);
    }

    private void showCrashLog() {
        var ctx = requireContext();
        var content = CrashLogger.read(ctx);
        if (content.isEmpty()) {
            Toast.makeText(ctx, R.string.pref_crash_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        var tv = new TextView(ctx);
        tv.setText(content);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12f);
        int p = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        var scroll = new ScrollView(ctx);
        scroll.addView(tv);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.crash_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.crash_dialog_close, null)
                .setNeutralButton(R.string.crash_dialog_copy, (d, w) -> {
                    var cm = ctx.getSystemService(ClipboardManager.class);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("crash", content));
                        Toast.makeText(ctx, R.string.toast_crash_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.crash_dialog_clear, (d, w) -> {
                    CrashLogger.clear(ctx);
                    Toast.makeText(ctx, R.string.toast_crash_cleared, Toast.LENGTH_SHORT).show();
                    refreshCrashLogSummary();
                })
                .show();
    }

    private void bindList(String key, boolean alsoApplyNightMode) {
        final ListPreference pref = findPreference(key);
        if (pref == null) return;
        pref.setOnPreferenceChangeListener((p, v) -> {
            pref.setValue((String) v);
            if (alsoApplyNightMode) ThemeManager.applyNightMode(requireContext());
            requireActivity().recreate();
            return false;
        });
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        if (preference instanceof ListPreference lp) {
            var entries = lp.getEntries();
            var values = lp.getEntryValues();
            int checked = lp.findIndexOfValue(lp.getValue());
            final int[] picked = {checked};
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(lp.getTitle())
                    .setSingleChoiceItems(entries, checked, (d, which) -> picked[0] = which)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (picked[0] < 0 || picked[0] >= values.length) return;
                        var newVal = values[picked[0]].toString();
                        if (lp.callChangeListener(newVal)) lp.setValue(newVal);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        super.onDisplayPreferenceDialog(preference);
    }

    private void bindAction(String key, Runnable action) {
        var pref = findPreference(key);
        if (pref == null) return;
        pref.setOnPreferenceClickListener(p -> { action.run(); return true; });
    }

    private void bindDefaultDialer() {
        var pref = findPreference("default_dialer");
        if (pref == null) return;
        pref.setOnPreferenceClickListener(p -> { launchDefaultDialerRequest(); return true; });
    }

    private void refreshDefaultDialerSummary() {
        Preference pref = findPreference("default_dialer");
        if (pref == null) return;
        pref.setSummary(isDefaultDialer()
                ? R.string.pref_default_dialer_summary_yes
                : R.string.pref_default_dialer_summary_no);
    }

    private boolean isDefaultDialer() {
        var ctx = requireContext();
        var rm = (RoleManager) ctx.getSystemService(Context.ROLE_SERVICE);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            return rm.isRoleHeld(RoleManager.ROLE_DIALER);
        }
        var tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        return tm != null && ctx.getPackageName().equals(tm.getDefaultDialerPackage());
    }

    private void launchDefaultDialerRequest() {
        var ctx = requireContext();
        var rm = (RoleManager) ctx.getSystemService(Context.ROLE_SERVICE);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)
                && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER));
            return;
        }
        var intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                        ctx.getPackageName());
        startActivity(intent);
    }
}
