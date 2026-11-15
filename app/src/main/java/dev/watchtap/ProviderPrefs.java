package dev.watchtap;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * hook 进程要读的开关，统一存在这里。
 *
 * 为什么需要它：
 *   hook 进程（com.heytap.health）跨 uid 读不到本 App 的 SharedPreferences 文件，
 *   而 Root.exec("su") 在这个 uid 上不一定被授权 —— 两条老路都不可靠。
 *
 *   provider（StateStore）跑在本 App 内，同 uid 能直接读 SharedPreferences，
 *   所以开关值写在这里，hook 进程通过 provider 的 get 拿到。
 */
final class ProviderPrefs {

    /** provider 与 UI 共用的文件名 */
    static final String NAME = "provider_flags";

    private ProviderPrefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static void setBoolean(Context c, String key, boolean v) {
        try {
            get(c).edit().putBoolean(key, v).apply();
        } catch (Throwable ignored) {
        }
    }

    static boolean getBoolean(Context c, String key, boolean def) {
        try {
            return get(c).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
