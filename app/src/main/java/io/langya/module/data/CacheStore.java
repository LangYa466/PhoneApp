package io.langya.module.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CacheStore {

    private static final int MAX = 500;
    private static final String PREF = "num_cache";
    private static final String TAG = "CallerID_Cache";
    private static final String KEY_PREFIX = "num_";

    private static final Map<String, String> MEM = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > MAX;
        }
    };

    private static SharedPreferences sp;

    private CacheStore() {}

    public static synchronized void init(Context ctx) {
        if (sp == null) {
            sp = ctx.getApplicationContext().getSharedPreferences(PREF, 0);
        }
    }

    public static synchronized String get(String number) {
        var hit = MEM.get(number);
        if (hit != null) {
            Log.d(TAG, "MEM hit: " + number + " -> " + hit);
            return hit;
        }
        if (sp == null || !sp.contains(KEY_PREFIX + number)) return null;
        var v = sp.getString(KEY_PREFIX + number, null);
        if (v != null) MEM.put(number, v);
        Log.d(TAG, "DISK hit: " + number + " -> " + v);
        return v;
    }

    public static synchronized void put(String number, String result) {
        if (result == null) return;
        MEM.put(number, result);
        if (sp != null) {
            sp.edit().putString(KEY_PREFIX + number, result).apply();
            Log.d(TAG, "CACHE put: " + number + " -> " + result);
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
        Log.d(TAG, "clearEmpty: removed " + count + " entries");
    }

    public static synchronized void remove(String number) {
        MEM.remove(number);
        if (sp != null) sp.edit().remove(KEY_PREFIX + number).apply();
        Log.d(TAG, "removed: " + number);
    }

    public static synchronized void clearAll() {
        MEM.clear();
        if (sp != null) sp.edit().clear().apply();
        Log.d(TAG, "clearAll done");
    }
}
