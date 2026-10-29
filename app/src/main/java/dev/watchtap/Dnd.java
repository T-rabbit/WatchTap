package dev.watchtap;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * 勿扰模式开关。
 *
 * ==== 目标 ====
 * 只要求「勿扰开启 / 关闭」，不实现「仅闹钟」这类静音策略。
 *
 * ==== 为什么用 INTERRUPTION_FILTER_NONE ====
 * 诊断实测：
 *   setInterruptionFilter(5) → IllegalArgumentException: Invalid filter: 5
 *   setInterruptionFilter(4) → 可用
 *
 * setInterruptionFilter 的合法值只有 1(ALL) / 2(PRIORITY) / 4(NONE)。
 * ALARMS(5) 只能用于 AutomaticZenRule 的 interruptionFilter，不能直接传。
 *
 * 所以：
 *   开启勿扰 → FILTER_NONE(4)  → Settings.Global.zen_mode = 2
 *   关闭勿扰 → FILTER_ALL (1)  → Settings.Global.zen_mode = 0
 *
 * 注意：NONE 会连闹钟一起静音。这是「完全勿扰」的语义。
 *
 * ==== targetSdk 必须是 34 ====
 * Android 15 起，targetSdk >= 35 时 setInterruptionFilter 会被系统转换成
 * AutomaticZenRule（应用自己的规则），导致 zen_mode 不变。build.gradle 与
 * build_apk.sh 里都固定 34。
 */
final class Dnd {

    static final int ZEN_OFF = 0;
    /** FILTER_NONE 对应的 zen_mode 值 */
    static final int ZEN_ON = 2;

    static final int FILTER_ALL = 1;
    /** NotificationManager.INTERRUPTION_FILTER_NONE */
    static final int FILTER_NONE = 4;

    private Dnd() {}

    // ================================================================ 主入口

    /**
     * 开关勿扰。
     *
     * @return 以 zen_mode 实际变化为准
     */
    static boolean set(Context ctx, boolean on) {
        if (ctx == null) {
            Logs.e("DND.set: no context", null);
            return false;
        }

        int before = zen(ctx);
        boolean already = on ? (before > 0) : (before == 0);
        if (already) {
            Logs.fire("DND.set", "already " + (on ? "on" : "off") + " zen=" + before);
            return true;
        }

        // 路径 1：标准 API
        String e1 = tryFilter(ctx, on);
        int after = verify(ctx);
        if (on ? after > 0 : after == 0) {
            Logs.fire("DND.set", "target=" + (on ? "ON" : "OFF")
                    + " before=" + before + " after=" + after + " via=filter [OK]");
            return true;
        }

        // 路径 2：root 兜底
        String e2 = tryRoot(ctx, on);
        after = verify(ctx);
        if (on ? after > 0 : after == 0) {
            Logs.fire("DND.set", "target=" + (on ? "ON" : "OFF")
                    + " before=" + before + " after=" + after + " via=root [OK]");
            return true;
        }

        // 路径 3：关闭时如果还残留 AZR 规则，清掉它们。
        // zen=3 有时是某条规则（如系统就寝模式）导致的，filter 清不掉。
        if (!on) {
            String e3 = clearRules(ctx);
            after = verify(ctx);
            if (after == 0) {
                Logs.fire("DND.set", "target=OFF before=" + before
                        + " after=0 via=clearRules [OK]");
                return true;
            }
            e2 = (e2 == null ? "" : e2 + " | ") + e3;
        }

        Logs.fire("DND.set", "target=" + (on ? "ON" : "OFF")
                + " before=" + before + " after=" + after
                + " filter=" + (e1 == null ? "ok-no-effect" : e1)
                + " root=" + (e2 == null ? "ok-no-effect" : e2)
                + " [FAILED]");
        return false;
    }

    // ================================================================ 路径

    /** 标准 API。合法值只有 1/2/4。 */
    private static String tryFilter(Context ctx, boolean on) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return "NotificationManager null";

