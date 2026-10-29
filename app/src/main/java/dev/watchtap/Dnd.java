package dev.watchtap;

import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.ZenPolicy;

import java.lang.reflect.Method;

/**
 * 勿扰模式读写。
 *
 * ==== 为什么这么复杂 ====
 * HyperOS / MIUI 对第三方写全局勿扰限制很严。实测（诊断功能输出）：
 *
 *   [path1] NotificationManager.setZenMode(反射)  调用成功，但 zen_mode 不变
 *   [path2] INotificationManager.setZenMode       调用失败（抛异常）
 *   [path3] setInterruptionFilter                 调用失败（抛异常）
 *
 * 也就是说系统**静默拦截**了反射写入。所以需要更多路径：
 *
 *   path A  AutomaticZenRule（Android 官方推荐，无需 root）
 *           ZenPolicy 里 allowAlarms(true) 实现「仅闹钟」
 *   path B  root + settings put global zen_mode（最可靠，需要 su）
 *   path C  原有三条，作为兜底
 *
 * 每次写入后用 Settings.Global.zen_mode 复核，以实际值为准。
 */
final class Dnd {

    static final int ZEN_OFF = 0;
    static final int ZEN_ALARMS = 3;

    static final int FILTER_ALL = 1;
    static final int FILTER_ALARMS = 5;

    private static final String AZR_ID = "dev.watchtap.sleepmode";
    private static final String AZR_NAME = "WatchTap 睡眠模式";
    private static final String REASON = "WatchTap Sync Watch";

    private Dnd() {}

    // ================================================================ 主入口

    static boolean set(Context ctx, boolean on) {
        return apply(ctx, on, false);
    }

    /**
     * @param probe true 时跳过「已是目标值」的短路，强制跑所有路径
     */
    static boolean apply(Context ctx, boolean on, boolean probe) {
        if (ctx == null) {
            Logs.e("DND.set: no context", null);
            return false;
        }

        int target = on ? ZEN_ALARMS : ZEN_OFF;
        int before = zen(ctx);

        if (!probe && before == target) {
            Logs.fire("DND.set", "already target zen=" + target);
            return true;
        }

        boolean ok = doApply(ctx, on, target);
        int after = zen(ctx);
        Logs.fire("DND.set", "target=" + target
                + " before=" + before + " after=" + after
                + (ok ? " [OK]" : " [FAILED]"));
        return ok;
    }

    private static boolean doApply(Context ctx, boolean on, int target) {
        String err;

        err = tryAzr(ctx, on);
        if (zen(ctx) == target) {
            Logs.fire("DND.win", "pathA AZR");
            return true;
        }
        Logs.fire("DND.try", "pathA AZR -> " + (err == null ? "无效果" : err));

        err = tryRoot(ctx, target);
        if (zen(ctx) == target) {
            Logs.fire("DND.win", "pathB root");
            return true;
        }
        Logs.fire("DND.try", "pathB root -> " + (err == null ? "无效果" : err));

        err = tryManagerSetZenMode(ctx, target);
        if (zen(ctx) == target) {
            Logs.fire("DND.win", "pathC1");
            return true;
        }
        Logs.fire("DND.try", "pathC1 -> " + (err == null ? "无效果" : err));

        err = tryServiceSetZenMode(ctx, target);
        if (zen(ctx) == target) {
            Logs.fire("DND.win", "pathC2");
            return true;
        }
        Logs.fire("DND.try", "pathC2 -> " + (err == null ? "无效果" : err));

        err = tryFilter(ctx, on);
        if (zen(ctx) == target) {
            Logs.fire("DND.win", "pathC3");
            return true;
        }
        Logs.fire("DND.try", "pathC3 -> " + (err == null ? "无效果" : err));

        return false;
    }

    // ================================================================ path A

    /**
     * AutomaticZenRule 路径。
     *
     * 这是 Android 5.0+ 官方给第三方应用的勿扰接入方式。
     * 规则挂在应用自己名下，但生效后会影响全局 zen_mode。
     *
     * 通过 ZenPolicy 精确表达「只允许闹钟」：
     *   disallowAllSounds()      先全禁
     *   allowAlarms(true)        再放行闹钟
     */
    /** 返回 null 表示成功，非 null 为失败原因。 */
    private static String tryAzr(Context ctx, boolean on) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return "NotificationManager null";

            ComponentName owner = new ComponentName(ctx, MainActivity.class);

            if (!on) {
                // 关闭：把自己创建的规则删掉
                nm.removeAutomaticZenRule(AZR_ID);
                Logs.fire("DND.pathA", "removeAutomaticZenRule");
                return null;
            }

            ZenPolicy policy = new ZenPolicy.Builder()
                    .disallowAllSounds()
                    .allowAlarms(true)
                    .showAllVisualEffects()
                    .build();

            AutomaticZenRule rule = new AutomaticZenRule.Builder(
                    AZR_NAME, Uri.parse("condition://watchtap/sleep"))
                    .setOwner(owner)
                    .setInterruptionFilter(FILTER_ALARMS)
                    .setZenPolicy(policy)
                    .setEnabled(true)
                    .setManualInvocationAllowed(true)
                    .build();

            // 已存在则 update，否则 add
            try {
                nm.updateAutomaticZenRule(AZR_ID, rule);
                Logs.fire("DND.pathA", "updateAutomaticZenRule");
                return null;
            } catch (Throwable ignored) {
            }

