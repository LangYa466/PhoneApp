package io.langya.module.ui.settings;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import io.langya.module.BuildConfig;
import io.langya.module.R;
import io.langya.module.ui.Md3Icons;
import io.langya.module.ui.ThemeManager;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyColor(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(Md3Icons.of(this, "mso-arrow_back"));
        toolbar.setNavigationOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.tvVersion))
                .setText("v" + BuildConfig.VERSION_NAME);

        var libs = new StringBuilder();
        for (var s : getString(R.string.build_dependencies).split("\n")) {
            if (!s.isEmpty()) libs.append("· ").append(s).append('\n');
        }
        ((TextView) findViewById(R.id.tvLibraries)).setText(libs.toString().trim());

        ((TextView) findViewById(R.id.tvApis))
                .setText("· " + getString(R.string.about_api_taobao));
    }
}
