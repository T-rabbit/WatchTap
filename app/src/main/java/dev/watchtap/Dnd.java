package dev.watchtap;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * 勿扰模式开关 + 静音策略。
 *
 * ==== 两个独立控制 ====
 *
 * 1) 勿扰开关（on/off）
 *    开启 → setInterruptionFilter(NONE 或 PRIORITY)
 *    关闭 → setInterruptionFilter(ALL)
 *
 * 2) 同步静音（mute）
 *    开启 → FILTER_NONE(4)     完全静音，通知不响不亮
 *    关闭 → FILTER_PRIORITY(2) 仍算勿扰，但允许优先通知（来电等）
 *
 * 也就是说「勿扰开着但不同步静音」= PRIORITY 模式，
 * 系统状态栏仍显示勿扰图标，但通知不会全被拦。
 *
 * ==== 实测注意 ====
 * 这台 HyperOS 上 FILTER_NONE(4) 得到 zen_mode=3（仅闹钟），
 * 而不是 AOSP 的 2（完全静音）。所以显示时按实际值判断。
 *
 * FILTER_ALARMS(5) 不能传给 setInterruptionFilter，会抛
 * IllegalArgumentException: Invalid filter: 5。它只能用于 AZR。
 */
final class Dnd {

    static final int ZEN_OFF = 0;

    static final int FILTER_ALL = 1;
    static final int FILTER_PRIORITY = 2;
    static final int FILTER_NONE = 4;

    private Dnd() {}

    // ================================================================ 主入口

    /**
     * 设置勿扰。
     *
     * @param on   是否开启勿扰
     * @param mute 是否同步静音（仅 on=true 时有意义）
     * @return 是否达到预期状态
     */
    static boolean set(Context ctx, boolean on, boolean mute) {
        if (ctx == null) {
            Logs.e("勿扰设置失败：拿不到上下文", null);
            return false;
        }

        int before = zen(ctx);
        int wanted = on ? (mute ? FILTER_NONE : FILTER_PRIORITY) : FILTER_ALL;

        // 已经符合预期就不用动
        if (matches(before, on, mute)) {
            Logs.fire("勿扰", "状态已是「" + describe(before) + "」，无需操作");
            return true;
        }

        Logs.fire("勿扰", "准备切换：当前「" + describe(before) + "」→ 目标「"
                + describeFilter(wanted) + "」");

        // 路径 1：标准 API
        String err = tryFilter(ctx, wanted);
        int after = verify(ctx);
        if (matches(after, on, mute)) {
            Logs.key("✓", "勿扰已切换为「" + describe(after) + "」");
            return true;
        }

        // 路径 2：root 兜底
        String err2 = tryRoot(ctx, on, mute);
        after = verify(ctx);
        if (matches(after, on, mute)) {
            Logs.key("✓", "勿扰已切换为「" + describe(after) + "」（root）");
            return true;
        }

        // 路径 3：关闭时清残留规则
        if (!on) {
            String err3 = clearRules(ctx);
            after = verify(ctx);
            if (after == ZEN_OFF) {
                Logs.key("✓", "勿扰已关闭（清理规则）");
                return true;
            }
            Logs.e("勿扰关闭失败", null);
            Logs.i("  标准接口：" + (err == null ? "调用成功但没生效" : err));
            Logs.i("  root：" + (err2 == null ? "调用成功但没生效" : err2));
            Logs.i("  清理规则：" + err3);
            return false;
        }

        Logs.e("勿扰开启失败", null);
        Logs.i("  标准接口：" + (err == null ? "调用成功但没生效" : err));
        Logs.i("  root：" + (err2 == null ? "调用成功但没生效" : err2));
        return false;
    }

    /** 判断当前 zen 是否符合「on + mute」的预期。 */
    private static boolean matches(int zen, boolean on, boolean mute) {
        if (!on) return zen == ZEN_OFF;
        if (zen == ZEN_OFF) return false;
        // 开着即可；静音策略不强制匹配（不同 ROM 映射不同）
        return true;
    }

    // ================================================================ 路径

    private static String tryFilter(Context ctx, int filter) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return "通知服务不可用";

