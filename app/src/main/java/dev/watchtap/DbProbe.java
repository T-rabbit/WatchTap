package dev.watchtap;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用 root 读取 OPPO 健康的数据库。
 *
 * ==== 为什么不依赖 hook ====
 * hook 需要 LSPosed 把模块注入 com.heytap.health 进程。如果注入失败，
 * 模块在宿主进程里就什么都做不了。
 *
 * 但设备有 root，可以换个思路：
 *   1. su cp 把 .db 拷到我们自己的缓存目录
 *   2. 直接打开拷贝的副本读数据
 *
 * 这样完全不依赖 hook，只要 root 可用就能拿到数据。
 *
 * ==== 缺点 ====
 * 是「快照」而不是实时。所以按分钟级轮询即可，不需要毫秒精度。
 */
final class DbProbe {

    private static final String PKG = "com.heytap.health";
    private static final String DATA_DIR = "/data/user/0/" + PKG;

    /** 数据可能存放的目录 */
    private static final String[] DB_DIRS = {
            DATA_DIR + "/databases",
            DATA_DIR + "/files",
            DATA_DIR + "/no_backup",
    };

    private DbProbe() {}

    /**
     * 采集一次，把结果写入历史。
     *
     * @return 采集报告（给 UI 显示）
     */
    static String collect(Context ctx) {
        StringBuilder report = new StringBuilder();

        if (!Root.available()) {
            return "root 不可用，无法读取 OPPO 健康数据";
        }

        List<String> dbFiles = new ArrayList<>();
        for (String dir : DB_DIRS) {
            if (!Root.exists(dir)) continue;
            for (String name : Root.ls(dir)) {
                String lower = name.toLowerCase(Locale.US);
                if (lower.endsWith(".db") || lower.endsWith(".sqlite")
                        || lower.endsWith(".db3")) {
                    dbFiles.add(dir + "/" + name);
                }
            }
        }

        if (dbFiles.isEmpty()) {
            return "没找到 OPPO 健康的数据库文件\n"
                    + "已查找目录：\n  " + String.join("\n  ", DB_DIRS);
        }

        report.append("找到 ").append(dbFiles.size()).append(" 个数据库\n\n");

        int total = 0;
        for (String path : dbFiles) {
            File local = Root.copyToCache(ctx, path);
            if (local == null) {
                report.append(new File(path).getName()).append("：拷贝失败\n");
                continue;
            }

            String r = readDb(ctx, local);
            report.append(new File(path).getName()).append("：").append(r).append('\n');
            total += countIn(r);
        }

        report.insert(0, "采集完成，共写入 " + total + " 项\n\n");
        return report.toString();
    }

