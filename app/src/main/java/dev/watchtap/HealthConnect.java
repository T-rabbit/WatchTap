package dev.watchtap;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从 Android Health Connect 读取健康数据。
 *
 * ==== 为什么这条路可行 ====
 * 探测发现：/data/system_ce/0/healthconnect/healthconnect.db 存在。
 * 这是 Android 官方的健康数据中心，OPPO 健康会把数据同步进去。
 *
 * 好处：
 *   - 数据是标准格式，不用猜 OPPO 的私有结构
 *   - 有 root 就能直接读库，不用申请 Health Connect 权限
 *   - 手表同步的数据（步数/睡眠/心率/血氧）通常都在里面
 *
 * ==== Health Connect 表结构（已知）====
 *   local_steps_record_table                  步数
 *   local_heart_rate_record_table             心率
 *   local_oxygen_saturation_record_table      血氧
 *   local_sleep_session_record_table          睡眠会话
 *   local_sleep_stages_record_table           睡眠分期
 *   local_exercise_session_record_table       锻炼
 *   local_active_calories_burned_record_table 活动消耗
 *   local_total_calories_burned_record_table  总消耗
 *
 * 每条记录都带 time 列（毫秒时间戳）和一个数据列。
 */
final class HealthConnect {

    private static final String DB_PATH = "/data/system_ce/0/healthconnect/healthconnect.db";

    /** 表名关键词 → 指标 id */
    private static final String[][] MAP = {
            {"steps_record", Metrics.STEPS},
            {"heart_rate", Metrics.HR},
            {"oxygen_saturation", Metrics.SPO2},
            {"sleep_stages", Metrics.SLEEP_STAGE},
            {"sleep_session", Metrics.SLEEP_STAGE},
            {"active_calories", Metrics.CALORIES},
            {"total_calories", Metrics.CALORIES},
            {"exercise_session", Metrics.EXERCISE_MIN},
    };

    private HealthConnect() {}

    /** 数据库是否存在（需要 root）。 */
    static boolean available() {
        return Root.available() && Root.exists(DB_PATH);
    }

