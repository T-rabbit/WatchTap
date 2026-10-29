package dev.watchtap;

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;

/**
 * 勿扰模式读写。
 *
 * ==== 为什么不能只用 setInterruptionFilter ====
 * targetSdk >= 35（Android 15）时，系统会把 setInterruptionFilter 自动转换成
 * AutomaticZenRule，走的是「应用自己的勿扰规则」，而不是全局勿扰。
 *
 * 表现：调用不抛异常，但 Settings.Global.zen_mode 不变 —— 正是实测到的现象
 * （权限已授予，点击切换后 zen 仍是 0）。
 *
 * ==== 所以按优先级尝试三条路径 ====
 *   1. NotificationManager.setZenMode(int, Uri, String)  ← 反射，直接写全局
 *   2. INotificationManager.setZenMode(String, int, Uri, String)  ← 更底层
 *   3. setInterruptionFilter  ← 兜底，低版本可用
 *
 * 每次写入后用 Settings.Global.zen_mode 复核，以实际值为准。
 */
final class Dnd {

    /** zen_mode 取值 */
    static final int ZEN_OFF = 0;
    static final int ZEN_ALARMS = 3;

    /** NotificationManager.INTERRUPTION_FILTER_* */
    static final int FILTER_ALL = 1;
    static final int FILTER_ALARMS = 5;

    private static final String REASON = "WatchTap Sync Watch";

    private Dnd() {}

    /**
     * 设置勿扰：开 → 仅闹钟，关 → 全部允许。
     *
     * @return 以 zen_mode 实际变化为准
     */
    static boolean set(Context ctx, boolean on) {
        if (ctx == null) {
            Logs.e("DND.set: no context", null);
            return false;
        }

        int target = on ? ZEN_ALARMS : ZEN_OFF;
        int before = zen(ctx);
        if (before == target) {
            Logs.fire("DND.set", "already target zen=" + target);
            return true;
        }

        boolean invoked =
                tryManagerSetZenMode(ctx, target)
                        || tryServiceSetZenMode(ctx, target)
                        || tryFilter(ctx, on);

        int after = zen(ctx);
        boolean ok = (after == target);
        Logs.fire("DND.set", "target=" + target
                + " before=" + before
                + " after=" + after
                + " invoked=" + invoked
                + (ok ? " [OK]" : " [FAILED]"));
        return ok;
    }

    // ---------------------------------------------------------------- 路径 1

    /** NotificationManager.setZenMode(int mode, Uri conditionId, String reason) */
    private static boolean tryManagerSetZenMode(Context ctx, int mode) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;

            Method m = NotificationManager.class.getDeclaredMethod(
                    "setZenMode", int.class, Uri.class, String.class);
            m.setAccessible(true);
            m.invoke(nm, mode, null, REASON);
            Logs.fire("DND.path1", "setZenMode(" + mode + ") invoked");
            return true;
        } catch (Throwable t) {
            Logs.w("DND.path1 failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 路径 2

    /** INotificationManager.setZenMode(String callingPkg, int mode, Uri, String) */
    private static boolean tryServiceSetZenMode(Context ctx, int mode) {
        try {
            Object svc = ctx.getSystemService("notification");
            if (svc == null) return false;

            // 拿 INotificationManager 代理：字段名随版本变化，逐个试
            Object binder = null;
            for (String fname : new String[]{"sService", "sNotificationManager", "service"}) {
                try {
                    java.lang.reflect.Field f = svc.getClass().getDeclaredField(fname);
                    f.setAccessible(true);
                    Object v = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            ? f.get(null) : f.get(svc);
                    if (v != null) {
                        binder = v;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (binder == null) return false;

            Method setZen = binder.getClass().getMethod("setZenMode",
                    String.class, int.class, Uri.class, String.class);
            setZen.setAccessible(true);
            setZen.invoke(binder, ctx.getPackageName(), mode, null, REASON);
            Logs.fire("DND.path2", "INM.setZenMode(" + mode + ") invoked");
            return true;
        } catch (Throwable t) {
            Logs.w("DND.path2 failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 路径 3

    /** setInterruptionFilter，低版本可用；targetSdk>=35 会被转成 AZR。 */
    private static boolean tryFilter(Context ctx, boolean on) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            int filter = on ? FILTER_ALARMS : FILTER_ALL;
            nm.setInterruptionFilter(filter);
            Logs.fire("DND.path3", "setInterruptionFilter(" + filter + ") invoked");
            return true;
        } catch (SecurityException se) {
            Logs.e("DND.path3 SecurityException — 无勿扰权限", se);
            return false;
        } catch (Throwable t) {
            Logs.w("DND.path3 failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 读取

    static int zen(Context ctx) {
        if (ctx == null) return -1;
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "zen_mode", 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    static boolean hasAccess(Context ctx) {
        if (ctx == null) return false;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationPolicyAccessGranted();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 逐条测试三条写入路径，返回详细报告。
     *
     * 用于在设备上一次性定位「哪条路能真正改 zen_mode」。
     */
    static String probe(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx == null) return "no context";

        int start = zen(ctx);
        sb.append("初始 zen=").append(start).append('\n');
        sb.append("权限 access=").append(hasAccess(ctx)).append('\n');
        sb.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" target=").append(ctx.getApplicationInfo().targetSdkVersion).append('\n');

        // 目标：与当前相反
        int target = start == ZEN_OFF ? ZEN_ALARMS : ZEN_OFF;
        sb.append("目标 zen=").append(target).append("\n\n");

        // 路径 1
        sb.append("[path1] NotificationManager.setZenMode(反射)\n");
        boolean r1 = tryManagerSetZenMode(ctx, target);
        sb.append("  调用=").append(r1).append(" 结果 zen=").append(zen(ctx)).append('\n');
        if (zen(ctx) == target) {
            sb.append("  ✓ 成功\n");
            return sb.toString();
        }

        // 路径 2
        sb.append("[path2] INotificationManager.setZenMode\n");
        boolean r2 = tryServiceSetZenMode(ctx, target);
        sb.append("  调用=").append(r2).append(" 结果 zen=").append(zen(ctx)).append('\n');
        if (zen(ctx) == target) {
            sb.append("  ✓ 成功\n");
            return sb.toString();
        }

        // 路径 3
        sb.append("[path3] setInterruptionFilter\n");
        boolean r3 = tryFilter(ctx, target == ZEN_ALARMS);
        sb.append("  调用=").append(r3).append(" 结果 zen=").append(zen(ctx)).append('\n');
        if (zen(ctx) == target) {
            sb.append("  ✓ 成功\n");
            return sb.toString();
        }

        sb.append("\n✗ 三条路径均未改变 zen_mode\n");
        sb.append("可能原因：系统限制第三方应用写全局勿扰\n");
        return sb.toString();
    }

    /** 给 UI 显示的综合诊断。 */
    static String diagnose(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("sdk=").append(Build.VERSION.SDK_INT);
        sb.append(" target=").append(ctx == null ? "?" : ctx.getApplicationInfo().targetSdkVersion);
        sb.append(" access=").append(hasAccess(ctx));
        sb.append(" zen=").append(zen(ctx));
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm != null) sb.append(" filter=").append(nm.getCurrentInterruptionFilter());
        } catch (Throwable t) {
            sb.append(" filter=ERR");
        }
        return sb.toString();
    }
}