            int filter = on ? FILTER_NONE : FILTER_ALL;
            nm.setInterruptionFilter(filter);
            Logs.fire("DND.filter", "setInterruptionFilter(" + filter + ")");
            return null;
        } catch (SecurityException se) {
            Logs.e("DND.filter: 无勿扰权限", se);
            return Logs.describe(se);
        } catch (Throwable t) {
            Logs.w("DND.filter failed", t);
            return Logs.describe(t);
        }
    }

    /**
     * root 兜底：settings put + cmd notification。
     *
     * settings put 只改数据库，NMS 内存态不会立刻跟；cmd 会让它重载。
     */
    private static String tryRoot(Context ctx, boolean on) {
        String[][] cmds = {
                {"settings", "put", "global", "zen_mode", String.valueOf(on ? ZEN_ON : ZEN_OFF)},
                {"cmd", "notification", "--zen", on ? "none" : "off"},
        };

        StringBuilder log = new StringBuilder();
        boolean anyOk = false;

        for (String[] c : cmds) {
            String cmd = join(c);
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                int rc = p.waitFor();
                log.append(cmd).append(" rc=").append(rc).append("; ");
                if (rc == 0) anyOk = true;
            } catch (Throwable t) {
                log.append(cmd).append(" ERR=").append(Logs.describe(t)).append("; ");
            }
        }

        Logs.fire("DND.root", log.toString());
        return anyOk ? null : log.toString();
    }

    /**
     * 清除所有非系统自动规则，用于关闭勿扰。
     *
     * HyperOS 上 zen_mode 可能是 3（仅闹钟），有时是系统就寝/驾驶规则造成的，
     * setInterruptionFilter(ALL) 不一定能清掉，需要显式删规则。
     */
    private static String clearRules(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return "NotificationManager null";

            java.util.Map<String, android.app.AutomaticZenRule> rules =
                    nm.getAutomaticZenRules();
            if (rules == null || rules.isEmpty()) return "no rules";

            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, android.app.AutomaticZenRule> e : rules.entrySet()) {
                try {
                    boolean ok = nm.removeAutomaticZenRule(e.getKey());
                    sb.append(e.getKey()).append('=').append(ok).append("; ");
                } catch (Throwable t) {
                    sb.append(e.getKey()).append("=ERR(")
                            .append(Logs.describe(t)).append("); ");
                }
            }
            Logs.fire("DND.clearRules", sb.toString());
            return sb.toString();
        } catch (Throwable t) {
            Logs.w("DND.clearRules failed", t);
            return Logs.describe(t);
        }
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (String s : a) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
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

    /**
     * 写入后复核。
     *
     * Settings.Global.getInt 走进程内缓存，别的进程刚改的值可能读不到，
     * 所以等一下再读两次确认。
     */
    private static int verify(Context ctx) {
        try {
            Thread.sleep(150L);
        } catch (InterruptedException ignored) {
        }
        int a = zen(ctx);
        try {
            Thread.sleep(100L);
        } catch (InterruptedException ignored) {
        }
        int b = zen(ctx);
        return b;
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

    // ================================================================ 诊断

    /** 诊断：测试两条路径，返回详细报告。 */
    static String probe(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx == null) return "no context";

        int before = zen(ctx);
        sb.append("初始 zen=").append(before).append('\n');
        sb.append("权限 access=").append(hasAccess(ctx)).append('\n');
        sb.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" target=").append(ctx.getApplicationInfo().targetSdkVersion).append('\n');

        boolean turnOn = before == 0;
        sb.append("目标=").append(turnOn ? "开启勿扰" : "关闭勿扰").append("\n\n");

        sb.append("[1] setInterruptionFilter(").append(turnOn ? FILTER_NONE : FILTER_ALL)
                .append(")\n");
        String e1 = tryFilter(ctx, turnOn);
        int a1 = verify(ctx);
        sb.append("    ").append(e1 == null ? "调用成功" : "失败: " + e1)
                .append(" → zen=").append(a1);
        if (turnOn ? a1 > 0 : a1 == 0) return sb.append("  ✓\n").toString();
        sb.append("  ✗\n\n");

        sb.append("[2] root: settings put + cmd notification\n");
        String e2 = tryRoot(ctx, turnOn);
        int a2 = verify(ctx);
        sb.append("    ").append(e2 == null ? "调用成功" : "失败: " + e2)
                .append(" → zen=").append(a2);
        if (turnOn ? a2 > 0 : a2 == 0) {
            sb.append("  ✓\n\n");
            // 成功后再验证能否切回来 —— 这是上一版反馈的问题
            sb.append("--- 反向验证 ---\n");
            sb.append("[3] 切回 ").append(turnOn ? "关闭" : "开启").append('\n');
            String e3 = tryFilter(ctx, !turnOn);
            int a3 = verify(ctx);
            sb.append("    ").append(e3 == null ? "调用成功" : "失败: " + e3)
                    .append(" → zen=").append(a3);
            if (turnOn ? a3 == 0 : a3 > 0) {
                sb.append("  ✓ 双向正常\n");
            } else {
                sb.append("  ✗ 切不回去\n");
                sb.append("    尝试清规则…\n");
                String e4 = clearRules(ctx);
                int a4 = verify(ctx);
                sb.append("    ").append(e4).append(" → zen=").append(a4);
                sb.append(a4 == 0 ? "  ✓\n" : "  ✗\n");
            }
            return sb.toString();
        }
        sb.append("  ✗\n\n");

        sb.append("✗ 两条路径均失败\n");
        sb.append("详细异常见日志");
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
