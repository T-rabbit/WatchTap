package dev.watchtap;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * hook 进程侧的状态缓存 + 上报。
 *
 * 所有采集到的数据先写到这里，再通过 StateStore 推给 UI 进程。
 * 为了减少跨进程调用开销，采用「变化即推」而不做定时批量。
 */
final class State {

    private static final String TAG = "WatchTap";

    private static volatile Context sCtx;

    // 内存缓存（provider 读不到时 UI 直接看这些也行，但优先走 provider）
    static volatile int hr = -1;
    static volatile long hrTs = 0;
    static volatile int sleepState = -1;      // 生理睡眠：0=清醒 1=睡着
    static volatile long sleepTs = 0;
    static volatile int sleepMode = -1;       // 手表睡眠模式开关：0/1
    static volatile long sleepModeTs = 0;

    static final AtomicLong hrCount = new AtomicLong();
    static final AtomicLong sleepCount = new AtomicLong();
    static final AtomicLong sleepModeCount = new AtomicLong();

    static volatile String lastMsg = "";

    private State() {}

    /** 由 hook 在拿到宿主 Context 后调用。 */
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

    static void setHeartRate(int bpm, long tsSec) {
        if (bpm <= 0 || bpm > 250) return;
        hr = bpm;
        hrTs = toMs(tsSec);
        hrCount.incrementAndGet();
        push();
    }

    static void setSleepState(int state, long tsSec) {
        sleepState = state;
        sleepTs = toMs(tsSec);
        sleepCount.incrementAndGet();
        push();
    }

    /** 手表睡眠模式开关。src 用于区分「手表上报」和「App 内部读取」。 */
    static void setSleepMode(int on, long tsSec, boolean fromPush) {
        if (on != 0 && on != 1) return;
        long ms = toMs(tsSec);
        // App 内部读取路径经常带 ts=0（默认实例），忽略
        if (!fromPush && ms <= 0) {
            Log.i(TAG, "STATE: ignore sleepMode from read path (ts=0)");
            return;
        }
        // 旧值时间更新则忽略（防止读取路径覆盖上报值）
        if (!fromPush && ms > 0 && ms < sleepModeTs) {
            Log.i(TAG, "STATE: ignore stale sleepMode " + ms + " < " + sleepModeTs);
            return;
        }
        sleepMode = on;
        sleepModeTs = ms;
        sleepModeCount.incrementAndGet();
        push();
        if (on == 1) autoDnd(true);
    }

    /** 主动同步一次 zen_mode（Context 就绪后调用，UI 首屏就能看到）。 */
    static void syncZen() {
        Context c = ctx();
        if (c == null) return;
        push();
    }

    static void noteMessage(int sid, int cid, int len) {
        lastMsg = "sid=" + sid + " cid=0x" + Integer.toHexString(cid) + " len=" + len;
    }

    // ---------------------------------------------------------------- 联动

    /**
     * 手表进入睡眠模式 → 开勿扰；退出 → 关勿扰。
     *
     * 调用方是 hook 进程（com.heytap.health），它没有 ACCESS_NOTIFICATION_POLICY，
     * 所以不能直接写。改为 requestDnd()：由 provider 进程代写。
     */
    static void autoDnd(boolean on) {
        Context c = ctx();
        if (c == null) return;
        try {
            Bundle b = new Bundle();
            b.putBoolean("on", on);
            Bundle r = c.getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_TOGGLE_DND, null, b);
            boolean ok = r != null && r.getBoolean("ok", false);
            Log.i(TAG, "AUTO: dnd " + on + " -> " + (ok ? "ok" : "FAILED"));
        } catch (Throwable t) {
            Log.w(TAG, "AUTO: dnd failed", t);
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
            b.putInt(StateStore.K_HR, hr);
            b.putLong(StateStore.K_HR_TS, hrTs);
            b.putInt(StateStore.K_SLEEP_STATE, sleepState);
            b.putLong(StateStore.K_SLEEP_TS, sleepTs);
            b.putInt(StateStore.K_SLEEP_MODE, sleepMode);
            b.putLong(StateStore.K_SLEEP_MODE_TS, sleepModeTs);
            b.putInt(StateStore.K_ZEN, Dnd.zen(c));
            b.putLong(StateStore.K_ZEN_TS, System.currentTimeMillis());
            b.putLong(StateStore.K_UPDATED, System.currentTimeMillis());
            b.putString(StateStore.K_COUNTS,
                    hrCount.get() + "/" + sleepCount.get() + "/" + sleepModeCount.get());
            b.putString(StateStore.K_LAST_MSG, lastMsg);

            c.getContentResolver().call(StateStore.URI, StateStore.METHOD_PUT, null, b);
        } catch (Throwable ignored) {
        } finally {
            sPushing = false;
        }
    }

    private static long toMs(long ts) {
        if (ts <= 0) return 0;
        if (ts < 100000000000L) return ts * 1000L;   // 秒 → 毫秒
        return ts;
    }
}
