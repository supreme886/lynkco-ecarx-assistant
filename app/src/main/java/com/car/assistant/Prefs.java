package com.car.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/** 用户配置持久化：目标驾驶模式 + 两个开机开关 */
public final class Prefs {
    private static final String FILE = "settings";
    private static final String KEY_MODE_ENABLED = "mode_enabled";
    private static final String KEY_MODE_VALUE = "mode_value";
    private static final String KEY_ISS_ENABLED = "iss_enabled";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isModeEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_MODE_ENABLED, false);
    }

    public static void setModeEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_MODE_ENABLED, on).apply();
    }

    public static int getModeValue(Context ctx) {
        return sp(ctx).getInt(KEY_MODE_VALUE, CarFunctionController.MODE_COMFORT);
    }

    public static void setModeValue(Context ctx, int mode) {
        sp(ctx).edit().putInt(KEY_MODE_VALUE, mode).apply();
    }

    public static boolean isIssEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_ISS_ENABLED, false);
    }

    public static void setIssEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_ISS_ENABLED, on).apply();
    }
}
