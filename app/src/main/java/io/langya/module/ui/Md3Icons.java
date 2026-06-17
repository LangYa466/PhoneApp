package io.langya.module.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

import com.mikepenz.iconics.IconicsDrawable;

/**
 * 统一构造 Material Symbols Outlined icon drawable
 * 默认 24dp 自动跟随主题 ?attr/colorOnSurface 上色
 * Toolbar / BottomNav 等本身会做 tinting 的容器会再覆盖一层 tint
 */
public final class Md3Icons {

    private Md3Icons() {}

    public static IconicsDrawable of(Context ctx, String key) {
        return of(ctx, key, 24);
    }

    public static IconicsDrawable of(Context ctx, String key, int sizeDp) {
        IconicsDrawable d = new IconicsDrawable(ctx, key);
        int px = Math.round(sizeDp * ctx.getResources().getDisplayMetrics().density);
        d.setSizeXPx(px);
        d.setSizeYPx(px);
        d.setColorList(ColorStateList.valueOf(resolveOnSurface(ctx)));
        return d;
    }

    private static int resolveOnSurface(Context ctx) {
        var tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true)) {
            if (tv.resourceId != 0) return ContextCompat.getColor(ctx, tv.resourceId);
            return tv.data;
        }
        return Color.WHITE;
    }
}
