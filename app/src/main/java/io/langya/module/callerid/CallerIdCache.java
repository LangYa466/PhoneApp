package io.langya.module.callerid;

import android.content.Context;
import android.content.SharedPreferences;
import timber.log.Timber;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CallerIdCache {

    private static final int MAX = 500;
    private static final String PREF = "num_cache";
    private static final String KEY_PREFIX = "num_";

    private static final Map<String, String> MEM = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > MAX;
        }
    };

    private static SharedPreferences sp;

    private CallerIdCache() {}

    public static synchronized void init(Context ctx) {
        if (sp == null) {
            sp = ctx.getApplicationContext().getSharedPreferences(PREF, 0);
        }
    }

    public static synchronized String get(String number) {
        var hit = MEM.get(number);
        if (hit != null) {
            Timber.d("MEM hit: %s -> %s", number, hit);
            return hit;
        }
        if (sp == null || !sp.contains(KEY_PREFIX + number)) return null;
        var v = sp.getString(KEY_PREFIX + number, null);
        if (v != null) MEM.put(number, v);
        Timber.d("DISK hit: %s -> %s", number, v);
        return v;
    }

    public static synchronized void put(String number, String result) {
        if (result == null) return;
        MEM.put(number, result);
        if (sp != null) {
            sp.edit().putString(KEY_PREFIX + number, result).apply();
            Timber.d("CACHE put: %s -> %s", number, result);
        }
    }

    public static synchronized void clearEmpty() {
        if (sp == null) return;
        var editor = sp.edit();
        int count = 0;
        for (var entry : sp.getAll().entrySet()) {
            if (entry.getValue() instanceof String s && s.isEmpty()) {
                editor.remove(entry.getKey());
                MEM.remove(entry.getKey().replace(KEY_PREFIX, ""));
                count++;
            }
        }
        editor.apply();
        Timber.d("clearEmpty: removed %d entries", count);
    }

    public static synchronized void remove(String number) {
        MEM.remove(number);
        if (sp != null) sp.edit().remove(KEY_PREFIX + number).apply();
        Timber.d("removed: %s", number);
    }

    public static synchronized void clearAll() {
        MEM.clear();
        if (sp != null) sp.edit().clear().apply();
        Timber.d("clearAll done");
    }
}
