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
        boolean first = (sleepState < 0);

        sleepState = state;
        sleepTs = toMs(tsSec);
        sleepCount.incrementAndGet();

        // 首次也是历史值，标注清楚，避免误以为是刚发生的变化
        if (first) {
            Logs.i("首次读取生理睡眠：" + (state == 1 ? "睡眠" : "清醒")
                    + "（仅记录）");
        } else {
            Logs.fire("睡眠检测", state == 1 ? "检测到入睡" : "检测到清醒");
        }
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

        // 首次上报（sleepMode 还是 -1）只建立基线，不触发联动。
        //
        // 原因：App 与手表连接后第一次读到的通常是数据库里的历史值，
        // 不是「此刻刚发生的变化」。如果按变化处理，会误触发一次
        // 勿扰切换和通知。用户明确说这个状态没用。
        // 首次上报只建立基线，不触发联动（那是历史值，不是刚发生的变化）。
        //
        // 但如果上次运行记录过状态，就从持久化恢复基线 ——
        // 这样 hook 进程重启后，真实的状态变化不会因为「首次」被漏掉。
        if (sleepMode < 0) {
            Context pc = ctx();
            int saved = (int) Prefs.getLong(pc, Prefs.K_LAST_SLEEP_MODE, -1);
            if (saved == 0 || saved == 1) {
                sleepMode = saved;
                Logs.i("已恢复上次的睡眠模式基线：" + (saved == 1 ? "开" : "关"));
            }
        }

        boolean first = (sleepMode < 0);

        boolean changed = !first && (sleepMode != on);

        sleepMode = on;
        Prefs.setLong(ctx(), Prefs.K_LAST_SLEEP_MODE, on);
        if (ts > 0) sleepModeTs = ts;
        sleepModeAt = System.currentTimeMillis();
        sleepModeCount.incrementAndGet();

        String who = fromPush ? "手表上报" : "App读取";

        if (first) {
            Logs.i("首次读取睡眠模式：" + (on == 1 ? "开" : "关")
                    + "（仅记录，不触发联动）");
        } else if (changed) {
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
    /** 上次读到的偏好（provider 不可用时用这个）。 */
    private static volatile boolean sMutePref = true;
    private static volatile boolean sNotifyPref = false;

    /** 开关修复是否启用（运行时读取，带缓存）。 */
    private static volatile boolean sFixSwitch;
    private static volatile long sFixSwitchAt;

    /**
     * 是否启用「开关修复」。
     *
     * 免 root 也能读：走 provider 的普通 IPC（ContentResolver.call），
     * 不需要任何特殊权限。provider 不在时用缓存值。
     *
     * 加 30 秒缓存，避免 UI 判断时反复 IPC。
     */
    static boolean fixSwitchEnabled() {
        long now = System.currentTimeMillis();
        if (now - sFixSwitchAt < 30_000L) return sFixSwitch;

        sFixSwitchAt = now;

        Context c = ctx();
        if (c != null) {
            try {
                Bundle b = c.getContentResolver().call(
                        StateStore.URI, StateStore.METHOD_GET, null, null);
                if (b != null) {
                    sFixSwitch = b.getBoolean(StateStore.K_FIX_SWITCH, false);
                    return sFixSwitch;
                }
            } catch (Throwable ignored) {
                // provider 不在
            }
        }

        // 兜底：读文件（有 root 时能跨读，没 root 只能读自己的）
        sFixSwitch = Prefs.getBool(c, Prefs.K_FIX_SWITCH, sFixSwitch);
        return sFixSwitch;
    }

    /** UI 推送后立即更新缓存。 */
    static void setFixSwitch(boolean v) {
        sFixSwitch = v;
        sFixSwitchAt = System.currentTimeMillis();
    }

    /** 由 UI 通过 provider 推送后调用，立即生效。 */
    static void setMutePref(boolean v) {
        sMutePref = v;
    }

    static void setNotifyPref(boolean v) {
        sNotifyPref = v;
    }

    static boolean mutePref() {
        // 直接读共享文件，完全不依赖 provider 进程。
        // 文件路径见 Prefs.candidates()，hook 进程和模块进程各写一份。
        Context c = ctx();
        sMutePref = Prefs.getBool(c, Prefs.K_MUTE, true);
        sNotifyPref = Prefs.getBool(c, Prefs.K_NOTIFY, false);
        return sMutePref;
    }

    /** 读取「发送通知」偏好。 */
    static boolean notifyPref() {
        mutePref();   // 顺带刷新两个偏好
        return sNotifyPref;
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

        // 直接在 hook 进程（OPPO 健康）内发通知，不经 provider。
        // 这样模块进程死掉也不影响，通知归属就是 com.heytap.health。
        Context c = ctx();
        if (c == null) {
            Logs.w("发送通知失败：拿不到上下文", null);
            return;
        }
        WatchNotify.send(c, on);
    }

    /** 手表睡眠模式变化 → 请求 provider 进程切勿扰。 */
    static void autoDnd(boolean on, boolean mute) {
        Logs.fire("自动联动", (on ? "开启" : "关闭") + "勿扰"
                + (on ? (mute ? "（同步静音）" : "（不同步静音）") : ""));

        // 直接在 hook 进程内用 su 操作，不依赖模块进程存活。
        // 原实现走 ContentResolver.call，模块进程被杀后就全挂了。
        boolean ok = DirectDnd.set(on, mute);
        if (ok) {
            push();
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
