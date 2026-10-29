package dev.watchtap;

import java.util.ArrayList;
import java.util.List;

/**
 * 保活：让 OPPO 健康和本模块都不被系统杀掉。
 *
 * ==== OPPO 健康保活（需要 root）====
 *
 * Android 杀后台应用有几个开关，逐个关掉：
 *
 *   1. 电池优化白名单（Doze whitelist）
 *      dumpsys deviceidle whitelist +com.heytap.health
 *      → 打盹模式下不会被冻结
 *
 *   2. 后台运行权限（appops）
 *      cmd appops set com.heytap.health RUN_IN_BACKGROUND allow
 *      cmd appops set com.heytap.health RUN_ANY_IN_BACKGROUND allow
 *      → 允许后台启动和常驻
 *
 *   3. 待机桶（standby bucket）
 *      am set-standby-bucket com.heytap.health active
 *      → 从「罕见」提到「活跃」，不被限流
 *
 *   4. 省电策略（OPPO 自家的）
 *      部分 ROM 有 appops 之外的控制，用 settings 兜底
 *
 * ==== 本模块保活 ====
 * 用前台服务（见 KeepAliveService），带常驻通知。
 * 这样 provider 进程不会被回收，看门狗才能持续工作。
 *
 * ==== 看门狗 ====
 * 定时检查 OPPO 健康进程是否还活着，死了就拉起来。
 * 拉起方式有多个，按侵入性从小到大依次尝试。
 */
final class KeepAlive {

    static final String TARGET = "com.heytap.health";

    private KeepAlive() {}

    // ================================================================ 状态

    /** 当前状态快照。 */
    static final class Status {
        boolean root;
        boolean dozeWhitelisted;      // 电池优化白名单
        boolean bgAllowed;            // 允许后台运行
        boolean standbyActive;        // 活跃待机桶
        boolean running;              // 进程存活
        int pid;
        String bucket = "?";

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("root：").append(root ? "可用" : "不可用").append('\n');
            sb.append("OPPO 健康：").append(running ? "运行中 (pid " + pid + ")" : "已停止").append('\n');
            sb.append("电池优化白名单：").append(dozeWhitelisted ? "已加入" : "未加入").append('\n');
            sb.append("后台运行权限：").append(bgAllowed ? "已允许" : "未允许").append('\n');
            sb.append("待机桶：").append(bucket).append(standbyActive ? "（活跃）" : "").append('\n');
            return sb.toString();
        }

