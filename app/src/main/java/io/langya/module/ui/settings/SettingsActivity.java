package io.langya.module.ui.settings;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import io.langya.module.R;
import io.langya.module.ui.Md3Icons;
import io.langya.module.ui.ThemeManager;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyColor(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(Md3Icons.of(this, "mso-arrow_back"));
        toolbar.setNavigationOnClickListener(v -> finish());
    }
}