    private static int countIn(String r) {
        // 报告里形如 "3 张表 / 写入 2 项"
        try {
            int i = r.indexOf("写入 ");
            if (i < 0) return 0;
            int j = r.indexOf(" 项", i);
            if (j < 0) return 0;
            return Integer.parseInt(r.substring(i + 3, j).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 读一个已拷贝到本地的库。返回简短报告。 */
    private static String readDb(Context ctx, File db) {
        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);

            List<String> tables = new ArrayList<>();
            c = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && !n.startsWith("sqlite_") && !n.startsWith("android_")) {
                    tables.add(n);
                }
            }
            c.close();
            c = null;

            int written = 0;
            for (String t : tables) {
                written += harvestTable(ctx, sql, t);
            }
            return tables.size() + " 张表 / 写入 " + written + " 项";
        } catch (Throwable t) {
            return "打不开（" + Logs.brief(t) + "）";
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
            if (sql != null) {
                try {
                    sql.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 从一张表里提取指标。
     *
     * 不写死列名，而是：
     *   1. 用表名判断是哪个指标
     *   2. 找时间列（名字含 time/date/ts）
     *   3. 找值列（优先含 value/count/calorie/step 等）
     *   4. 取最新一行
     */
    private static int harvestTable(Context ctx, SQLiteDatabase sql, String table) {
        String metric = metricOf(table);
        if (metric == null) return 0;

        Cursor c = null;
        try {
            // 拿列名
            c = sql.rawQuery("SELECT * FROM " + quote(table) + " LIMIT 0", null);
            if (c == null) return 0;
            String[] cols = c.getColumnNames();
            c.close();
            c = null;
            if (cols == null || cols.length == 0) return 0;

            String timeCol = pickTimeCol(cols);
            String valCol = pickValueCol(cols, metric);
            if (valCol == null) return 0;

            String order = timeCol != null ? " ORDER BY " + quote(timeCol) + " DESC" : "";
            c = sql.rawQuery("SELECT " + quote(valCol)
                    + (timeCol != null ? "," + quote(timeCol) : "")
                    + " FROM " + quote(table) + order + " LIMIT 1", null);
            if (c == null || !c.moveToFirst()) return 0;

            long value = 0;
            boolean ok = false;
            try {
                value = c.getLong(0);
                ok = value != 0;
            } catch (Throwable ignored) {
                try {
                    value = Math.round(c.getDouble(0));
                    ok = value != 0;
                } catch (Throwable ignored2) {
                }
            }

            long ts = System.currentTimeMillis();
            if (timeCol != null && c.getColumnCount() > 1) {
                try {
                    long t = c.getLong(1);
                    if (t > 1000000000000L) ts = t;                 // 已是毫秒
                    else if (t > 1000000000L) ts = t * 1000L;       // 秒
                } catch (Throwable ignored) {
                }
            }
            c.close();
            c = null;

            if (!ok) return 0;

            // 血氧/心率可能存的是放大值，做个范围修正
            value = normalize(metric, value);

            History.append(ctx, ts, metric, value);
            return 1;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 表名 → 指标。 */
    private static String metricOf(String table) {
        String t = table.toLowerCase(Locale.US);
        if (t.contains("step")) return Metrics.STEPS;
        if (t.contains("calorie") || t.contains("energy")) return Metrics.CALORIES;
        if (t.contains("spo2") || t.contains("oxygen")) return Metrics.SPO2;
        if (t.contains("heart") || t.contains("pulse")) return Metrics.HR;
        if (t.contains("sleep")) return Metrics.SLEEP_STAGE;
        if (t.contains("sport") || t.contains("exercise")) return Metrics.EXERCISE_MIN;
        if (t.contains("mind") || t.contains("pressure")) return Metrics.MIND;
        if (t.contains("activity")) return Metrics.ACTIVE_COUNT;
        return null;
    }

    private static String pickTimeCol(String[] cols) {
        for (String c : cols) {
            String l = c.toLowerCase(Locale.US);
            if (l.contains("timestamp") || l.equals("ts") || l.contains("time")
                    || l.equals("date") || l.endsWith("_at")) {
                return c;
            }
        }
        return null;
    }

    private static String pickValueCol(String[] cols, String metric) {
        // 先按指标找专有列名
        String[] hints;
        switch (metric) {
            case Metrics.STEPS:
                hints = new String[]{"step", "count"};
                break;
            case Metrics.CALORIES:
                hints = new String[]{"calorie", "energy", "kcal"};
                break;
            case Metrics.SPO2:
                hints = new String[]{"spo2", "oxygen", "value"};
                break;
            case Metrics.HR:
                hints = new String[]{"heart", "rate", "pulse", "value"};
                break;
            case Metrics.SLEEP_STAGE:
                hints = new String[]{"stage", "level", "type", "phase"};
                break;
            case Metrics.EXERCISE_MIN:
                hints = new String[]{"duration", "minute", "time", "value"};
                break;
            case Metrics.ACTIVE_COUNT:
                hints = new String[]{"count", "times", "num"};
                break;
            default:
                hints = new String[]{"value", "level", "data"};
                break;
        }

        for (String h : hints) {
            for (String c : cols) {
                if (c.toLowerCase(Locale.US).contains(h)) return c;
            }
        }
        // 兜底：取最后一个数值型列名像数值的
        for (String c : cols) {
            String l = c.toLowerCase(Locale.US);
            if (l.equals("_id") || l.contains("time") || l.equals("date")) continue;
            if (l.contains("value") || l.contains("data") || l.contains("num")) return c;
        }
        return null;
    }

    /** 修正明显异常的量级（比如血氧存成 9800 表示 98%）。 */
    private static long normalize(String metric, long v) {
        if (Metrics.SPO2.equals(metric)) {
            if (v > 1000 && v <= 10000) return v / 100;   // 9800 → 98
            if (v > 10000) return v / 1000;
        }
        if (Metrics.HR.equals(metric)) {
            if (v > 1000) return v / 100;
        }
        if (Metrics.STEPS.equals(metric)) {
            if (v > 1000000) return v / 100;
        }
        return v;
    }

    private static String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /**
     * 列出所有库的表结构（诊断用，靠 root）。
     */
    static String dumpSchema() {
        StringBuilder sb = new StringBuilder();
        if (!Root.available()) return "root 不可用";

        sb.append("root：可用\n");
        sb.append("目标：").append(DATA_DIR).append("\n\n");

        int found = 0;
        for (String dir : DB_DIRS) {
            boolean ex = Root.exists(dir);
            sb.append("[").append(dir).append("] ")
                    .append(ex ? "存在" : "不存在").append('\n');
            if (!ex) continue;

            List<String> names = Root.ls(dir);
            int shown = 0;
            for (String n : names) {
                String lower = n.toLowerCase(Locale.US);
                if (!lower.endsWith(".db") && !lower.endsWith(".sqlite")
                        && !lower.endsWith(".db3")) {
                    continue;
                }
                found++;
                sb.append("  └ ").append(n).append('\n');
                if (++shown >= 10) {
                    sb.append("  …（还有更多）\n");
                    break;
                }
            }
            if (shown == 0) sb.append("  （无数据库文件）\n");
            sb.append('\n');
        }

        sb.append("数据库总数：").append(found).append('\n');
        if (found == 0) {
            sb.append("\n可能数据不在本地，而是实时从云端拉取。\n");
        }
        return sb.toString();
    }
}
