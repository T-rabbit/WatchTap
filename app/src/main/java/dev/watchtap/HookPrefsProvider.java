package dev.watchtap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 注册在 hook 进程（com.heytap.health）里的 provider。
 *
 * ==== 为什么要它 ====
 * UI 改了开关，需要把新值告诉 hook 进程。但两个进程的存储目录互相隔离，
 * 写不进去。
 *
 * 这个 provider 注册在 hook 进程里，UI 通过
 * `content://com.heytap.health.watchtap_prefs` 调用它，
 * 它就在 hook 进程内把值写进 hook 进程自己的文件。
 *
 * 这样就绕开了跨应用存储限制，也不需要 root。
 *
 * ==== 注册方式 ====
 * 在 AndroidManifest 里声明，并指定 process 为 com.heytap.health。
 * 但模块的 manifest 不会合并进宿主，所以这个 provider 实际不会被系统注册。
 *
 * 因此本类还有第二个用途：**作为 hook 进程内的直接调用入口**。
 * Main 在 onPackageLoaded 时不需要它；它主要由 State 在
 * hook 进程内直接调用 apply() 完成同样的事。
 *
 * 保留 ContentProvider 形态是为了将来如果有办法注入 manifest 时能直接用。
 */
public class HookPrefsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.heytap.health.watchtap_prefs";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!StateStore.METHOD_APPLY_PREFS.equals(method) || extras == null) {
            return Bundle.EMPTY;
        }
        String key = extras.getString("key");
        boolean value = extras.getBoolean("value", false);
        boolean ok = apply(getContext(), key, value);

        Bundle r = new Bundle();
        r.putBoolean("ok", ok);
        return r;
    }

    /**
     * 把偏好写进当前进程的存储。hook 进程和模块进程都能调用。
     *
     * @return 是否成功
     */
    static boolean apply(Context ctx, String key, boolean value) {
        if (ctx == null || key == null) return false;
        try {
            Prefs.setBool(ctx, key, value);

            // 同时更新内存缓存，让 State 立即可见
            if (Prefs.K_MUTE.equals(key)) {
                State.setMutePref(value);
            } else if (Prefs.K_NOTIFY.equals(key)) {
                State.setNotifyPref(value);
            }
            Logs.fire("偏好", "已更新 " + key + "=" + value);
            return true;
        } catch (Throwable t) {
            Logs.w("更新偏好失败", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 占位

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
