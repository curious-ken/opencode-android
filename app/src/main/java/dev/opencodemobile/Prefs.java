package dev.opencodemobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Small typed wrapper around SharedPreferences. */
public final class Prefs {

    private static final String NAME = "opencode_mobile";

    public static final String KEY_FONT_SIZE = "font_size";
    public static final String KEY_EXTRA_KEYS = "extra_keys";
    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String KEY_SHARED_STORAGE = "shared_storage";
    public static final String KEY_TAB = "tab";
    public static final String KEY_INSTALLED_VERSION = "installed_version";
    public static final String KEY_INSTALLED_STAMP = "installed_stamp";

    public static final int TAB_TERMINAL = 0;
    public static final int TAB_WEB = 1;

    private static SharedPreferences sPrefs;

    private Prefs() {
    }

    public static synchronized void init(Context context) {
        if (sPrefs == null) {
            sPrefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences p() {
        return sPrefs;
    }

    public static int getFontSize() {
        return Math.max(8, Math.min(32, p().getInt(KEY_FONT_SIZE, 14)));
    }

    public static void setFontSize(int size) {
        p().edit().putInt(KEY_FONT_SIZE, Math.max(8, Math.min(32, size))).apply();
    }

    public static boolean extraKeys() {
        return p().getBoolean(KEY_EXTRA_KEYS, true);
    }

    public static void setExtraKeys(boolean value) {
        p().edit().putBoolean(KEY_EXTRA_KEYS, value).apply();
    }

    public static boolean keepScreenOn() {
        return p().getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    public static void setKeepScreenOn(boolean value) {
        p().edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply();
    }

    public static boolean sharedStorage() {
        return p().getBoolean(KEY_SHARED_STORAGE, true);
    }

    public static void setSharedStorage(boolean value) {
        p().edit().putBoolean(KEY_SHARED_STORAGE, value).apply();
    }

    public static int getTab() {
        return p().getInt(KEY_TAB, TAB_TERMINAL);
    }

    public static void setTab(int tab) {
        p().edit().putInt(KEY_TAB, tab).apply();
    }

    public static String installedVersion() {
        return p().getString(KEY_INSTALLED_VERSION, "");
    }

    public static void setInstalled(String version) {
        p().edit()
            .putString(KEY_INSTALLED_VERSION, version == null ? "" : version)
            .putLong(KEY_INSTALLED_STAMP, System.currentTimeMillis())
            .apply();
    }

    public static long installedStamp() {
        return p().getLong(KEY_INSTALLED_STAMP, 0L);
    }
}
