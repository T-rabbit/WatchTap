package dev.watchtap;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 深度探测：找出 OPPO 健康把数据藏在哪。
 *
 * ==== 背景 ====
 * 之前只找 databases/*.db，结果什么都没有。可能的原因：
 *   1. 数据不在 SQLite，而是别的格式（json/protobuf/自定义二进制）
 *   2. 数据在 room 数据库但扩展名不同（.db-wal / .room / 无扩展名）
 *   3. 数据包名不是 com.heytap.health
 *   4. 数据通过 ContentProvider 暴露
 *
 * 本类把这四种可能一次性查清楚。
 */
final class DeepProbe {

    private static final String PKG = "com.heytap.health";

    private DeepProbe() {}

    /**
     * 完整探测，返回报告。
     */
    static String probe(Context ctx) {
        StringBuilder sb = new StringBuilder();

        if (!Root.available()) {
            sb.append("root 不可用，无法探测\n");
            return sb.toString();
        }
        sb.append("root：可用\n\n");

        // 1. 相关包
        sb.append("=== 1. 相关应用包 ===\n");
        sb.append(findRelatedPackages(ctx)).append('\n');

        // 2. 全目录文件清单
        sb.append("=== 2. 数据目录完整清单 ===\n");
        sb.append(listAllFiles("/data/user/0/" + PKG)).append('\n');

        // 3. ContentProvider
        sb.append("=== 3. ContentProvider ===\n");
        sb.append(probeProviders(ctx)).append('\n');

        // 4. 其他可能位置
        sb.append("=== 4. 其他可能的数据位置 ===\n");
        sb.append(otherLocations()).append('\n');

        return sb.toString();
    }

    // ---------------------------------------------------------------- 1

    /** 找出所有可能的健康相关包名。 */
    private static String findRelatedPackages(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> all = pm.getInstalledPackages(0);
            List<String> hits = new ArrayList<>();

            for (PackageInfo pi : all) {
                String n = pi.packageName;
                String lower = n.toLowerCase(Locale.US);
                if (lower.contains("heytap") || lower.contains("health")
                        || lower.contains("oplus") || lower.contains("coloros")) {
                    hits.add(n);
                }
            }

            if (hits.isEmpty()) {
                sb.append("没有找到相关包\n");
            } else {
                for (String h : hits) {
                    sb.append("  ").append(h);
                    // 检查数据目录是否存在
                    if (Root.exists("/data/user/0/" + h)) {
                        sb.append("  ✓ 有数据目录");
                    }
                    sb.append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("查询失败：").append(Logs.brief(t)).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 2

    /**
     * 递归列出数据目录下所有文件，带大小。
     *
     * 按大小排序，最大的文件最可能是数据存储。
     */
    private static String listAllFiles(String dir) {
        StringBuilder sb = new StringBuilder();
        if (!Root.exists(dir)) {
            return "目录不存在：" + dir + "\n";
        }

        // 用 du 找大文件（跳过缓存和代码）
        sb.append("--- 最大的 30 个文件 ---\n");
        String cmd = "find '" + dir + "' -type f -size +1k 2>/dev/null "
                + "| head -2000 "
                + "| xargs -r du -k 2>/dev/null "
                + "| sort -rn | head -30";
        String out = Root.exec(cmd, 15000);
        sb.append(out.trim().isEmpty() ? "（没有大于 1KB 的文件）\n" : out);

        // 目录结构概览
        sb.append("\n--- 一级目录 ---\n");
        String dirs = Root.exec("ls -la '" + dir + "' 2>/dev/null", 6000);
        for (String line : dirs.split("\n")) {
            if (line.startsWith("d") || line.contains(" -> ")) {
                sb.append(line).append('\n');
            }
        }

        return sb.toString();
    }

    // ---------------------------------------------------------------- 3

    /**
     * 枚举并尝试查询 ContentProvider。
     *
     * 有 root 的话，即使 provider 不 exported 也能查。
     */
    private static String probeProviders(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(PKG, PackageManager.GET_PROVIDERS);
            ProviderInfo[] providers = pi.providers;
            if (providers == null || providers.length == 0) {
                return "该应用没有声明 ContentProvider\n";
            }

            sb.append("共 ").append(providers.length).append(" 个 provider：\n\n");

            for (ProviderInfo p : providers) {
                sb.append("▸ ").append(p.authority)
                        .append(p.exported ? "（exported）" : "（私有）")
                        .append('\n');

                // 尝试查询
                String r = tryQuery(Uri.parse("content://" + p.authority));
                sb.append("  ").append(r).append('\n');
            }
        } catch (Throwable t) {
            sb.append("枚举失败：").append(Logs.brief(t)).append('\n');
        }
        return sb.toString();
    }

    /** 检查其他可能存数据的地方。 */
    private static String otherLocations() {
        StringBuilder sb = new StringBuilder();

        String[] paths = {
                "/data/data/com.heytap.health",            // 老路径（未分用户时）
                "/data/user_de/0/com.heytap.health",       // 设备加密存储
                "/data/media/0/Android/data/com.heytap.health",
                "/sdcard/Android/data/com.heytap.health",
                "/data/system_ce/0",                       // 系统数据
                "/data/misc/health",                       // 厂商自定义
                "/data/vendor/health",
        };

        for (String p : paths) {
            boolean ex = Root.exists(p);
            sb.append(ex ? "✓ " : "✗ ").append(p).append('\n');
            if (ex) {
                // 有几个文件
                String cnt = Root.exec("find '" + p + "' -type f 2>/dev/null | wc -l", 8000);
                sb.append("    文件数：").append(cnt.trim()).append('\n');
            }
        }

        // 全局搜含 step/sleep 字样的文件（限数据库类）
        sb.append("\n--- 全局搜索健康数据库 ---\n");
        // 避免转义麻烦：用 -name 多次 + 单引号包裹
        String search = Root.exec(
                "find /data -maxdepth 6 -type f -name '*.db' 2>/dev/null"
                        + " | grep -iE 'heytap|health|sport|fitness' | head -15",
                20000);
        sb.append(search.trim().isEmpty() ? "未找到\n" : search);

        return sb.toString();
    }

    /** 尝试查询一个 provider。 */
    private static String tryQuery(Uri uri) {
        Cursor c = null;
        try {
            Context ctx = State.ctx();
            if (ctx == null) return "跳过（无上下文）";

            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c == null) return "无返回";

            int rows = c.getCount();
            String[] cols = c.getColumnNames();

            StringBuilder sb = new StringBuilder();
            sb.append("✓ 可查询，").append(rows).append(" 行");
            if (cols != null && cols.length > 0) {
                sb.append("，列：");
                for (int i = 0; i < Math.min(cols.length, 12); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(cols[i]);
                }
            }

            // 取一行样例
            if (rows > 0 && c.moveToFirst() && cols != null) {
                sb.append("\n    样例：");
                for (int i = 0; i < Math.min(cols.length, 6); i++) {
                    if (i > 0) sb.append(", ");
                    try {
                        String v = c.getString(i);
                        if (v != null && v.length() > 24) v = v.substring(0, 24) + "…";
                        sb.append(cols[i]).append('=').append(v);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "✗ " + Logs.brief(t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
