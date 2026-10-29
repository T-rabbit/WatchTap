package dev.watchtap;

import android.content.Context;
import android.os.Bundle;

import java.io.File;

/**
 * 把偏好推给 hook 进程的通道。
 *
 * ==== 为什么需要它 ====
 * Android 11+ 的限制：dev.watchtap 写不进 com.heytap.health 的目录，
 * 反过来也一样。所以 UI 改了开关，hook 进程没法直接看到。
 *
 * ==== 做法 ====
 * UI 改开关时，除了写自己的文件，还把这个值写到
 * `/sdcard/Android/data/com.heytap.health/files/` 下 —— 这需要 root。
 *
 * 没 root 时退回另一条路：hook 进程每次读偏好时，
 * 会通过 root cat 去读模块的文件。所以只要一边有 root 就能同步。
 *
 * 如果两边都没有 root，最后的手段是：hook 进程启动时
 * 读一次 provider（模块进程那时通常还活着），之后用内存值。
 */
final class HookPrefsChannel {

    private static final String HOOK_FILE =
            "/sdcard/Android/data/com.heytap.health/files/watchtap_prefs.txt";

    private HookPrefsChannel() {}

    /**
     * 把偏好写到 hook 进程能读到的位置。
     *
     * @return 是否成功
     */
    static boolean push(String key, boolean value) {
        // 1. 有 root：直接写进 hook 进程的目录
        if (Root.available()) {
            String line = key + "=" + (value ? "1" : "0");
            // 先读出现有内容，替换目标键，再写回
            String cur = Root.exec("cat '" + HOOK_FILE + "' 2>/dev/null", 4000);
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;

            for (String l : cur.split("\n")) {
                int i = l.indexOf('=');
                if (i <= 0) continue;
                String k = l.substring(0, i).trim();
                if (k.equals(key)) {
                    sb.append(line).append('\n');
                    replaced = true;
                } else {
                    sb.append(l.trim()).append('\n');
                }
            }
            if (!replaced) sb.append(line).append('\n');

            String content = sb.toString().replace("'", "'\\''");
            String cmd = "mkdir -p /sdcard/Android/data/com.heytap.health/files"
                    + " && printf '%s' '" + content + "' > '" + HOOK_FILE + "'"
                    + " && chmod 666 '" + HOOK_FILE + "'";
            String out = Root.exec(cmd, 8000);

            // 验证
            String check = Root.exec("cat '" + HOOK_FILE + "' 2>/dev/null", 4000);
            if (check.contains(key + "=")) {
                Logs.fire("偏好", "已同步到 hook 进程：" + key + "=" + value);
                return true;
            }
            Logs.w("偏好同步失败：" + out.trim(), null);
        }

        // 2. 没 root：试 ContentProvider（hook 进程若还活着能收到）
        try {
            Context c = ctx();
            if (c != null) {
                Bundle b = new Bundle();
                b.putString("key", key);
                b.putBoolean("value", value);
                Bundle r = c.getContentResolver().call(
                        StateStore.URI, StateStore.METHOD_APPLY_PREFS, null, b);
                if (r != null && r.getBoolean("ok", false)) {
                    Logs.fire("偏好", "已通过 provider 同步：" + key);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        Logs.i("偏好未能同步到 hook 进程（下次启动时读取）");
        return false;
    }

    private static Context ctx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return ((Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
