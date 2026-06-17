package io.langya.module.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

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

    /**
     * 在每个 Activity 的 onCreate super 之前调用
     *
     * 用 DynamicColorsOptions 的 contentBasedSource 让 Material 根据种子色生成
     * 完整 M3 tonal palette (primary / secondary / tertiary / surface / outline ...
     * 30+ slot 全部派生) 之前手写 6 个 attr 的 overlay 只覆盖了一小部分槽位
     * 没覆盖的全部走 Material baseline 紫色 这就是切了主题色但 UI 还在紫的根因
     */
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

        int seed = seedOf(color);
        var opts = new DynamicColorsOptions.Builder()
                .setContentBasedSource(seed)
                .build();
        DynamicColors.applyToActivityIfAvailable(activity, opts);
    }

    private static int seedOf(String name) {
        return switch (name == null ? "" : name) {
            case "green"  -> Color.parseColor("#2E7D32");
            case "purple" -> Color.parseColor("#6750A4");
            case "red"    -> Color.parseColor("#C5221F");
            case "orange" -> Color.parseColor("#E8710A");
            case "teal"   -> Color.parseColor("#00838F");
            default       -> Color.parseColor("#1A73E8");
        };
    }
}