            String id = nm.addAutomaticZenRule(rule);
            Logs.fire("DND.pathA", "addAutomaticZenRule id=" + id);
            return id != null ? null : "addAutomaticZenRule returned null";
        } catch (Throwable t) {
            Logs.w("DND.pathA failed", t);
            return Logs.describe(t);
        }
    }

    // ================================================================ path B

    /**
     * root 兜底：su -c "settings put global zen_mode N"
     *
     * 最可靠，但需要 root。设备已装 KernelSU，可用。
     */
    private static String tryRoot(Context ctx, int target) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "settings put global zen_mode " + target});
            int rc = p.waitFor();
            Logs.fire("DND.pathB", "su settings put zen_mode=" + target + " rc=" + rc);
            return rc == 0 ? null : "exit code " + rc + " (无 root?)";
        } catch (Throwable t) {
            Logs.w("DND.pathB failed (no root?)", t);
            return Logs.describe(t);
        }
    }

    // ================================================================ path C

    private static String tryManagerSetZenMode(Context ctx, int mode) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return "NotificationManager null";
            Method m = NotificationManager.class.getDeclaredMethod(
                    "setZenMode", int.class, Uri.class, String.class);
            m.setAccessible(true);
            m.invoke(nm, mode, null, REASON);
            Logs.fire("DND.pathC1", "setZenMode(" + mode + ") invoked");
            return null;
        } catch (Throwable t) {
            Logs.w("DND.pathC1 failed", t);
            return Logs.describe(t);
        }
    }

    private static String tryServiceSetZenMode(Context ctx, int mode) {
        try {
            Object svc = ctx.getSystemService("notification");
            if (svc == null) return "notification service null";

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
            if (binder == null) {
                return "INotificationManager binder not found (字段名不匹配)";
            }

            Method setZen = binder.getClass().getMethod("setZenMode",
                    String.class, int.class, Uri.class, String.class);
            setZen.setAccessible(true);
            setZen.invoke(binder, ctx.getPackageName(), mode, null, REASON);
            Logs.fire("DND.pathC2", "INM.setZenMode(" + mode + ") invoked");
            return null;
        } catch (Throwable t) {
            Logs.w("DND.pathC2 failed", t);
            return Logs.describe(t);
        }
    }

    private static String tryFilter(Context ctx, boolean on) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return "NotificationManager null";
            int filter = on ? FILTER_ALARMS : FILTER_ALL;
            nm.setInterruptionFilter(filter);
            Logs.fire("DND.pathC3", "setInterruptionFilter(" + filter + ") invoked");
            return null;
        } catch (Throwable t) {
            Logs.w("DND.pathC3 failed", t);
            return Logs.describe(t);
        }
    }

    // ================================================================ 读取

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

    /** 是否有 root。 */
    static boolean hasRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ 诊断

    /**
     * 逐条测试所有写入路径，返回详细报告。
     *
     * 每条都记录：调用是否抛异常 + 异常内容 + 调用后 zen_mode 实际值。
     */
    static String probe(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx == null) return "no context";

        int start = zen(ctx);
        sb.append("初始 zen=").append(start).append('\n');
        sb.append("权限 access=").append(hasAccess(ctx)).append('\n');
        sb.append("root=").append(hasRoot()).append('\n');
        sb.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" target=").append(ctx.getApplicationInfo().targetSdkVersion).append('\n');

        int target = start == ZEN_OFF ? ZEN_ALARMS : ZEN_OFF;
        sb.append("目标 zen=").append(target).append("\n\n");

        boolean turningOn = target == ZEN_ALARMS;

        String e;

        sb.append("[A] AutomaticZenRule + ZenPolicy(allowAlarms)\n");
        e = tryAzr(ctx, turningOn);
        sb.append("    ").append(e == null ? "调用成功" : "失败: " + e)
                .append(" → zen=").append(zen(ctx));
        if (zen(ctx) == target) return sb.append("  ✓\n").toString();
        sb.append("  ✗\n\n");

        sb.append("[B] root: settings put global zen_mode\n");
        e = tryRoot(ctx, target);
        sb.append("    ").append(e == null ? "调用成功" : "失败: " + e)
                .append(" → zen=").append(zen(ctx));
        if (zen(ctx) == target) return sb.append("  ✓\n").toString();
        sb.append("  ✗\n\n");

        sb.append("[C1] NotificationManager.setZenMode(反射)\n");
        e = tryManagerSetZenMode(ctx, target);
        sb.append("    ").append(e == null ? "调用成功" : "失败: " + e)
                .append(" → zen=").append(zen(ctx));
        if (zen(ctx) == target) return sb.append("  ✓\n").toString();
        sb.append("  ✗\n\n");

        sb.append("[C2] INotificationManager.setZenMode\n");
        e = tryServiceSetZenMode(ctx, target);
        sb.append("    ").append(e == null ? "调用成功" : "失败: " + e)
                .append(" → zen=").append(zen(ctx));
        if (zen(ctx) == target) return sb.append("  ✓\n").toString();
        sb.append("  ✗\n\n");

        sb.append("[C3] setInterruptionFilter\n");
        e = tryFilter(ctx, turningOn);
        sb.append("    ").append(e == null ? "调用成功" : "失败: " + e)
                .append(" → zen=").append(zen(ctx));
        if (zen(ctx) == target) return sb.append("  ✓\n").toString();
        sb.append("  ✗\n\n");

        sb.append("✗ 全部失败\n");
        sb.append("详细异常见日志（UI 底部或 watchtap.log）");
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
