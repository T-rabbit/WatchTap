package dev.watchtap;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 健康数据采集。
 *
 * ==== 策略 ====
 * OPPO 健康把数据分两条路：
 *   1. 实时推送（心率）—— 从 BLE 消息里拿
 *   2. 同步入库（步数/血氧/睡眠等）—— 数据先进 App 的数据库，UI 再读
 *
 * 第 1 条我们已经在 Main 里 hook 了。第 2 条没法靠 hook 单个方法拿到
 * （写入点分散在几十个 Repository 里），所以改用「读数据库」的方式。
 *
 * ==== 数据库读取 ====
 * OPPO 健康的数据存在 /data/data/com.heytap.health/databases/ 下。
 * 模块的 hook 进程就是 com.heytap.health 本身，有权限直接读。
 *
 * 但表结构随版本变化，写死 SQL 很容易失效。所以这里做的是：
 *   - 枚举所有 .db 文件
 *   - 每个库里列出含关键词的表（step/calorie/spo2/oxygen/sleep/sport）
 *   - 用通用的方式取最新一行
 *
 * 读不到时只记日志，不影响其他功能。
 */
final class HealthHook {

    /** 表名关键词 → 指标 id */
    private static final String[][] TABLE_HINTS = {
            {"step", Metrics.STEPS},
            {"calorie", Metrics.CALORIES},
            {"distance", Metrics.STEPS},          // 距离表通常和步数一起
            {"spo2", Metrics.SPO2},
            {"oxygen", Metrics.SPO2},
            {"blood_oxygen", Metrics.SPO2},
            {"sleep", Metrics.SLEEP_STAGE},
            {"sport", Metrics.EXERCISE_MIN},
            {"exercise", Metrics.EXERCISE_MIN},
            {"activity", Metrics.ACTIVE_COUNT},
            {"mind", Metrics.MIND},
            {"pressure", Metrics.MIND},
    };

    private HealthHook() {}

    /**
     * 安装采集。
     *
     * 这里 hook 的是 App 的数据同步完成回调，借此在数据更新后读一次库。
     * 找不到就退回定时轮询。
     */
    static void install(XposedInterface api, ClassLoader cl) {
        boolean hooked = hookSyncFinished(api, cl);
        if (!hooked) {
            Logs.i("未找到数据同步回调，改用定时读取");
        }
        startPolling();
    }

