package dev.watchtap;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * root 命令工具。
 *
 * ==== 为什么需要它 ====
 * 模块的 hook 进程（com.heytap.health）拿不到数据时，模块自己的进程
 * （dev.watchtap）也没有权限读 OPPO 健康的私有目录。
 *
 * 但设备有 root（KernelSU）。用 su 可以做两件事：
 *   1. 读别的应用的数据 —— 把 .db 拷到我们自己的目录再打开
 *   2. 查 LSPosed 状态 —— 诊断 hook 为什么没生效
 *
 * 这样即使 hook 完全不工作，也能通过 root 拿到健康数据。
 */
final class Root {

    private static volatile Boolean sHasRoot;

    private Root() {}

    /** 是否有 root（结果缓存）。 */
    static boolean available() {
        Boolean c = sHasRoot;
        if (c != null) return c;

        boolean ok = false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            ok = line != null && line.contains("uid=0");
        } catch (Throwable ignored) {
        }
        sHasRoot = ok;
        return ok;
    }

    /** 执行 su 命令，返回 stdout（合并 stderr）。 */
    static String exec(String cmd) {
        return exec(cmd, 8000);
    }

    /**
     * @param timeoutMs 超时毫秒，超时就杀进程避免卡死
     */
    static String exec(String cmd, long timeoutMs) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});

            final Process fp = p;
            final StringBuilder out = sb;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(fp.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Throwable ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            // 等进程结束，超时则强杀
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (p.isAlive()) {
                if (System.currentTimeMillis() > deadline) {
                    p.destroyForcibly();
                    synchronized (sb) {
                        sb.append("\n[命令超时]");
                    }
                    break;
                }
                Thread.sleep(50L);
            }
            reader.join(1000L);
        } catch (Throwable t) {
            synchronized (sb) {
                sb.append("执行失败：").append(Logs.brief(t));
            }
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (sb) {
            return sb.toString();
        }
    }

    /**
     * 用 root 把文件拷到我们自己的缓存目录，之后就能正常读。
     *
     * @param srcPath 源文件绝对路径（root 才能读）
     * @return 拷贝后的文件，失败返回 null
     */
    static File copyToCache(Context ctx, String srcPath) {
        if (ctx == null || srcPath == null) return null;
        try {
            File dir = new File(ctx.getCacheDir(), "db");
            if (!dir.exists()) dir.mkdirs();

            String name = new File(srcPath).getName();
            File dst = new File(dir, name);

            // 每次拷贝前删旧的，避免读到旧数据
            if (dst.exists()) dst.delete();

            String cmd = "cp '" + srcPath + "' '" + dst.getAbsolutePath() + "'"
                    + " && chmod 666 '" + dst.getAbsolutePath() + "'";
            String out = exec(cmd, 6000);

            if (dst.exists() && dst.length() > 0) return dst;

            // cp 失败时退回 cat 重定向
            String cmd2 = "cat '" + srcPath + "' > '" + dst.getAbsolutePath() + "'"
                    + " && chmod 666 '" + dst.getAbsolutePath() + "'";
            exec(cmd2, 8000);
            if (dst.exists() && dst.length() > 0) return dst;

            Logs.w("root 拷贝失败：" + name + " " + out.trim(), null);
            return null;
        } catch (Throwable t) {
            Logs.w("root 拷贝异常", t);
            return null;
        }
    }

    /** 列出目录下的文件（root 权限）。 */
    static java.util.List<String> ls(String dirPath) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String r = exec("ls -1 '" + dirPath + "' 2>/dev/null", 5000);
        for (String line : r.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("[")) out.add(s);
        }
        return out;
    }

    /** 文件是否存在（root）。 */
    static boolean exists(String path) {
        String r = exec("test -e '" + path + "' && echo YES || echo NO", 4000);
        return r.contains("YES");
    }

    // ---------------------------------------------------------------- 诊断

    /**
     * 诊断 LSPosed 状态，回答「hook 为什么没生效」。
     */
    static String diagnoseLsposed() {
        StringBuilder sb = new StringBuilder();
        sb.append("root：").append(available() ? "可用" : "不可用").append('\n');
        if (!available()) {
            sb.append("（无法继续查询 LSPosed 状态）\n");
            return sb.toString();
        }

        String lspctl = "/data/adb/modules/zygisk_lsposed/lspctl";
        sb.append('\n');

        // 1. lspctl 是否存在
        if (!exists(lspctl)) {
            sb.append("lspctl：不存在\n");
            sb.append("→ LSPosed 模块目录不对，或没装 Zygisk 版 LSPosed\n");
            return sb.toString();
        }
        sb.append("lspctl：存在\n\n");

        // 2. 模块列表里找自己
        sb.append("--- 模块注册状态 ---\n");
        String mods = exec(lspctl + " --json module list 2>&1", 10000);
        boolean found = mods.contains("dev.watchtap");
        if (!found) {
            sb.append("模块列表里没有 dev.watchtap\n");
            sb.append("→ LSPosed 没识别到模块，检查 APK 的 META-INF/xposed/ 文件\n");
        } else {
            sb.append("模块已被 LSPosed 识别\n");
            // 抓出自己那一段
            int i = mods.indexOf("dev.watchtap");
            int start = Math.max(0, i - 260);
            int end = Math.min(mods.length(), i + 160);
            sb.append("  …").append(mods, start, end).append("…\n");
        }

        // 3. 是否启用
        sb.append("\n--- 启用状态 ---\n");
        String en = exec(lspctl + " --json module show dev.watchtap 2>&1", 8000);
        sb.append(en.isEmpty() ? "查询无返回\n" : en + "\n");

        // 4. 作用域
        sb.append("\n--- 作用域 ---\n");
        String scope = exec(lspctl + " --json scope list dev.watchtap 2>&1", 8000);
        sb.append(scope.isEmpty() ? "查询无返回\n" : scope + "\n");
        if (!scope.contains("com.heytap.health")) {
            sb.append("⚠ 作用域缺少 com.heytap.health！这就是没生效的原因\n");
        }

        // 5. LSPosed 日志里搜自己
        sb.append("\n--- LSPosed 日志（含 WatchTap）---\n");
        String log = exec("grep -i 'watchtap\\|dev.watchtap' /data/adb/lspd/log/*.log 2>/dev/null | tail -15", 8000);
        sb.append(log.trim().isEmpty() ? "无相关日志\n" : log + "\n");

        return sb.toString();
    }
}