            nm.setInterruptionFilter(filter);
            Logs.fire("勿扰", "已调用系统接口（filter=" + filter + "）");
            return null;
        } catch (SecurityException se) {
            return "没有勿扰权限";
        } catch (Throwable t) {
            return Logs.brief(t);
        }
    }

    private static String tryRoot(Context ctx, boolean on, boolean mute) {
        String zen = on ? (mute ? "none" : "priority") : "off";
        String[] cmds = {
                "settings put global zen_mode " + (on ? (mute ? 2 : 1) : 0),
                "cmd notification --zen " + zen,
        };

        StringBuilder fail = new StringBuilder();
        for (String cmd : cmds) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                int rc = p.waitFor();
                if (rc != 0) fail.append(cmd).append(" 返回 ").append(rc).append("；");
            } catch (Throwable t) {
                fail.append(cmd).append(" 失败（").append(Logs.brief(t)).append("）；");
            }
        }
        return fail.length() == 0 ? null : fail.toString();
    }

    private static String clearRules(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return "通知服务不可用";

            java.util.Map<String, android.app.AutomaticZenRule> rules =
                    nm.getAutomaticZenRules();
            if (rules == null || rules.isEmpty()) return "没有残留规则";

            int n = 0;
            for (String id : rules.keySet()) {
                try {
                    if (nm.removeAutomaticZenRule(id)) n++;
                } catch (Throwable ignored) {
                }
            }
            Logs.fire("勿扰", "清理了 " + n + " 条残留规则");
            return "清理了 " + n + " 条";
        } catch (Throwable t) {
            return Logs.brief(t);
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

    /**
     * 写入后复核。
     * Settings 有进程内缓存，别的进程刚改的值可能读不到，所以等一下再读。
     */
    private static int verify(Context ctx) {
        try {
            Thread.sleep(150L);
        } catch (InterruptedException ignored) {
        }
        zen(ctx);
        try {
            Thread.sleep(100L);
        } catch (InterruptedException ignored) {
        }
        return zen(ctx);
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

    // ================================================================ 描述

    /** zen_mode 转人话 */
    static String describe(int zen) {
        switch (zen) {
            case 0: return "已关闭";
            case 1: return "已开启·仅优先通知";
            case 2: return "已开启·完全静音";
            case 3: return "已开启·仅闹钟";
            case 4: return "已开启·仅闹钟";
            default: return zen < 0 ? "读取失败" : ("已开启·模式" + zen);
        }
    }

    /** filter 值转人话 */
    private static String describeFilter(int filter) {
        switch (filter) {
            case FILTER_ALL: return "已关闭";
            case FILTER_PRIORITY: return "已开启·仅优先通知";
            case FILTER_NONE: return "已开启·完全静音";
            default: return "模式" + filter;
        }
    }

    /** 根据 zen 判断是否同步静音 */
    static boolean isMuted(int zen) {
        // 2=完全静音 3/4=仅闹钟，都算「静音」
        return zen >= 2;
    }

    // ================================================================ 诊断

    static String probe(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx == null) return "拿不到上下文";

        int before = zen(ctx);
        Logs.i("—— 诊断开始 ——");

        sb.append("当前状态：").append(describe(before)).append('\n');
        sb.append("勿扰权限：").append(hasAccess(ctx) ? "已授权" : "未授权").append('\n');
        sb.append("系统版本：Android ").append(Build.VERSION.SDK_INT)
                .append("，targetSdk ").append(ctx.getApplicationInfo().targetSdkVersion)
                .append("\n\n");

        boolean turnOn = before == ZEN_OFF;
        sb.append("测试 ").append(turnOn ? "开启" : "关闭").append("…\n\n");

        sb.append("① 系统标准接口\n");
        String e1 = tryFilter(ctx, turnOn ? FILTER_NONE : FILTER_ALL);
        int a1 = verify(ctx);
        sb.append("   ").append(e1 == null ? "调用成功" : "失败：" + e1)
                .append("，结果：").append(describe(a1)).append('\n');
        boolean ok1 = turnOn ? a1 > 0 : a1 == 0;
        sb.append(ok1 ? "   ✓ 成功\n\n" : "   ✗ 没生效\n\n");

        if (!ok1) {
            sb.append("② root 命令\n");
            String e2 = tryRoot(ctx, turnOn, true);
            int a2 = verify(ctx);
            sb.append("   ").append(e2 == null ? "调用成功" : "失败：" + e2)
                    .append("，结果：").append(describe(a2)).append('\n');
            boolean ok2 = turnOn ? a2 > 0 : a2 == 0;
            sb.append(ok2 ? "   ✓ 成功\n\n" : "   ✗ 没生效\n\n");
            if (ok2) {
                sb.append("—— 反向验证 ——\n");
                sb.append("③ 切回 ").append(turnOn ? "关闭" : "开启").append('\n');
                tryFilter(ctx, turnOn ? FILTER_ALL : FILTER_NONE);
                int a3 = verify(ctx);
                sb.append("   结果：").append(describe(a3)).append('\n');
                sb.append((turnOn ? a3 == 0 : a3 > 0) ? "   ✓ 双向正常\n" : "   ✗ 切不回去\n");
            }
            return sb.toString();
        }

        // 正向成功，验证能否切回
        sb.append("—— 反向验证 ——\n");
        sb.append("② 切回 ").append(turnOn ? "关闭" : "开启").append('\n');
        String e3 = tryFilter(ctx, turnOn ? FILTER_ALL : FILTER_NONE);
        int a3 = verify(ctx);
        sb.append("   ").append(e3 == null ? "调用成功" : "失败：" + e3)
                .append("，结果：").append(describe(a3)).append('\n');
        boolean ok3 = turnOn ? a3 == 0 : a3 > 0;
        if (ok3) {
            sb.append("   ✓ 双向正常\n");
        } else {
            sb.append("   ✗ 切不回去，尝试清理规则\n");
            clearRules(ctx);
            int a4 = verify(ctx);
            sb.append("   清理后：").append(describe(a4)).append('\n');
        }
        return sb.toString();
    }

    static String diagnose(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(describe(zen(ctx)));
        sb.append(" · 权限：").append(hasAccess(ctx) ? "已授权" : "未授权");
        return sb.toString();
    }
}
