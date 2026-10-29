package dev.watchtap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 跨进程通道 + 日志落盘 + 勿扰执行。
 *
 * ==== 为什么勿扰必须在这里执行 ====
 * hook 进程的身份是 com.heytap.health，它没有 ACCESS_NOTIFICATION_POLICY 权限，
 * 调 setInterruptionFilter 会抛 SecurityException。
 * 本 provider 跑在 dev.watchtap 进程，才有权限。
 */
public class StateStore extends ContentProvider {

    public static final String AUTHORITY = "dev.watchtap.state";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_PUT = "put";
    public static final String METHOD_GET = "get";
    public static final String METHOD_TOGGLE_DND = "toggleDnd";
    public static final String METHOD_LOG = "log";
    /** 诊断：逐条测试勿扰写入路径，返回每一步结果 */
    public static final String METHOD_PROBE_DND = "probeDnd";

    // ---- 状态键 ----
    public static final String K_SLEEP_STATE = "sleepState";
    public static final String K_SLEEP_TS = "sleepTs";
    public static final String K_SLEEP_MODE = "sleepMode";
    public static final String K_SLEEP_MODE_TS = "sleepModeTs";
    /** 手表睡眠模式最后一次「上报」的时间。用于区分「当前值」和「上次值」 */
    public static final String K_SLEEP_MODE_AT = "sleepModeAt";
    public static final String K_ZEN = "zen";
    public static final String K_ZEN_TS = "zenTs";
    public static final String K_UPDATED = "updated";
    public static final String K_COUNTS = "counts";
    public static final String K_LAST_MSG = "lastMsg";

    static final String PREF = "watchtap_state";
    static final String LOGFILE = "watchtap.log";

    /** UI 可见的最近日志（环形，最多 60 条） */
    private static final int LOG_KEEP = 60;
    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();

    private static volatile StateStore sInstance;

    @Override
    public boolean onCreate() {
        sInstance = this;
        Logs.setLocal(true);
        return true;
    }

    private SharedPreferences pref() {
        Context c = getContext();
        if (c == null) return null;
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            return handle(method, extras);
        } catch (Throwable t) {
            Logs.e("provider call failed: " + method, t);
            return Bundle.EMPTY;
        }
    }

    private Bundle handle(String method, Bundle extras) {
        SharedPreferences p = pref();
        if (p == null) return Bundle.EMPTY;

        if (METHOD_LOG.equals(method) && extras != null) {
            String line = extras.getString("line");
            if (line != null) writeLog(line);
            return Bundle.EMPTY;
        }

        if (METHOD_PUT.equals(method) && extras != null) {
            SharedPreferences.Editor e = p.edit();
            putIntIfPresent(extras, e, K_SLEEP_STATE);
            putLongIfPresent(extras, e, K_SLEEP_TS);
            putIntIfPresent(extras, e, K_SLEEP_MODE);
            putLongIfPresent(extras, e, K_SLEEP_MODE_TS);
            putLongIfPresent(extras, e, K_SLEEP_MODE_AT);
            putIntIfPresent(extras, e, K_ZEN);
            putLongIfPresent(extras, e, K_ZEN_TS);
            putLongIfPresent(extras, e, K_UPDATED);
            if (extras.containsKey(K_COUNTS)) {
                e.putString(K_COUNTS, safe(extras.getString(K_COUNTS)));
            }
            if (extras.containsKey(K_LAST_MSG)) {
                e.putString(K_LAST_MSG, safe(extras.getString(K_LAST_MSG)));
            }
            e.apply();
            return Bundle.EMPTY;
        }

        if (METHOD_GET.equals(method)) {
            Bundle b = new Bundle();
            b.putInt(K_SLEEP_STATE, p.getInt(K_SLEEP_STATE, -1));
            b.putLong(K_SLEEP_TS, p.getLong(K_SLEEP_TS, 0));
            b.putInt(K_SLEEP_MODE, p.getInt(K_SLEEP_MODE, -1));
            b.putLong(K_SLEEP_MODE_TS, p.getLong(K_SLEEP_MODE_TS, 0));
            b.putLong(K_SLEEP_MODE_AT, p.getLong(K_SLEEP_MODE_AT, 0));
            b.putInt(K_ZEN, p.getInt(K_ZEN, -1));
            b.putLong(K_ZEN_TS, p.getLong(K_ZEN_TS, 0));
            b.putLong(K_UPDATED, p.getLong(K_UPDATED, 0));
            b.putString(K_COUNTS, p.getString(K_COUNTS, ""));
            b.putString(K_LAST_MSG, p.getString(K_LAST_MSG, ""));
            b.putString("logs", recentLogs());
            b.putBoolean("dndAccess", Dnd.hasAccess(getContext()));
            return b;
        }

        if (METHOD_PROBE_DND.equals(method)) {
            Bundle r = new Bundle();
            r.putString("report", Dnd.probe(getContext()));
            return r;
        }

        if (METHOD_TOGGLE_DND.equals(method) && extras != null) {
            boolean on = extras.getBoolean("on", false);
            Context c = getContext();

            boolean access = Dnd.hasAccess(c);
            int before = Dnd.zen(c);

            boolean ok = Dnd.set(c, on);

            int after = Dnd.zen(c);
            Logs.fire("DND.toggle", "req=" + on
                    + " access=" + access
                    + " before=" + before
                    + " after=" + after
                    + " ok=" + ok
                    + (ok && before == after ? " [NO_CHANGE]" : ""));

            Bundle r = new Bundle();
            // 以实际 zen_mode 变化为准，而不是 set() 的返回值：
            // setInterruptionFilter 不抛异常不代表真的生效。
            r.putBoolean("ok", ok && (on ? after > 0 : after == 0));
            r.putInt("zen", after);
            r.putBoolean("access", access);
            return r;
        }

        return Bundle.EMPTY;
    }

    // ---------------------------------------------------------------- 日志

    private void writeLog(String line) {
        synchronized (LOGS) {
            LOGS.addLast(line);
            while (LOGS.size() > LOG_KEEP) LOGS.removeFirst();
        }
        try {
            Context c = getContext();
            if (c == null) return;
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, LOGFILE);
            if (f.length() > 4L * 1024 * 1024) {
                // 超过 4MB 截断重来，避免无限增长
                f.delete();
            }
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            try {
                w.write(line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    static String recentLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOGS) {
            int n = 0;
            for (String s : LOGS) {
                if (n++ > 0) sb.append('\n');
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** UI 进程自己记一条（不经过 provider，直接进环形缓冲 + 文件）。 */
    static void logLocal(Context ctx, String msg) {
        // msg 已经是完整行时（带时间戳）直接透传，避免双时间戳
        String line = msg.startsWith("0") || msg.matches("^\\d{2}-\\d{2} .*")
                ? msg
                : new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date()) + " " + msg;
        synchronized (LOGS) {
            LOGS.addLast(line);
            while (LOGS.size() > LOG_KEEP) LOGS.removeFirst();
        }
        try {
            Context c = ctx;
            if (c == null) {
                // provider 进程内调用时传 null，用自己的 Context
                c = sInstance == null ? null : sInstance.getContext();
            }
            if (c == null) return;
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(dir, LOGFILE), true);
            try {
                w.write(line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void putIntIfPresent(Bundle b, SharedPreferences.Editor e, String key) {
        if (b.containsKey(key)) e.putInt(key, b.getInt(key, 0));
    }

    private static void putLongIfPresent(Bundle b, SharedPreferences.Editor e, String key) {
        if (b.containsKey(key)) e.putLong(key, b.getLong(key, 0L));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
