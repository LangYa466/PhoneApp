package io.langya.module;

import android.app.Application;

import com.mikepenz.iconics.Iconics;
import com.mikepenz.iconics.typeface.library.materialsymbols.OutlinedMaterialSymbols;

import io.langya.module.data.CacheStore;
import io.langya.module.data.CrashLogger;
import io.langya.module.ui.ThemeManager;
import timber.log.Timber;

public class App extends Application {
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
        CacheStore.init(this);
    }
}
