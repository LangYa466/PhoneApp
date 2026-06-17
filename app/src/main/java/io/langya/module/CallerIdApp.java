package io.langya.module;

import android.app.Application;

import com.mikepenz.iconics.Iconics;
import com.mikepenz.iconics.typeface.library.materialsymbols.OutlinedMaterialSymbols;

import io.langya.module.callerid.CallerIdCache;
import io.langya.module.diagnostics.CrashLogger;
import io.langya.module.ui.ThemeManager;
import timber.log.Timber;

public class CallerIdApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
        Iconics.init(this);
        Iconics.registerFont(OutlinedMaterialSymbols.INSTANCE);
        CrashLogger.install(this);
        ThemeManager.applyNightMode(this);
        CallerIdCache.init(this);
    }
}
