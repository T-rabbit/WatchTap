package dev.watchtap;

import android.content.Context;

import java.io.File;

/**
 * 跨进程偏好存储：不依赖模块进程存活，也不需要 root。
 *
 * ==== 存储位置的选择 ====
 *
 * Android 11+ 限制：应用只能写自己的
 * /sdcard/Android/data/<自己的包名>/files/，读不到别的应用的。
 *
 * 所以「两边共用一个文件」这条常规路走不通。这里改成：
 *
 *   1. 每个进程**写自己**的目录
 *        /sdcard/Android/data/<自己包名>/files/watchtap_prefs.txt
 *
 *   2. 读取时**先试所有候选路径**，能读到就用
 *
 *   3. 关键补充：用 root 跨读（如果有 root）
 *        su cat /sdcard/Android/data/dev.watchtap/files/watchtap_prefs.txt
 *
 * ==== 更重要的设计：状态由 hook 进程持有 ====
 *
 * 即便上面都失败，联动也不会用错开关值，因为：
 *
 *   - UI 点开关 → 通过 provider 的 `sync` 方法把值传给 hook 进程
 *   - hook 进程把值存在自己的内存 + 自己的文件里
 *   - 之后 hook 进程只读自己的存储，不依赖任何外部进程
 *
 * 也就是说：**UI 只负责下发一次指令，之后 hook 进程自给自足。**
 * 模块进程随后被杀，开关值仍然保留在 hook 进程里。
 *
 * 本类只负责「持久化到磁盘」，内存里还有一份由 State 持有。
 */
final class Prefs {

    private static final String FILE_NAME = "watchtap_prefs.txt";

    static final String K_MUTE = "mute";
    static final String K_NOTIFY = "notify";
    static final String K_KEEPALIVE = "keepalive";
    /** 上次记录的睡眠模式状态（0/1），用于进程重启后恢复基线 */
    static final String K_LAST_SLEEP_MODE = "lastSleepMode";

    private Prefs() {}

    /** 本进程自己的偏好文件。 */
    private static File ownFile(Context c) {
        if (c == null) return null;
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return null;
        return new File(dir, FILE_NAME);
    }

    /** 所有可能的路径（本进程 + 对方进程）。 */
    private static String[] candidatePaths(Context c) {
        File own = ownFile(c);
        String ownPath = own == null ? "" : own.getAbsolutePath();

        return new String[]{
                ownPath,
                "/sdcard/Android/data/" + (c == null ? "dev.watchtap" : c.getPackageName())
                        + "/files/" + FILE_NAME,
                "/sdcard/Android/data/dev.watchtap/files/" + FILE_NAME,
                "/sdcard/Android/data/com.heytap.health/files/" + FILE_NAME,
        };
    }

    /**
     * 读一个布尔偏好。
     *
     * 顺序：本进程文件 → 对方文件（直接读）→ 对方文件（root 读）
     */
    static boolean getBool(Context c, String key, boolean def) {
        // 1. 本进程自己写得进去也读得出来
        File own = ownFile(c);
        if (own != null) {
            String v = readLocal(own, key);
            if (v != null) return truthy(v);
        }

        // 2. 试直接读对方的（同 uid 或有读权限时可行）
        for (String p : candidatePaths(c)) {
            if (p.isEmpty()) continue;
            String v = readLocal(new File(p), key);
            if (v != null) return truthy(v);
        }

        // 3. root 跨读（最可靠）
        if (Root.available()) {
            for (String p : candidatePaths(c)) {
                if (p.isEmpty()) continue;
                String xml = Root.exec("cat '" + p + "' 2>/dev/null", 4000);
                String v = parseValue(xml, key);
                if (v != null) return truthy(v);
            }
        }
        return def;
    }

    /**
     * 写一个布尔偏好。
     *
     * 只写本进程自己的文件 —— 别的应用的目录写不进去。
     * 跨进程同步靠 provider 下发（见 StateStore.METHOD_SYNC）。
     */
    static void setBool(Context c, String key, boolean value) {
        File own = ownFile(c);
        if (own == null) return;
        writeLocal(own, key, value ? "1" : "0");
    }

    /** 读一个长整型偏好。 */
    static long getLong(Context c, String key, long def) {
        File own = ownFile(c);
        String v = own == null ? null : readLocal(own, key);
        if (v == null) {
            for (String p : candidatePaths(c)) {
                if (p.isEmpty()) continue;
                v = readLocal(new File(p), key);
                if (v != null) break;
            }
        }
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 写一个长整型偏好。 */
    static void setLong(Context c, String key, long value) {
        File own = ownFile(c);
        if (own == null) return;
        writeLocal(own, key, String.valueOf(value));
    }

    // ---------------------------------------------------------------- 文件

    private static String readLocal(File f, String key) {
        try {
            if (f == null || !f.exists() || !f.canRead()) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf('=');
                    if (i <= 0) continue;
                    if (key.equals(line.substring(0, i).trim())) {
                        return line.substring(i + 1).trim();
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从文本里找 key=value（给 root cat 的输出用）。 */
    private static String parseValue(String text, String key) {
        if (text == null || text.isEmpty()) return null;
        for (String line : text.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            if (key.equals(line.substring(0, i).trim())) {
                return line.substring(i + 1).trim();
            }
        }
        return null;
    }

    private static boolean truthy(String v) {
        return "1".equals(v) || "true".equals(v);
    }

    private static void writeLocal(File f, String key, String value) {
        try {
            File dir = f.getParentFile();
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;

            java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
            if (f.exists() && f.canRead()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader(f));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.indexOf('=');
                        if (i > 0) {
                            map.put(line.substring(0, i).trim(),
                                    line.substring(i + 1).trim());
                        }
                    }
                } finally {
                    r.close();
                }
            }
            map.put(key, value);

            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : map.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }

            java.io.FileWriter w = new java.io.FileWriter(f, false);
            try {
                w.write(sb.toString());
            } finally {
                w.close();
            }
            try {
                f.setReadable(true, false);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }
}
