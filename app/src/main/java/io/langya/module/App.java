package io.langya.module;

import android.app.Application;

import io.langya.module.data.CacheStore;
import io.langya.module.data.CrashLogger;
import io.langya.module.ui.ThemeManager;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.install(this);
        ThemeManager.applyNightMode(this);
        CacheStore.init(this);
    }
}
