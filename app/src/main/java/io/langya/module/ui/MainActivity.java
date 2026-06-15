package io.langya.module.ui;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.annotation.NonNull;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;

import io.langya.module.R;
import io.langya.module.data.ContactsRepository;
import io.langya.module.data.CrashLogger;
import io.langya.module.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding b;
    private DialerFragment dialerFragment;
    private CallLogFragment callLogFragment;

    private final ActivityResultLauncher<Intent> roleLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    r -> { updateDefaultDialerPrompt(); requestCorePermissions(); });

    private final ActivityResultLauncher<String[]> corePermsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    r -> updateContactsPrompt());

    private final ActivityResultLauncher<Intent> overlayLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    r -> updateOverlayPrompt());

    private final ActivityResultLauncher<String> contactsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            updateContactsPrompt();
                        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                            // 系统永久拒绝 —— 跳应用权限页让用户手动开
                            toast(getString(R.string.toast_open_settings_for_contacts));
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", getPackageName(), null)));
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyColor(this);
        super.onCreate(savedInstanceState);

        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);

        // Android 15 edge-to-edge：给底部导航栏让出系统栏 inset
        ViewCompat.setOnApplyWindowInsetsListener(b.bottomNav, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        if (savedInstanceState == null) {
            dialerFragment = new DialerFragment();
            callLogFragment = new CallLogFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragmentContainer, callLogFragment, "calllog")
                    .add(R.id.fragmentContainer, dialerFragment, "dialer")
                    .hide(callLogFragment)
                    .commit();
        } else {
            dialerFragment = (DialerFragment) getSupportFragmentManager().findFragmentByTag("dialer");
            callLogFragment = (CallLogFragment) getSupportFragmentManager().findFragmentByTag("calllog");
            if (dialerFragment == null) dialerFragment = new DialerFragment();
            if (callLogFragment == null) callLogFragment = new CallLogFragment();
        }

        b.bottomNav.setOnItemSelectedListener(item -> {
            var tx = getSupportFragmentManager().beginTransaction();
            int id = item.getItemId();
            if (id == R.id.nav_dialer) tx.show(dialerFragment).hide(callLogFragment);
            else if (id == R.id.nav_calllog) tx.show(callLogFragment).hide(dialerFragment);
            tx.commit();
            return true;
        });

        b.btnBecomeDefault.setOnClickListener(v -> requestDefaultDialerRole());
        b.btnGrantContacts.setOnClickListener(v -> contactsLauncher.launch(Manifest.permission.READ_CONTACTS));
        b.btnGrantOverlay.setOnClickListener(v -> overlayLauncher.launch(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", getPackageName(), null))));

        handleIntent(getIntent());
        requestCorePermissions();
        maybeShowLastCrash();
    }

    /** 若上次运行崩溃过，启动时立刻弹出日志对话框 —— 防止用户进不了设置。 */
    private void maybeShowLastCrash() {
        if (!CrashLogger.has(this)) return;
        var content = CrashLogger.read(this);
        if (content.isEmpty()) return;

        var tv = new TextView(this);
        tv.setText(content);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11f);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        var scroll = new ScrollView(this);
        scroll.addView(tv);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.crash_dialog_title)
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton(R.string.crash_dialog_close, null)
                .setNeutralButton(R.string.crash_dialog_copy, (d, w) -> {
                    var cm = getSystemService(ClipboardManager.class);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("crash", content));
                        toast(getString(R.string.toast_crash_copied));
                    }
                })
                .setNegativeButton(R.string.crash_dialog_clear, (d, w) -> {
                    CrashLogger.clear(this);
                    toast(getString(R.string.toast_crash_cleared));
                })
                .show();
    }

    private void requestCorePermissions() {
        var missing = new ArrayList<String>();
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) missing.add(Manifest.permission.READ_CONTACTS);
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) missing.add(Manifest.permission.READ_CALL_LOG);
        if (!hasPerm(Manifest.permission.POST_NOTIFICATIONS)) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!hasPerm(Manifest.permission.READ_PHONE_STATE)) missing.add(Manifest.permission.READ_PHONE_STATE);
        if (!hasPerm(Manifest.permission.CALL_PHONE)) missing.add(Manifest.permission.CALL_PHONE);
        if (!missing.isEmpty()) corePermsLauncher.launch(missing.toArray(new String[0]));
    }

    private boolean hasPerm(String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDefaultDialerPrompt();
        updateContactsPrompt();
        updateOverlayPrompt();
    }

    private void updateOverlayPrompt() {
        b.cardOverlayPrompt.setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
    }

    private void updateContactsPrompt() {
        boolean granted = hasPerm(Manifest.permission.READ_CONTACTS);
        b.cardContactsPrompt.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (granted) {
            // 后台预热联系人索引（无 toast）
            new Thread(() -> ContactsRepository.prefetchAndCount(getApplicationContext())).start();
        } else {
            ContactsRepository.invalidate();
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        var data = intent.getData();
        if (data != null && "tel".equalsIgnoreCase(data.getScheme())) {
            var number = data.getSchemeSpecificPart();
            b.bottomNav.setSelectedItemId(R.id.nav_dialer);
            if (dialerFragment != null) dialerFragment.setNumber(number);
        }
    }

    private void updateDefaultDialerPrompt() {
        b.cardDefaultPrompt.setVisibility(isDefaultDialer() ? View.GONE : View.VISIBLE);
    }

    private boolean isDefaultDialer() {
        var rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            return rm.isRoleHeld(RoleManager.ROLE_DIALER);
        }
        var tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        return tm != null && getPackageName().equals(tm.getDefaultDialerPackage());
    }

    private void requestDefaultDialerRole() {
        var rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                toast(getString(R.string.toast_already_default));
                return;
            }
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER));
            return;
        }
        var intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
        try {
            roleLauncher.launch(intent);
        } catch (Exception e) {
            toast(getString(R.string.toast_default_unsupported));
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
