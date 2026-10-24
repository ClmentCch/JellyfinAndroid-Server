package com.jellyfinserver.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent, non sensitive application settings. */
public final class Prefs {

    private static final String FILE = "jellyfinserver";
    private static final String KEY_INSTALLED_VERSION = "installed_version";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_BATTERY_PROMPTED = "battery_prompted";

    private Prefs() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isInstalled(Context context) {
        return AppConfig.SERVER_VERSION.equals(
                preferences(context).getString(KEY_INSTALLED_VERSION, null))
                && AppConfig.prootBinary(context).isFile()
                && AppConfig.installedMarker(context).isFile();
    }

    public static void setInstalled(Context context, boolean installed) {
        preferences(context).edit()
                .putString(KEY_INSTALLED_VERSION, installed ? AppConfig.SERVER_VERSION : null)
                .apply();
    }

    public static boolean isAutostartEnabled(Context context) {
        return preferences(context).getBoolean(KEY_AUTOSTART, true);
    }

    public static void setAutostartEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply();
    }

    public static boolean isBatteryPromptShown(Context context) {
        return preferences(context).getBoolean(KEY_BATTERY_PROMPTED, false);
    }

    public static void setBatteryPromptShown(Context context) {
        preferences(context).edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply();
    }
}
