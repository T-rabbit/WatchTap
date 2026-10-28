package dev.watchtap;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * STEP 2：拦截 ColorOS 私有 DND 写入，转成标准 Android API。
 *
 * 一旦 getRomVersionCode > 0，App 会走 ColorOS 分支，
 * 于是写免打扰时会 new android.app.OplusNotificationManager
 * —— 那个类在 HyperOS 上不存在，必然崩溃。
 *
 * 所以必须提前拦掉这两个入口：
 *   OPlusNotificationUtils.setZenMode(int, String)
 *   NotificationManagerNative.setZenMode(Context, int, Uri, String)
 */
final class DndInterceptor {

    private static final String TAG = "WatchTap";
    private static final String CLS_NOTIFUTILS =
            "com.heytap.health.base.oplus.OPlusNotificationUtils";
    private static final String CLS_COMPAT =
            "com.oplus.compat.app.NotificationManagerNative";

    static void install(XposedInterface api, ClassLoader cl) {
        hookOplusUtils(api, cl);
        hookCompat(api, cl);
    }

    private static void hookOplusUtils(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS_NOTIFUTILS, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"setZenMode".equals(m.getName())) continue;
                api.hook(m).intercept(chain -> {
                    int mode = 0;
                    Object a0 = chain.getArg(0);
                    if (a0 instanceof Integer) mode = (Integer) a0;
                    Log.i(TAG, "LINK: OPlusNotificationUtils.setZenMode(" + mode + ") intercepted");
                    setDnd(mode != 0);
                    return null;
                });
                Log.i(TAG, "LINK: hooked OPlusNotificationUtils.setZenMode");
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "LINK: hook OPlusUtils failed", t);
        }
    }

    private static void hookCompat(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS_COMPAT, false, cl);
            boolean got = false;
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if ("setZenMode".equals(n)) {
                    api.hook(m).intercept(chain -> {
                        int mode = 0;
                        Object a = chain.getArg(m.getParameterCount() >= 2 ? 1 : 0);
                        if (a instanceof Integer) mode = (Integer) a;
                        Log.i(TAG, "LINK: NotificationManagerNative.setZenMode(" + mode + ") intercepted");
                        setDnd(mode != 0);
                        return null;
                    });
                    got = true;
                } else if ("getZenMode".equals(n) && m.getParameterCount() == 0) {
                    api.hook(m).intercept(chain -> {
                        int v = rawZen();
                        Log.i(TAG, "LINK: getZenMode -> " + v);
                        return v;
                    });
                    got = true;
                }
            }
            if (got) Log.i(TAG, "LINK: hooked NotificationManagerNative");
        } catch (Throwable t) {
            Log.e(TAG, "LINK: hook compat failed", t);
        }
    }

    // ---------------------------------------------------------- 实际写入

    /** 用标准 Android API 写免打扰。 */
    private static void setDnd(boolean on) {
        try {
            android.content.Context ctx = ctx();
            if (ctx == null) {
                Log.w(TAG, "LINK: no context");
                return;
            }
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(
                            android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            int filter = on
                    ? android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                    : android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
            nm.setInterruptionFilter(filter);
            Log.i(TAG, "LINK: setInterruptionFilter(" + filter + ") ok");
        } catch (Throwable t) {
            Log.e(TAG, "LINK: setDnd failed", t);
        }
    }

    private static int rawZen() {
        try {
            android.content.Context ctx = ctx();
            if (ctx == null) return 0;
            return android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "zen_mode", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static android.content.Context ctx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