    /**
     * 采集一次。返回报告。
     */
    static String collect(Context ctx) {
        if (!Root.available()) return "root 不可用";
        if (!Root.exists(DB_PATH)) return "Health Connect 数据库不存在";

        File local = Root.copyToCache(ctx, DB_PATH);
        if (local == null) return "拷贝 Health Connect 数据库失败";

        StringBuilder report = new StringBuilder();
        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = SQLiteDatabase.openDatabase(local.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);

            // 列出所有表
            List<String> tables = new ArrayList<>();
            c = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && n.startsWith("local_")) tables.add(n);
            }
            c.close();
            c = null;

            report.append("共 ").append(tables.size()).append(" 张记录表\n\n");

            int written = 0;
            java.util.Set<String> done = new java.util.HashSet<>();

            for (String table : tables) {
                String metric = metricOf(table);
                if (metric == null) continue;
                // 同一指标只取第一张匹配的表
                if (done.contains(metric)) continue;

                int n = harvest(ctx, sql, table, metric);
                if (n > 0) {
                    done.add(metric);
                    written += n;
                    report.append(Metrics.nameOf(metric)).append("：")
                            .append(n).append(" 条（").append(table).append("）\n");
                }
            }

            report.insert(0, "写入 " + written + " 项\n\n");
        } catch (Throwable t) {
            return "读库失败：" + Logs.brief(t);
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
        return report.toString();
    }

    private static String metricOf(String table) {
        String t = table.toLowerCase(Locale.US);
        for (String[] m : MAP) {
            if (t.contains(m[0])) return m[1];
        }
        return null;
    }

    /**
     * 从一张表里取最近的数据。
     *
     * @return 写入条数
     */
    private static int harvest(Context ctx, SQLiteDatabase sql, String table, String metric) {
        Cursor c = null;
        try {
            c = sql.rawQuery("SELECT * FROM \"" + table + "\" LIMIT 0", null);
            if (c == null) return 0;
            String[] cols = c.getColumnNames();
            c.close();
            c = null;
            if (cols == null || cols.length == 0) return 0;

            String timeCol = null;
            for (String col : cols) {
                if ("time".equalsIgnoreCase(col) || col.toLowerCase(Locale.US).contains("time")) {
                    timeCol = col;
                    break;
                }
            }
            String valCol = valueCol(cols, metric);
            if (valCol == null || timeCol == null) return 0;

            String order = " ORDER BY \"" + timeCol + "\" DESC";
            c = sql.rawQuery("SELECT \"" + valCol + "\",\"" + timeCol + "\" FROM \""
                    + table + "\"" + order + " LIMIT 200", null);
            if (c == null) return 0;

            int n = 0;
            while (c.moveToNext() && n < 200) {
                long v;
                try {
                    v = c.getLong(0);
                } catch (Throwable t) {
                    continue;
                }
                long ts = c.getLong(1);
                if (v == 0 || ts <= 0) continue;

                v = normalize(metric, v);
                History.append(ctx, ts, metric, v);
                n++;
            }
            return n;
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

    private static String valueCol(String[] cols, String metric) {
        String[] hints;
        switch (metric) {
            case Metrics.STEPS:
                hints = new String[]{"count", "steps"};
                break;
            case Metrics.HR:
                hints = new String[]{"beats", "rate", "bpm"};
                break;
            case Metrics.SPO2:
                hints = new String[]{"percentage", "saturation", "value"};
                break;
            case Metrics.CALORIES:
                hints = new String[]{"energy", "calories", "kcal"};
                break;
            case Metrics.EXERCISE_MIN:
                hints = new String[]{"duration", "seconds"};
                break;
            case Metrics.SLEEP_STAGE:
                hints = new String[]{"stage", "type"};
                break;
            default:
                hints = new String[]{"value"};
                break;
        }
        for (String h : hints) {
            for (String c : cols) {
                if (c.toLowerCase(Locale.US).contains(h)) return c;
            }
        }
        return null;
    }

    private static long normalize(String metric, long v) {
        if (Metrics.SPO2.equals(metric) && v > 1000) return v / 100;
        if (Metrics.HR.equals(metric) && v > 1000) return v / 100;
        return v;
    }

    /**
     * 诊断：列出 Health Connect 所有表名和列。
     */
    static String dumpSchema(Context ctx) {
        if (!Root.available()) return "root 不可用";
        if (!Root.exists(DB_PATH)) return "Health Connect 数据库不存在";

        File local = Root.copyToCache(ctx, DB_PATH);
        if (local == null) return "拷贝失败";

        StringBuilder sb = new StringBuilder();
        sb.append("数据库：").append(DB_PATH).append('\n');
        sb.append("大小：").append(local.length() / 1024).append(" KB\n\n");

        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = SQLiteDatabase.openDatabase(local.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);

            List<String> tables = new ArrayList<>();
            c = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && n.startsWith("local_")) tables.add(n);
            }
            c.close();
            c = null;

            for (String t : tables) {
                long rows = -1;
                Cursor cc = null;
                try {
                    cc = sql.rawQuery("SELECT COUNT(*) FROM \"" + t + "\"", null);
                    if (cc.moveToFirst()) rows = cc.getLong(0);
                } catch (Throwable ignored) {
                } finally {
                    if (cc != null) cc.close();
                }
                if (rows <= 0) continue;   // 只显示有数据的表

                StringBuilder cols = new StringBuilder();
                Cursor ci = null;
                try {
                    ci = sql.rawQuery("SELECT * FROM \"" + t + "\" LIMIT 0", null);
                    if (ci != null) {
                        String[] names = ci.getColumnNames();
                        for (int i = 0; i < Math.min(names.length, 10); i++) {
                            if (i > 0) cols.append(',');
                            cols.append(names[i]);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (ci != null) ci.close();
                }

                sb.append("▸ ").append(t).append(" (").append(rows).append(" 行)\n");
                sb.append("    ").append(cols).append('\n');
            }
        } catch (Throwable t) {
            sb.append("读库失败：").append(Logs.brief(t)).append('\n');
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
        return sb.toString();
    }
}
