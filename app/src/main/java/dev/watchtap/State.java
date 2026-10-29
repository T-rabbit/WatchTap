package dev.watchtap;

import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicLong;

/**
 * hook 进程侧的状态缓存 + 上报 + 自动联动。
 *
 * ==== 睡眠模式的两个时间 ====
 * 手表不是每次都上报状态，所以 UI 需要区分：
 *   sleepModeTs → 状态**发生时刻**（手表给的时间戳，用于判断数据新旧）
 *   sleepModeAt → 手机**收到时刻**（wall clock，用于显示"多久前更新"）
 *
 * UI 显示「上次记录」时用 sleepModeAt，这样用户能看出数据是不是旧的。
 */
final class State {

    private static volatile Context sCtx;

    static volatile int sleepState = -1;      // 生理睡眠：0=清醒 1=睡着
    static volatile long sleepTs = 0;

    static volatile int sleepMode = -1;       // 手表睡眠模式：0=关 1=开
    static volatile long sleepModeTs = 0;     // 手表给的时间戳
    static volatile long sleepModeAt = 0;     // 手机收到的时间

    static final AtomicLong sleepCount = new AtomicLong();
    static final AtomicLong sleepModeCount = new AtomicLong();

    static volatile String lastMsg = "";

    private State() {}

    static void attach(Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
    }

    static Context ctx() {
        if (sCtx != null) return sCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sCtx = ((Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return sCtx;
    }

    // ---------------------------------------------------------------- 写入

    static void setSleepState(int state, long tsSec) {
        sleepState = state;
        sleepTs = toMs(tsSec);
        sleepCount.incrementAndGet();
        Logs.fire("睡眠检测", state == 1 ? "检测到入睡" : "检测到清醒");
        push();
    }

    /**
     * 手表睡眠模式。
     *
     * @param on       0=关 1=开
     * @param tsSec    手表给的时间戳（可能为 0）
     * @param fromPush true=手表上报，false=App 内部读取
     */
    static void setSleepMode(int on, long tsSec, boolean fromPush) {
        if (on != 0 && on != 1) return;

        long ts = toMs(tsSec);

        // App 内部读取路径常带 ts=0（默认实例），忽略，避免把状态改成关
        if (!fromPush && ts <= 0) {
            Logs.i("忽略一条无效的睡眠模式读取（无时间戳）");
            return;
        }
        if (!fromPush && ts < sleepModeTs) {
            Logs.i("忽略过期的睡眠模式数据");
            return;
        }

        boolean changed = (sleepMode != on);

        sleepMode = on;
        if (ts > 0) sleepModeTs = ts;
        sleepModeAt = System.currentTimeMillis();
        sleepModeCount.incrementAndGet();

        String who = fromPush ? "手表上报" : "App读取";
        if (changed) {
            Logs.key(on == 1 ? "🌙" : "☀️",
                    "手表" + (on == 1 ? "进入" : "退出") + "睡眠模式（" + who + "）");
        } else {
            Logs.i("手表仍在" + (on == 1 ? "睡眠" : "普通") + "模式（" + who + "）");
        }

        push();

        if (changed) {
            autoDnd(on == 1, mutePref());
            maybeNotify(on == 1);
        }
    }

    static void noteMessage(int sid, int cid, int len) {
        lastMsg = "sid=" + sid + " cid=0x" + Integer.toHexString(cid) + " len=" + len;
    }

    // ---------------------------------------------------------------- 联动

    /**
     * 读取用户的「同步静音」偏好。
     *
     * hook 进程读不到模块进程的 SharedPreferences，
     * 所以通过 provider 的 GET 拿。
     */
    static boolean mutePref() {
        Context c = ctx();
        if (c == null) return true;
        try {
            Bundle b = c.getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_GET, null, null);
            return b == null || b.getBoolean(StateStore.K_MUTE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 读取「发送通知」偏好。 */
    static boolean notifyPref() {
        Context c = ctx();
        if (c == null) return false;
        try {
            Bundle b = c.getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_GET, null, null);
            return b != null && b.getBoolean(StateStore.K_NOTIFY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按偏好发送睡眠模式通知。
     *
     * hook 进程（com.heytap.health）发通知会显示成 OPPO 健康的通知，
     * 自动化软件不好识别。所以通过 provider 让模块进程发。
     */
    static void maybeNotify(boolean on) {
        if (!notifyPref()) {
            Logs.i("通知开关已关闭，跳过发送");
            return;
        }
        Context c = ctx();
        if (c == null) return;
        try {
            Bundle b = new Bundle();
            b.putBoolean("on", on);
            c.getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_NOTIFY, null, b);
        } catch (Throwable t) {
            Logs.e("发送通知调用失败", t);
        }
    }

    /** 手表睡眠模式变化 → 请求 provider 进程切勿扰。 */
    static void autoDnd(boolean on, boolean mute) {
        Context c = ctx();
        if (c == null) {
            Logs.e("自动联动失败：拿不到上下文", null);
            return;
        }
        try {
            Logs.fire("自动联动", (on ? "开启" : "关闭") + "勿扰"
                    + (on ? (mute ? "（同步静音）" : "（不同步静音）") : ""));
            Bundle b = new Bundle();
            b.putBoolean("on", on);
            b.putBoolean("mute", mute);
            Bundle r = c.getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_TOGGLE_DND, null, b);
            boolean ok = r != null && r.getBoolean("ok", false);
            int zen = r == null ? -1 : r.getInt("zen", -1);
            if (ok) {
                Logs.key("✓", "自动联动成功，勿扰状态：" + Dnd.describe(zen));
            } else {
                Logs.e("自动联动失败，勿扰状态：" + Dnd.describe(zen), null);
            }
        } catch (Throwable t) {
            Logs.e("自动联动调用失败", t);
        }
    }

    // ---------------------------------------------------------------- 上报

    private static volatile boolean sPushing;

    static void push() {
        if (sPushing) return;
        Context c = ctx();
        if (c == null) return;
        sPushing = true;
        try {
            Bundle b = new Bundle();
            b.putInt(StateStore.K_SLEEP_STATE, sleepState);
            b.putLong(StateStore.K_SLEEP_TS, sleepTs);
            b.putInt(StateStore.K_SLEEP_MODE, sleepMode);
            b.putLong(StateStore.K_SLEEP_MODE_TS, sleepModeTs);
            b.putLong(StateStore.K_SLEEP_MODE_AT, sleepModeAt);
            b.putInt(StateStore.K_ZEN, Dnd.zen(c));
            b.putLong(StateStore.K_ZEN_TS, System.currentTimeMillis());
            b.putLong(StateStore.K_UPDATED, System.currentTimeMillis());
            b.putString(StateStore.K_COUNTS,
                    sleepCount.get() + "/" + sleepModeCount.get());
            b.putString(StateStore.K_LAST_MSG, lastMsg);

            c.getContentResolver().call(StateStore.URI, StateStore.METHOD_PUT, null, b);
        } catch (Throwable ignored) {
        } finally {
            sPushing = false;
        }
    }

    /** Context 就绪后同步一次，UI 首屏就有数据。 */
    static void syncZen() {
        if (ctx() == null) return;
        Logs.i("已连接手表应用，开始同步状态");
        push();
    }

    private static long toMs(long ts) {
        if (ts <= 0) return 0;
        if (ts < 100000000000L) return ts * 1000L;
        return ts;
    }
}
