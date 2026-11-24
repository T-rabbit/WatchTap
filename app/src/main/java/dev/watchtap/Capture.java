package dev.watchtap;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 隔夜报文采集器。
 *
 * 与 alarmlink.log 完全分开，原因：
 *   1. alarmlink.log 超过 512KB 会被自动删除，隔夜必然丢数据
 *   2. 采集期间产生的文件体积大（一晚可能几 MB），不该混在功能日志里
 *   3. 需要独立开关，采集完可以关掉
 *
 * 输出（都在 OPPO 健康的外部目录下）：
 *   capture/<日期>/frame.log    每行一条报文
 *   capture/<日期>/meta.log     会话/状态变化（睡眠模式切换、加密标记等）
 *
 * 单文件上限 32MB 后切分（frame.log -> frame.log.1 ...），不删除任何数据。
 */
final class Capture {

    /** 采集总开关。由 UI 的「报文采集」开关控制，存 ProviderPrefs。 */
    static final String PREF_KEY = "capture";

    static final String DIR = "capture";

    /** 采集开关：该文件存在即开启 */
    static final String MARKER = "capture_on";

    /** 单文件切分阈值 */
    private static final long SPLIT_BYTES = 32L * 1024 * 1024;

    private static final AtomicLong sFrames = new AtomicLong();
    private static final AtomicLong sBytes = new AtomicLong();
    private static volatile long sStartAt;
    private static volatile boolean sInited;
    private static volatile boolean sReported;

    private Capture() {}

    /**
     * 采集是否开启。
     *
     * 两条通路都认，任一满足即采集：
     *   1. 模块偏好 capture=true（UI 开关写入，经 HookPrefsChannel 落到
     *      宿主外部目录的 watchtap_prefs.txt，hook 进程直接可读）
     *   2. 标记文件 capture_on 存在（保留兼容，供 adb 手动开关）
     *
     * 不用 ContentProvider：hook 进程的 ContentResolver 不认
     * dev.watchtap.state 这个 authority（实测 Unknown authority）。
     */
    static boolean enabled() {
        try {
            Context c = ctx();
            if (c == null) return false;
            File base = c.getExternalFilesDir(null);
            if (base == null) return false;
            if (new File(base, MARKER).exists()) return true;
            return readPref(base);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读 watchtap_prefs.txt 里的 capture=1。 */
    private static boolean readPref(File base) {
        try {
            File f = new File(base, "watchtap_prefs.txt");
            if (!f.exists()) return false;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf('=');
                    if (i <= 0) continue;
                    if ("capture".equals(line.substring(0, i).trim())) {
                        return "1".equals(line.substring(i + 1).trim());
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 会话开始：写一行 meta，并记录起点。 */
    private static synchronized void ensureInit(Context c) {
        if (sInited) return;
        sInited = true;
        sStartAt = System.currentTimeMillis();
        meta("=== 采集会话开始 ===");
        meta("设备=" + android.os.Build.MODEL + " Android=" + android.os.Build.VERSION.RELEASE);
        meta("起始帧数=" + sFrames.get());
    }

    /** 记一条报文。dir: RECV / SEND。 */
    static void frame(String dir, int sid, int cid, int len, int encrypt, String hex) {
        if (!sReported) {
            sReported = true;
            // 用最原始的方式留痕：直接写文件，不依赖 Logs / provider
            try {
                Context c0 = ctx();
                if (c0 != null) {
                    File d0 = dayDir(c0);
                    append(d0, "meta.log", "frame() 首次被调用, enabled=" + enabled() + "\n");
                }
            } catch (Throwable ignored) {
            }
        }
        boolean on = enabled();
        if (!sReported) {
            sReported = true;
            dev.watchtap.Logs.i("Capture: enabled=" + on + " (首次检查)");
        }
        if (!on) return;
        Context c = ctx();
        if (c == null) return;
        try {
            ensureInit(c);
            long now = System.currentTimeMillis();
            long n = sFrames.incrementAndGet();

            StringBuilder sb = new StringBuilder(64 + hex.length());
            sb.append(fmtMs(now))
                    .append(' ').append(dir)
                    .append(" sid=").append(sid)
                    .append(" cid=").append(cid)
                    .append(" len=").append(len)
                    .append(" enc=").append(encrypt)
                    .append(" hex=").append(hex)
                    .append('\n');

            append(dayDir(c), "frame.log", sb.toString());
        } catch (Throwable t) {
            dev.watchtap.Logs.w("Capture.frame 失败", t);
        }
    }

    /** 记一条元信息（睡眠模式切换、加密标记变化、异常等）。 */
    static void meta(String msg) {
        try {
            Context c = ctx();
            if (c == null) return;
            append(dayDir(c), "meta.log", fmtMs(System.currentTimeMillis()) + " " + msg + "\n");
        } catch (Throwable t) {
            dev.watchtap.Logs.w("Capture.meta 失败", t);
        }
    }

    /** 采集统计，供 UI 显示。 */
    static String stats() {
        long n = sFrames.get();
        long b = sBytes.get();
        if (sStartAt == 0) return "未开始";
        long mins = (System.currentTimeMillis() - sStartAt) / 60000L;
        return n + " 帧 / " + (b / 1024 / 1024) + "MB / " + mins + " 分钟";
    }

    // ------------------------------------------------------------ 内部

    private static File dayDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(new File(base, DIR), day());
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String day() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static String fmtMs(long ms) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(ms));
    }

    /** 追加写；超过阈值就切分，绝不删除。 */
    private static synchronized void append(File dir, String name, String text) {
        try {
            File f = new File(dir, name);
            if (f.length() > SPLIT_BYTES) {
                int i = 1;
                while (new File(dir, name + "." + i).exists()) i++;
                f.renameTo(new File(dir, name + "." + i));
                f = new File(dir, name);
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(text);
            } finally {
                w.close();
            }
            sBytes.addAndGet(text.length());
        } catch (Throwable t) {
            dev.watchtap.Logs.w("Capture.append 失败", t);
        }
    }

    private static Context ctx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return ((Context) app).getApplicationContext();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
