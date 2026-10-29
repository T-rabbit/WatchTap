package dev.watchtap;

import android.content.Context;

/**
 * 在 hook 进程内直接操作勿扰，不依赖模块进程。
 *
 * ==== 为什么需要它 ====
 * 原架构：
 *   hook 进程 → ContentResolver.call → provider 进程 → Dnd.set()
 *
 * provider 进程（dev.watchtap）被系统杀掉后，call 就失败了，
 * 联动和通知全部失效。这是设计缺陷。
 *
 * ==== 本类的做法 ====
 * 直接在 hook 进程内完成，只用 su：
 *   su -c "settings put global zen_mode N"
 *   su -c "cmd notification --zen on|off"
 *
 * su 授权认的是 uid，不是哪个进程。所以只要 KernelSU 里
 * 允许了 OPPO 健康（或允许 shell），hook 进程就能执行。
 *
 * ==== 权限对比 ====
 *   setInterruptionFilter  → 需要 ACCESS_NOTIFICATION_POLICY（hook 进程没有）
 *   su + settings put      → 只需要 root 授权（与进程无关）
 *
 * 所以走 su 这条路更可靠。
 */
final class DirectDnd {

    /** zen_mode 取值 */
    static final int ZEN_OFF = 0;
    static final int ZEN_PRIORITY = 1;
    static final int ZEN_NONE = 2;

    /** NotificationManager.INTERRUPTION_FILTER_* */
    private static final int FILTER_ALL = 1;
    private static final int FILTER_PRIORITY = 2;
    private static final int FILTER_NONE = 4;

    private DirectDnd() {}

