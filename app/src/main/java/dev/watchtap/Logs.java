package dev.watchtap;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一日志。
 *
 * ==== 设计原则 ====
 * 日志是给人看的，不是给机器看的。所以：
 *   1. 用中文短句，不用 "STATE.sleepMode => value=1 src=push"
 *   2. 时间只保留 HH:mm:ss，不显示日期（当天日志）
 *   3. 三个级别用不同标记：·  普通   ! 注意   × 错误
 *   4. 关键事件加 emoji 便于扫视
 *
 * 同时仍然输出到 logcat（保留 TAG 便于 adb 抓取）。
 */
final class Logs {

    private static final String TAG = "WatchTap";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    /** provider 进程内写日志时置 true，避免自己调自己 */
    private static volatile boolean sLocal;

    private Logs() {}

    static void setLocal(boolean local) {
        sLocal = local;
    }

    // ---------------------------------------------------------------- 各级别

    /** 普通信息 */
    static void i(String msg) {
        emit("·", msg);
    }

    /** 注意事项（黄色） */
    static void w(String msg, Throwable t) {
        emit("!", msg + (t == null ? "" : "（" + brief(t) + "）"));
    }

    /** 错误（红色） */
    static void e(String msg, Throwable t) {
        emit("×", msg + (t == null ? "" : "（" + brief(t) + "）"));
    }

    /**
     * 功能触发记录。
     *
     * @param feature 中文功能名，如「睡眠模式」「勿扰」
     * @param detail  中文说明
     */
    static void fire(String feature, String detail) {
        emit("·", feature + "：" + detail);
    }

    /** 带 emoji 的关键事件 */
    static void key(String emoji, String msg) {
        emit(emoji, msg);
    }

    // ---------------------------------------------------------------- 实现

    private static void emit(String level, String msg) {
        String line = FMT.format(new Date()) + " " + level + " " + msg;

        // logcat（保留原始文本，便于 grep）
        try {
            if ("×".equals(level)) Log.e(TAG, msg);
            else if ("!".equals(level)) Log.w(TAG, msg);
            else Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }

        if (sLocal) {
            StateStore.logLocal(null, line);
            return;
        }
        try {
            Context c = ctx();
            if (c == null) return;
            Bundle b = new Bundle();
            b.putString("line", line);
            c.getContentResolver().call(StateStore.URI, StateStore.METHOD_LOG, null, b);
        } catch (Throwable ignored) {
        }
    }

    /** 异常转人话：优先取 message，其次类名 */
    static String brief(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        if (m != null && !m.isEmpty()) {
            // 去掉冗长的包名前缀
            int idx = m.indexOf(": ");
            if (idx > 0 && idx < 60) return m.substring(idx + 2);
            return m.length() > 80 ? m.substring(0, 80) + "…" : m;
        }
        return t.getClass().getSimpleName();
    }

    static String describe(Throwable t) {
        return brief(t);
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
