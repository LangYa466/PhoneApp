package io.langya.module.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import io.langya.module.R;
import com.google.android.material.color.DynamicColors;

public final class ThemeManager {

    public static final String KEY_MODE = "theme_mode";
    public static final String KEY_COLOR = "theme_color";

    private ThemeManager() {}

    public static void applyNightMode(Context ctx) {
        var sp = PreferenceManager.getDefaultSharedPreferences(ctx);
        var mode = sp.getString(KEY_MODE, "system");
        int target = switch (mode == null ? "system" : mode) {
            case "light" -> AppCompatDelegate.MODE_NIGHT_NO;
            case "dark"  -> AppCompatDelegate.MODE_NIGHT_YES;
            default      -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        };
        AppCompatDelegate.setDefaultNightMode(target);
    }

    /** Call before setContentView in every Activity. */
    public static void applyColor(Activity activity) {
        var sp = PreferenceManager.getDefaultSharedPreferences(activity);
        var color = sp.getString(KEY_COLOR, "dynamic");

        if ("dynamic".equals(color)) {
            if (DynamicColors.isDynamicColorAvailable()) {
                DynamicColors.applyToActivityIfAvailable(activity);
                return;
            }
            color = "blue";
        }

        int overlay = switch (color) {
            case "green"  -> R.style.ThemeOverlay_CallerID_Green;
            case "purple" -> R.style.ThemeOverlay_CallerID_Purple;
            case "red"    -> R.style.ThemeOverlay_CallerID_Red;
            case "orange" -> R.style.ThemeOverlay_CallerID_Orange;
            case "teal"   -> R.style.ThemeOverlay_CallerID_Teal;
            default       -> R.style.ThemeOverlay_CallerID_Blue;
        };
        activity.getTheme().applyStyle(overlay, true);
    }
}
