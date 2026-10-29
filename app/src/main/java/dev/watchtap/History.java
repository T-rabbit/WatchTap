package dev.watchtap;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 指标历史存储。
 *
 * ==== 文件格式 ====
 * 按天分文件：watchtap-data-2026-10-29.csv
 *
 * 每行：毫秒时间戳,指标id,值
 *
 * 例：
 *   1793247651000,hr,72
 *   1793247651000,steps,1024
 *   1793247712000,sleepStage,2
 *
 * 用 CSV 而不是数据库，理由：
 *   - 可以 adb pull 出来直接用表格软件打开
 *   - 追加写入不需要事务，不会因为断电损坏
 *   - 用户能看懂、能自己删
 *
 * ==== 容量控制 ====
 * 每天一个文件。启动时清理 30 天前的旧文件。
 */
final class History {

    private static final String PREFIX = "watchtap-data-";
    private static final int KEEP_DAYS = 30;

    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private History() {}

    private static File dir(Context ctx) {
        File d = new File(ctx.getExternalFilesDir(null), "history");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileOf(Context ctx, long ts) {
        return new File(dir(ctx), PREFIX + DAY.format(new Date(ts)) + ".csv");
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 追加一条记录。
     *
     * @param ts   毫秒时间戳
     * @param id   指标 id（见 Metrics）
     * @param value 值
     */
    static void append(Context ctx, long ts, String id, long value) {
        if (ctx == null || ts <= 0 || id == null) return;
        try {
            File f = fileOf(ctx, ts);
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(ts + "," + id + "," + value + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Logs.w("写入历史失败", t);
        }
    }

    /** 批量写（用于一次性导入）。 */
    static void appendAll(Context ctx, List<long[]> rows, List<String> ids) {
        if (ctx == null || rows == null) return;
        try {
            long now = System.currentTimeMillis();
            File f = fileOf(ctx, now);
            FileWriter w = new FileWriter(f, true);
            try {
                for (int i = 0; i < rows.size(); i++) {
                    long[] r = rows.get(i);
                    w.write(r[0] + "," + ids.get(i) + "," + r[1] + "\n");
                }
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Logs.w("批量写入历史失败", t);
        }
    }

    // ---------------------------------------------------------------- 读取

    static final class Point {
        final long ts;
        final long value;

        Point(long ts, long value) {
            this.ts = ts;
            this.value = value;
        }
    }

    /**
     * 读某个指标在时间范围内的数据。
     *
     * @param id     指标 id
     * @param fromMs 起始（含），0 表示不限
     * @param toMs   结束（含），0 表示不限
     */
    static List<Point> read(Context ctx, String id, long fromMs, long toMs) {
        List<Point> out = new ArrayList<>();
        if (ctx == null || id == null) return out;
        if (toMs <= 0) toMs = System.currentTimeMillis();
        if (fromMs <= 0) fromMs = toMs - 24L * 3600 * 1000;

        // 跨天时逐日读
        List<File> files = filesBetween(ctx, fromMs, toMs);
        for (File f : files) {
            readFile(f, id, fromMs, toMs, out);
        }
        return out;
    }

    /** 读所有指标最新值。 */
    static List<Point> readLatest(Context ctx, String id) {
        List<Point> pts = read(ctx, id, 0, 0);
        if (pts.isEmpty()) return pts;
        return pts.subList(Math.max(0, pts.size() - 1), pts.size());
    }

    private static void readFile(File f, String id, long from, long to, List<Point> out) {
        if (!f.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                int c1 = line.indexOf(',');
                if (c1 <= 0) continue;
                int c2 = line.indexOf(',', c1 + 1);
                if (c2 <= 0) continue;

                if (!id.equals(line.substring(c1 + 1, c2))) continue;

                try {
                    long ts = Long.parseLong(line.substring(0, c1));
                    if (ts < from || ts > to) continue;
                    long v = Long.parseLong(line.substring(c2 + 1));
                    out.add(new Point(ts, v));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable t) {
            Logs.w("读历史失败：" + f.getName(), t);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 列出范围内的日文件。 */
    private static List<File> filesBetween(Context ctx, long from, long to) {
        List<File> out = new ArrayList<>();
        File d = dir(ctx);
        File[] all = d.listFiles();
        if (all == null) return out;

        for (File f : all) {
            String n = f.getName();
            if (!n.startsWith(PREFIX) || !n.endsWith(".csv")) continue;
            String day = n.substring(PREFIX.length(), n.length() - 4);
            try {
                long t = DAY.parse(day).getTime();
                // 文件覆盖当天 00:00 ~ 次日 00:00
                if (t + 24L * 3600 * 1000 >= from && t <= to) {
                    out.add(f);
                }
            } catch (Throwable ignored) {
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    // ---------------------------------------------------------------- 维护

    /** 清理超过保留期的文件。启动时调一次。 */
    static void cleanup(Context ctx) {
        try {
            File d = dir(ctx);
            File[] all = d.listFiles();
            if (all == null) return;

            long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000;
            int removed = 0;
            for (File f : all) {
                if (f.lastModified() < cutoff && f.delete()) removed++;
            }
            if (removed > 0) Logs.i("清理了 " + removed + " 个过期历史文件");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自检：验证存储链路。
     *
     * 依次检查：目录可写 → 能追加 → 能读回 → 统计各指标条数。
     * 用来定位「UI 显示 0 数据」到底是没采集、还是没写入、还是没读出。
     */
    static String selfCheck(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx == null) return "拿不到上下文";

        try {
            File d = dir(ctx);
            sb.append("存储目录：").append(d.getAbsolutePath()).append('\n');
            sb.append("目录存在：").append(d.exists()).append('\n');
            sb.append("可写测试：").append(d.canWrite()).append("\n\n");

            // 写入测试
            long now = System.currentTimeMillis();
            append(ctx, now, "_selftest", 1);
            File f = fileOf(ctx, now);
            sb.append("当前文件：").append(f.getName()).append('\n');
            sb.append("文件存在：").append(f.exists())
                    .append("，大小 ").append(f.length()).append(" 字节\n\n");

            // 读回测试
            List<Point> back = read(ctx, "_selftest", 0, 0);
            sb.append("读回测试：").append(back.isEmpty() ? "失败（写入后读不到）" : "成功")
                    .append('\n');

            // 清理测试数据
            try {
                f.delete();
            } catch (Throwable ignored) {
            }

            // 已有文件列表
            File[] all = d.listFiles();
            sb.append("\n已有文件：").append(all == null ? 0 : all.length).append(" 个\n");
            if (all != null) {
                int shown = 0;
                for (File x : all) {
                    if (!x.getName().endsWith(".csv")) continue;
                    sb.append("  ").append(x.getName())
                            .append("  ").append(x.length() / 1024).append("KB\n");
                    if (++shown >= 5) break;
                }
            }

            // 各指标条数
            sb.append("\n各指标记录数（近 7 天）：\n");
            long from = now - 7L * 24 * 3600 * 1000;
            for (Metrics.Def def : Metrics.all()) {
                int n = read(ctx, def.id, from, now).size();
                sb.append("  ").append(def.name).append("：").append(n).append('\n');
            }

            sb.append("\n总计：").append(countAll(ctx)).append(" 条\n");
        } catch (Throwable t) {
            sb.append("自检异常：").append(Logs.brief(t));
        }
        return sb.toString();
    }

    /** 统计总记录数，给 UI 显示。 */
    static int countAll(Context ctx) {
        int n = 0;
        try {
            File d = dir(ctx);
            File[] all = d.listFiles();
            if (all == null) return 0;
            for (File f : all) {
                if (!f.getName().endsWith(".csv")) continue;
                BufferedReader r = new BufferedReader(new FileReader(f));
                try {
                    while (r.readLine() != null) n++;
                } finally {
                    r.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }
}