    /**
     * hook 数据同步完成的回调。
     *
     * 常见类名候选（不同版本可能不同），逐个尝试。
     */
    private static boolean hookSyncFinished(XposedInterface api, ClassLoader cl) {
        String[] candidates = {
                "com.heytap.device.data.sporthealth.repository.SleepStateRepository$Companion",
                "com.heytap.health.devicemanager.btclient.BTClient",
        };
        // BTClient 已在 Main 里 hook，这里只补睡眠库的同步点
        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (!"reportSleepState".equals(m.getName())) continue;
                    api.hook(m).intercept(chain -> {
                        Object r = chain.proceed();
                        // 睡眠数据入库后再读库
                        pollOnce();
                        return r;
                    });
                    Logs.i("数据同步监听已就位");
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void startPolling() {
        final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
        // 首次延迟 5 秒（等 App 初始化完），之后每 3 分钟读一次
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollOnce();
                h.postDelayed(this, 3 * 60 * 1000L);
            }
        }, 5000L);
        Logs.i("已启动定时读取（每 3 分钟）");
    }

    /**
     * 自检：把 App 里所有数据库、表、列都列出来。
     *
     * 用途：采集不到数据时，先看清楚数据结构长什么样，
     * 再决定用什么表名/列名去取。
     *
     * @return 人可读的报告
     */
    static String dumpSchema() {
        android.content.Context ctx = State.ctx();
        if (ctx == null) return "拿不到 App 上下文（hook 进程没起来？）";

        StringBuilder sb = new StringBuilder();
        try {
            java.io.File dataDir = new java.io.File(ctx.getApplicationInfo().dataDir);
            sb.append("应用数据目录：").append(dataDir.getAbsolutePath()).append("\n");
            sb.append("目录存在：").append(dataDir.exists()).append("\n\n");

            // 遍历可能存数据的目录
            String[] dirs = {"databases", "files", "shared_prefs", "no_backup"};
            int totalDb = 0;

            for (String d : dirs) {
                java.io.File dir = new java.io.File(dataDir, d);
                if (!dir.exists()) {
                    sb.append("[").append(d).append("] 不存在\n");
                    continue;
                }
                java.io.File[] files = dir.listFiles();
                if (files == null) {
                    sb.append("[").append(d).append("] 无法列出\n");
                    continue;
                }

                // 只看 .db 和 .sqlite
                int shown = 0;
                for (java.io.File f : files) {
                    String n = f.getName();
                    if (!n.endsWith(".db") && !n.endsWith(".sqlite")
                            && !n.endsWith(".db3")) {
                        continue;
                    }
                    totalDb++;
                    sb.append("[").append(d).append("/").append(n)
                            .append("] ").append(f.length() / 1024).append("KB\n");
                    sb.append(dumpOneDb(f, sb));
                    if (++shown >= 8) {
                        sb.append("  …（该目录还有更多）\n");
                        break;
                    }
                }
                if (shown == 0) {
                    sb.append("[").append(d).append("] 没有数据库文件\n");
                }
                sb.append('\n');
            }

            sb.append("数据库文件总数：").append(totalDb).append("\n");
            if (totalDb == 0) {
                sb.append("\n提示：数据可能不在本地 SQLite，而是从云端拉取，\n");
                sb.append("或者存在其他格式的文件里。\n");
            }
        } catch (Throwable t) {
            sb.append("自检异常：").append(Logs.brief(t)).append('\n');
        }
        return sb.toString();
    }

    /** 列出一个库的表和列。 */
    private static String dumpOneDb(java.io.File f, StringBuilder sb) {
        android.database.sqlite.SQLiteDatabase sql = null;
        android.database.Cursor c = null;
        try {
            sql = android.database.sqlite.SQLiteDatabase.openDatabase(
                    f.getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);

            java.util.List<String> tables = new java.util.ArrayList<>();
            c = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && !n.startsWith("sqlite_") && !n.startsWith("android_")) {
                    tables.add(n);
                }
            }
            c.close();
            c = null;

            if (tables.isEmpty()) {
                sb.append("  无用户表\n");
                return "";
            }

            for (String t : tables) {
                // 行数
                long rows = -1;
                android.database.Cursor cc = null;
                try {
                    cc = sql.rawQuery("SELECT COUNT(*) FROM " + t, null);
                    if (cc.moveToFirst()) rows = cc.getLong(0);
                } catch (Throwable ignored) {
                } finally {
                    if (cc != null) cc.close();
                }

                // 列名
                StringBuilder cols = new StringBuilder();
                android.database.Cursor ci = null;
                try {
                    ci = sql.rawQuery("SELECT * FROM " + t + " LIMIT 0", null);
                    if (ci != null) {
                        String[] names = ci.getColumnNames();
                        for (int i = 0; i < names.length; i++) {
                            if (i > 0) cols.append(',');
                            cols.append(names[i]);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (ci != null) ci.close();
                }

                sb.append("  └ ").append(t).append(" (").append(rows).append(" 行)\n");
                if (cols.length() > 0) {
                    sb.append("      列: ").append(cols).append('\n');
                }
            }
            return "";
        } catch (Throwable t) {
            sb.append("  打不开：").append(Logs.brief(t)).append('\n');
            return "";
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
     * 读一次数据库，把能读到的指标记入历史。
     *
     * 同一指标 5 分钟内只记一次，避免刷屏。
     */
    private static void pollOnce() {
        android.content.Context ctx = State.ctx();
        if (ctx == null) return;

        try {
            java.io.File dataDir = new java.io.File(ctx.getApplicationInfo().dataDir);
            java.io.File dbDir = new java.io.File(dataDir, "databases");

            if (!dbDir.exists()) {
                Logs.w("数据采集：找不到 databases 目录（" + dbDir.getAbsolutePath() + "）", null);
                return;
            }

            java.io.File[] dbs = dbDir.listFiles();
            if (dbs == null || dbs.length == 0) {
                Logs.w("数据采集：databases 目录为空", null);
                return;
            }

            int found = 0;
            int scanned = 0;
            for (java.io.File db : dbs) {
                String n = db.getName();
                if (!n.endsWith(".db") && !n.endsWith(".sqlite")) continue;
                scanned++;
                found += readDb(ctx, db);
            }

            if (found > 0) {
                Logs.fire("数据采集", "扫描 " + scanned + " 个库，读到 " + found + " 项指标");
            } else {
                Logs.w("数据采集：扫描 " + scanned + " 个库，未匹配到任何指标表", null);
            }
        } catch (Throwable t) {
            Logs.w("读取数据库失败", t);
        }
    }

    /** 读一个数据库文件。返回读到的指标数。 */
    private static int readDb(android.content.Context ctx, java.io.File db) {
        int count = 0;
        android.database.sqlite.SQLiteDatabase sql = null;
        android.database.Cursor tables = null;
        try {
            sql = android.database.sqlite.SQLiteDatabase.openDatabase(
                    db.getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);

            // 枚举表
            java.util.List<String> names = new java.util.ArrayList<>();
            tables = sql.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'", null);
            while (tables.moveToNext()) {
                String n = tables.getString(0);
                if (n != null && !n.startsWith("sqlite_")) names.add(n);
            }
            tables.close();
            tables = null;

            for (String table : names) {
                String metric = matchMetric(table);
                if (metric == null) continue;
                if (recordLatest(ctx, sql, table, metric)) count++;
            }
        } catch (Throwable t) {
            // 某个库打不开就跳过，不影响其他
        } finally {
            if (tables != null) {
                try {
                    tables.close();
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
        return count;
    }

    private static String matchMetric(String table) {
        String t = table.toLowerCase(java.util.Locale.US);
        for (String[] h : TABLE_HINTS) {
            if (t.contains(h[0])) return h[1];
        }
        return null;
    }

    /**
     * 从表里取最新一行，找出数值列。
     *
     * 不假设列名：遍历所有列，取第一个能转成正数的整型/浮点列。
     */
    private static boolean recordLatest(android.content.Context ctx,
                                        android.database.sqlite.SQLiteDatabase sql,
                                        String table, String metric) {
        android.database.Cursor c = null;
        try {
            c = sql.rawQuery("SELECT * FROM " + table + " LIMIT 1", null);
            if (c == null || c.getCount() == 0) return false;
            String[] cols = c.getColumnNames();
            c.close();
            c = null;

            // 找时间列和值列
            String timeCol = null, valCol = null;
            for (String col : cols) {
                String l = col.toLowerCase(java.util.Locale.US);
                if (timeCol == null && (l.contains("time") || l.equals("date")
                        || l.contains("timestamp") || l.equals("ts"))) {
                    timeCol = col;
                }
            }

            String orderBy = timeCol != null ? " ORDER BY " + timeCol + " DESC" : "";
            c = sql.rawQuery("SELECT * FROM " + table + orderBy + " LIMIT 1", null);
            if (c == null || !c.moveToFirst()) return false;

            for (String col : cols) {
                if (col.equals(timeCol)) continue;
                String l = col.toLowerCase(java.util.Locale.US);
                // 值列优先带这些词
                if (l.contains("value") || l.contains("count") || l.contains("data")
                        || l.contains("level") || l.contains("rate")) {
                    valCol = col;
                    break;
                }
            }
            if (valCol == null) return false;

            int idx = c.getColumnIndex(valCol);
            if (idx < 0) return false;

            long v = 0;
            boolean got = false;
            try {
                v = c.getLong(idx);
                got = v > 0;
            } catch (Throwable ignored) {
            }
            if (!got) {
                try {
                    double d = c.getDouble(idx);
                    v = Math.round(d);
                    got = v > 0;
                } catch (Throwable ignored) {
                }
            }
            c.close();
            c = null;

            if (!got) return false;

            // 去重：同指标 5 分钟内只记一次
            if (recentlyRecorded(metric, v)) return false;

            State.record(metric, v);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final java.util.HashMap<String, Long> LAST_TS = new java.util.HashMap<>();
    private static final java.util.HashMap<String, Long> LAST_V = new java.util.HashMap<>();

    /** 同指标同值、5 分钟内不重复记录。 */
    private static boolean recentlyRecorded(String metric, long value) {
        synchronized (LAST_TS) {
            Long t = LAST_TS.get(metric);
            Long v = LAST_V.get(metric);
            long now = System.currentTimeMillis();
            if (t != null && v != null && v == value && now - t < 5 * 60 * 1000L) {
                return true;
            }
            LAST_TS.put(metric, now);
            LAST_V.put(metric, value);
            return false;
        }
    }
}
