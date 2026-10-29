package dev.watchtap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 跨进程状态通道。
 *
 * ==== 为什么需要它 ====
 * 模块跑在 com.heytap.health 进程（hook 进程），UI 跑在 dev.watchtap 进程。
 * 两个进程内存不共享，所以状态必须落地到一个双方都能访问的地方。
 *
 * 早期版本用的是宿主外部文件，但遇到两个问题：
 *   1. 权限（目录是 drwxrws---，只有 u0_a324 能进）
 *   2. 时机（onPackageLoaded 阶段拿不到 Context）
 *
 * 改为 ContentProvider：
 *   - authority 归本模块所有，双方都能访问
 *   - 用 call() + Bundle 传结构化数据，无需序列化
 *   - 底层仍是 SharedPreferences，但读写都在 provider 进程内完成
 *     （hook 进程通过 call 请求 provider 代写，避免跨进程文件锁问题）
 */
public class StateStore extends ContentProvider {

    public static final String AUTHORITY = "dev.watchtap.state";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_PUT = "put";
    public static final String METHOD_GET = "get";
    public static final String METHOD_TOGGLE_DND = "toggleDnd";

    // ---- 状态键 ----
    public static final String K_HR = "hr";
    public static final String K_HR_TS = "hrTs";
    public static final String K_SLEEP_STATE = "sleepState";
    public static final String K_SLEEP_TS = "sleepTs";
    public static final String K_SLEEP_MODE = "sleepMode";
    public static final String K_SLEEP_MODE_TS = "sleepModeTs";
    public static final String K_ZEN = "zen";
    public static final String K_ZEN_TS = "zenTs";
    public static final String K_UPDATED = "updated";
    public static final String K_COUNTS = "counts";
    public static final String K_LAST_MSG = "lastMsg";

    static final String PREF = "watchtap_state";

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences pref() {
        Context c = getContext();
        if (c == null) return null;
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        SharedPreferences p = pref();
        if (p == null) return Bundle.EMPTY;

        if (METHOD_PUT.equals(method) && extras != null) {
            SharedPreferences.Editor e = p.edit();
            // 逐键用类型化 getter，避免 Bundle.get() 的 Object 转换问题。
            // 键集合是固定的，直接按已知类型取即可。
            putIntIfPresent(extras, e, K_HR);
            putLongIfPresent(extras, e, K_HR_TS);
            putIntIfPresent(extras, e, K_SLEEP_STATE);
            putLongIfPresent(extras, e, K_SLEEP_TS);
            putIntIfPresent(extras, e, K_SLEEP_MODE);
            putLongIfPresent(extras, e, K_SLEEP_MODE_TS);
            putIntIfPresent(extras, e, K_ZEN);
            putLongIfPresent(extras, e, K_ZEN_TS);
            putLongIfPresent(extras, e, K_UPDATED);
            if (extras.containsKey(K_COUNTS)) {
                String v = extras.getString(K_COUNTS);
                e.putString(K_COUNTS, v == null ? "" : v);
            }
            if (extras.containsKey(K_LAST_MSG)) {
                String v = extras.getString(K_LAST_MSG);
                e.putString(K_LAST_MSG, v == null ? "" : v);
            }
            e.apply();
            return Bundle.EMPTY;
        }

        if (METHOD_GET.equals(method)) {
            Bundle b = new Bundle();
            b.putInt(K_HR, p.getInt(K_HR, -1));
            b.putLong(K_HR_TS, p.getLong(K_HR_TS, 0));
            b.putInt(K_SLEEP_STATE, p.getInt(K_SLEEP_STATE, -1));
            b.putLong(K_SLEEP_TS, p.getLong(K_SLEEP_TS, 0));
            b.putInt(K_SLEEP_MODE, p.getInt(K_SLEEP_MODE, -1));
            b.putLong(K_SLEEP_MODE_TS, p.getLong(K_SLEEP_MODE_TS, 0));
            b.putInt(K_ZEN, p.getInt(K_ZEN, -1));
            b.putLong(K_ZEN_TS, p.getLong(K_ZEN_TS, 0));
            b.putLong(K_UPDATED, p.getLong(K_UPDATED, 0));
            b.putString(K_COUNTS, p.getString(K_COUNTS, ""));
            b.putString(K_LAST_MSG, p.getString(K_LAST_MSG, ""));
            return b;
        }

        if (METHOD_TOGGLE_DND.equals(method) && extras != null) {
            boolean on = extras.getBoolean("on", false);
            boolean ok = Dnd.set(getContext(), on);
            Bundle r = new Bundle();
            r.putBoolean("ok", ok);
            return r;
        }

        return Bundle.EMPTY;
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
