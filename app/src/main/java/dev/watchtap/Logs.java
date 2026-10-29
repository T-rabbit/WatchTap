package dev.watchtap;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一日志：同时输出到 logcat 和模块日志文件，并在 UI 上可见。
 *
 * ==== 为什么需要 ====
 * 调试时经常要回答「这个功能到底有没有被触发」。只看 UI 看不出来，
 * 只看 logcat 又可能被系统限制。所以三路输出：
 *   1. logcat（adb logcat -s WatchTap）
 *   2. 文件（/sdcard/Android/data/dev.watchtap/files/watchtap.log）
 *   3. UI 底部日志区（最后 60 条）
 *
 * ==== 进程差异 ====
 * hook 进程（com.heytap.health）里的日志通过 provider 转发到模块进程统一落盘，
 * 这样只有一个日志文件，不分散。
 * provider 进程自己写日志时跳过转发（否则无限递归）。
 */
final class Logs {

    private static final String TAG = "WatchTap";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** provider 进程内写日志时置 true，避免自己调自己 */
    private static volatile boolean sLocal;

    private Logs() {}

    static void setLocal(boolean local) {
        sLocal = local;
    }

    // ---------------------------------------------------------------- 各级别

    static void i(String msg) {
        emit("I", msg);
    }

    static void w(String msg, Throwable t) {
        emit("W", msg + (t == null ? "" : " | " + describe(t)));
    }

    static void e(String msg, Throwable t) {
        emit("E", msg + (t == null ? "" : " | " + describe(t)));
    }

    /** 功能触发记录：统一格式，便于 grep */
    static void fire(String feature, String detail) {
        emit("F", feature + " => " + detail);
    }

    // ---------------------------------------------------------------- 实现

    private static void emit(String level, String msg) {
        String line = FMT.format(new Date()) + " " + level + " " + msg;

        // 1. logcat
        try {
            if ("E".equals(level)) Log.e(TAG, msg);
            else if ("W".equals(level)) Log.w(TAG, msg);
            else Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }

        // 2. 落盘
        if (sLocal) {
            // provider 进程：直接写本地缓冲 + 文件，不走 call（否则递归）
            StateStore.logLocal(null, line);
            return;
        }
        // hook 进程：转发给 provider 落盘
        try {
            Context c = ctx();
            if (c == null) return;
            Bundle b = new Bundle();
            b.putString("line", line);
            c.getContentResolver().call(StateStore.URI, StateStore.METHOD_LOG, null, b);
        } catch (Throwable ignored) {
        }
    }

    static String describe(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
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