        /** 是否全部就绪。 */
        boolean allGood() {
            return root && dozeWhitelisted && bgAllowed && standbyActive;
        }
    }

    /** 查询当前保活状态。 */
    static Status status() {
        Status st = new Status();
        st.root = Root.available();
        if (!st.root) {
            st.bucket = "无 root";
            return st;
        }

        // 进程
        String pidOut = Root.exec("pidof " + TARGET, 4000).trim();
        // 可能返回多个 pid，取第一个
        String first = pidOut.split("\\s+")[0];
        try {
            st.pid = Integer.parseInt(first.trim());
            st.running = st.pid > 0;
        } catch (Throwable ignored) {
            st.running = false;
        }

        // 电池优化白名单
        String whitelist = Root.exec("dumpsys deviceidle whitelist 2>/dev/null", 6000);
        st.dozeWhitelisted = whitelist.contains(TARGET);

        // appops
        String ops = Root.exec("cmd appops get " + TARGET + " RUN_IN_BACKGROUND 2>/dev/null", 5000);
        st.bgAllowed = ops.contains("allow");

        // 待机桶
        String b = Root.exec("am get-standby-bucket " + TARGET + " 2>/dev/null", 5000).trim();
        st.bucket = b.isEmpty() ? "?" : b;
        // active=10, working_set=20, frequent=30, rare=40, restricted=45
        st.standbyActive = st.bucket.contains("10") || st.bucket.contains("20")
                || st.bucket.contains("active") || st.bucket.contains("working");

        return st;
    }

    // ================================================================ 应用

    /**
     * 应用保活设置。返回执行报告。
     */
    static String apply() {
        if (!Root.available()) return "root 不可用，无法设置保活";

        StringBuilder sb = new StringBuilder();
        List<String[]> cmds = new ArrayList<>();

        // 1. 电池优化白名单
        cmds.add(new String[]{"电池优化白名单",
                "dumpsys deviceidle whitelist +" + TARGET});

        // 2. 后台运行权限
        cmds.add(new String[]{"允许后台运行",
                "cmd appops set " + TARGET + " RUN_IN_BACKGROUND allow"});
        cmds.add(new String[]{"允许任意后台",
                "cmd appops set " + TARGET + " RUN_ANY_IN_BACKGROUND allow"});

        // 3. 待机桶设为 active
        cmds.add(new String[]{"待机桶设为活跃",
                "am set-standby-bucket " + TARGET + " active"});

        // 4. 允许后台弹出/唤醒（部分 ROM 需要）
        cmds.add(new String[]{"允许后台唤醒",
                "cmd appops set " + TARGET + " WAKE_LOCK allow"});

        for (String[] c : cmds) {
            String out = Root.exec(c[1] + " 2>&1", 8000);
            boolean ok = !out.toLowerCase().contains("error")
                    && !out.toLowerCase().contains("exception")
                    && !out.toLowerCase().contains("denied");
            sb.append(ok ? "✓ " : "✗ ").append(c[0]);
            if (!ok) {
                String t = out.trim();
                if (!t.isEmpty()) sb.append("（").append(t.substring(0, Math.min(60, t.length()))).append("）");
            }
            sb.append('\n');
        }

        Logs.fire("保活", "已应用 OPPO 健康保活设置");

        // 立刻复查
        Status st = status();
        sb.append('\n').append(st.describe());
        return sb.toString();
    }

    /** 撤销保活（恢复默认）。 */
    static String revert() {
        if (!Root.available()) return "root 不可用";

        StringBuilder sb = new StringBuilder();
        String[][] cmds = {
                {"移出电池优化白名单", "dumpsys deviceidle whitelist -" + TARGET},
                {"恢复后台运行", "cmd appops set " + TARGET + " RUN_IN_BACKGROUND default"},
                {"恢复待机桶", "am set-standby-bucket " + TARGET + " rare"},
        };
        for (String[] c : cmds) {
            Root.exec(c[1] + " 2>&1", 8000);
            sb.append("· ").append(c[0]).append('\n');
        }
        Logs.fire("保活", "已撤销保活设置");
        return sb.toString();
    }

    // ================================================================ 唤醒

    /**
     * 拉起 OPPO 健康。
     *
     * 按侵入性从小到大尝试：
     *   1. 发 BOOT_COMPLETED 广播 —— 很多应用收到会启动后台服务，不弹界面
     *   2. 启动它的服务（猜几个常见组件名）
     *   3. 最后手段：monkey 启动主界面（会闪一下）
     *
     * @return 是否成功拉起
     */
    static boolean wake() {
        if (!Root.available()) return false;

        // 已经活着就不用拉
        if (isRunning()) return true;

        Logs.fire("保活", "OPPO 健康已停止，尝试拉起");

        // 策略 1：广播
        Root.exec("am broadcast -a android.intent.action.BOOT_COMPLETED -p " + TARGET, 8000);
        if (waitForStart(1500)) {
            Logs.fire("保活", "拉起成功（广播）");
            return true;
        }

        // 策略 2：启动常见后台服务组件
        String[] services = {
                TARGET + "/.devicemanager.btclient.BTService",
                TARGET + "/.core.service.HealthCoreService",
                TARGET + "/.SportDaemonService",
                TARGET + "/.devicemanager.service.DeviceManagerService",
        };
        for (String s : services) {
            Root.exec("am start-service -n " + s, 6000);
            if (waitForStart(1200)) {
                Logs.fire("保活", "拉起成功（service " + s + "）");
                return true;
            }
        }

        // 策略 3：monkey 启动界面（兜底）
        Root.exec("monkey -p " + TARGET + " -c android.intent.category.LAUNCHER 1", 10000);
        if (waitForStart(2000)) {
            Logs.fire("保活", "拉起成功（monkey）");
            return true;
        }

        Logs.w("保活：所有拉起方式都失败", null);
        return false;
    }

    private static boolean waitForStart(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (isRunning()) return true;
            try {
                Thread.sleep(150L);
            } catch (InterruptedException ignored) {
            }
        }
        return isRunning();
    }

    /** 进程是否活着。 */
    static boolean isRunning() {
        if (!Root.available()) return false;
        String out = Root.exec("pidof " + TARGET, 3000).trim();
        return !out.isEmpty() && out.split("\\s+").length > 0
                && out.split("\\s+")[0].matches("\\d+");
    }
}