    /** 当前进程是否有勿扰权限。 */
    static boolean hasAccess(Context c) {
        if (c == null) return false;
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationPolicyAccessGranted();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 调标准接口。返回是否未抛异常（是否真生效要看 zen_mode）。 */
    private static boolean tryFilter(Context c, int filter) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            nm.setInterruptionFilter(filter);
            Logs.i("调用系统接口 filter=" + filter);
            return true;
        } catch (SecurityException se) {
            Logs.w("无勿扰权限", se);
            return false;
        } catch (Throwable t) {
            Logs.w("调用系统接口失败", t);
            return false;
        }
    }

    /**
     * 设置勿扰。
     *
     * @param on   是否开启
     * @param mute 是否完全静音（false 时用 PRIORITY，允许优先通知）
     * @return 是否成功
     */
    static boolean set(boolean on, boolean mute) {
        int target = on ? (mute ? ZEN_NONE : ZEN_PRIORITY) : ZEN_OFF;

        int before = zen();
        if (before == target) {
            Logs.fire("勿扰", "已是「" + describe(before) + "」，无需操作");
            return true;
        }

        Logs.fire("勿扰", "切换：" + describe(before) + " → " + describe(target));

        Context c = State.ctx();

        // ---- 路径 1：标准 API ----
        // 只要给 com.heytap.health 授予了勿扰权限（cmd notification allow_dnd），
        // 这条路就能直接生效，完全不需要 root。
        if (c != null && hasAccess(c)) {
            int filter = on ? (mute ? FILTER_NONE : FILTER_PRIORITY) : FILTER_ALL;
            if (tryFilter(c, filter)) {
                sleep(200);
                int after = zen();
                if (after == target || (on && after > 0) || (!on && after == 0)) {
                    Logs.key("✓", "勿扰已切换为「" + describe(after) + "」（标准接口）");
                    return true;
                }
                Logs.w("标准接口没生效，改用 su", null);
            }
        } else if (c != null) {
            Logs.i("没有勿扰权限，尝试 su。如需免 root 请执行："
                    + "adb shell cmd notification allow_dnd com.heytap.health");
        }

        // ---- 路径 2：su 兜底 ----
        runSu("settings put global zen_mode " + target);
        runSu("cmd notification --zen " + zenArg(target));
        sleep(200);

        int after = zen();
        boolean ok = (after == target);

        if (!ok) {
            runSu("settings put global zen_mode " + target);
            sleep(150);
            after = zen();
            ok = (after == target);
        }

        if (ok) {
            Logs.key("✓", "勿扰已切换为「" + describe(after) + "」（su）");
        } else {
            Logs.e("勿扰切换失败（当前「" + describe(after) + "」）", null);
        }
        return ok;
    }

    /** 关掉勿扰（简化调用）。 */
    static boolean off() {
        return set(false, false);
    }

    /** 读当前 zen_mode。 */
    static int zen() {
        Context c = State.ctx();
        if (c == null) return -1;
        try {
            return android.provider.Settings.Global.getInt(
                    c.getContentResolver(), "zen_mode", 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** zen_mode 转中文。 */
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

    private static String zenArg(int zen) {
        switch (zen) {
            case ZEN_PRIORITY: return "priority";
            case ZEN_NONE: return "none";
            default: return "off";
        }
    }

    /** 执行 su 命令，忽略输出（避免阻塞）。 */
    private static void runSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // 不等待太久，避免卡住 hook 线程
            p.waitFor();
            Logs.i("执行：" + cmd);
        } catch (Throwable t) {
            Logs.w("执行失败：" + cmd, t);
        }
    }

    private static int sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
        return 0;
    }

    /**
     * 探测：hook 进程能否用 su 改勿扰。
     *
     * 返回诊断报告，用来判断 KernelSU 是否给 OPPO 健康授权了。
     */
    static String probe() {
        StringBuilder sb = new StringBuilder();

        Context c = State.ctx();
        sb.append("进程：").append(c == null ? "无上下文" : c.getPackageName()).append('\n');
        if (c != null) {
            sb.append("uid：").append(c.getApplicationInfo().uid).append('\n');
        }

        // 测 su
        String idOut = "";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            idOut = line == null ? "" : line;
        } catch (Throwable t) {
            idOut = "异常：" + Logs.brief(t);
        }
        sb.append("su id：").append(idOut.isEmpty() ? "无输出" : idOut).append('\n');
        boolean hasSu = idOut.contains("uid=0");
        sb.append("root 可用：").append(hasSu ? "是" : "否").append('\n');

        // 权限
        boolean access = hasAccess(c);
        sb.append("勿扰权限：").append(access ? "已授权" : "未授权");
        if (!access) {
            sb.append("（可执行：adb shell cmd notification allow_dnd ")
                    .append(c == null ? "com.heytap.health" : c.getPackageName())
                    .append("）");
        }
        sb.append('\n');

        // 测写 zen_mode
        int before = zen();
        sb.append("\n当前勿扰：").append(describe(before)).append('\n');

        if (access) {
            // 优先测标准接口
            int test = before == 0 ? ZEN_NONE : ZEN_OFF;
            int filter = before == 0 ? FILTER_NONE : FILTER_ALL;
            tryFilter(c, filter);
            sleep(250);
            int after = zen();
            sb.append("\n标准接口测试：").append(describe(test))
                    .append(" → 实际 ").append(describe(after))
                    .append(after == test ? "  ✓ 无需 root" : "  ✗").append('\n');
            if (after != before) {
                tryFilter(c, before == 0 ? FILTER_ALL : FILTER_NONE);
                sleep(150);
            }
            if (after == test) return sb.toString();
        }

        if (hasSu) {
            int test = before == 0 ? ZEN_NONE : ZEN_OFF;
            runSu("settings put global zen_mode " + test);
            sleep(250);
            int after = zen();
            sb.append("写入测试：").append(describe(test))
                    .append(" → 实际 ").append(describe(after))
                    .append(after == test ? "  ✓" : "  ✗").append('\n');

            // 恢复原值
            if (after != before) {
                runSu("settings put global zen_mode " + before);
                sleep(150);
            }
        } else {
            sb.append("没有 root，无法写勿扰\n");
            sb.append("→ 需要在 KernelSU 里给 ").append(
                    c == null ? "OPPO 健康" : c.getPackageName()).append(" 授权\n");
        }

        return sb.toString();
    }
}
