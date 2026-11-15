package dev.watchtap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 在系统时钟进程内关闭/稍后闹钟。
 *
 * ==== 为什么走这里 ====
 * 前面把「小米运动健康」的通道摸清了：时钟 bind 它，并通过 AIDL
 *   IAlarmConsoleV1Callback.dismiss(int id) / snooze(int id)
 * 把「关闭闹钟」的入口交给它。但时钟里 `AdditionUtil.HEALTH_PKG_NAME`
 * 写死为 "com.mi.health"，OPPO 健康无法顶替这个角色。
 *
 * 所以换个更直接的办法：把模块注入到时钟进程（scope 加 com.android.deskclock），
 * 从内部取到当前响铃的 Alarm，再用时钟自己的 AlarmReceiver 关闭它——
 * 同应用调用，不存在 miui.permission.ACCESS_ALARM_RECEIVER 的权限问题。
 *
 * 关键点：
 *   AlarmService.getCurrentAlarm() -> Alarm（Parcelable）
 *   AlarmReceiver 处理 action="action.alarm_cancel" + extra="intent.extra.alarm"
 */
final class ClockChannel {

    static final String ACTION_REQUEST = "dev.watchtap.CLOCK_ALARM";
    static final String EXTRA_OP = "op";
    static final String OP_DISMISS = "dismiss";
    static final String OP_SNOOZE = "snooze";
    static final String OP_CANCEL = "cancel";

    /** 稍后提醒固定分钟数（与手表端显示保持一致） */
    static final int SNOOZE_MINUTES = 5;

    private static volatile Context sCtx;
    private static volatile boolean sListening;
    /** 最近一次闹钟广播里携带的 Alarm（Parcelable），响铃/关闭都用它 */
    private static volatile Object sAlarm;
    private static volatile ClassLoader sCl;
    /** 响铃界面实例（稍后提醒需要走它自己的 onSnooze） */
    private static volatile Object sActivity;
    /** 去重：同一动作 3 秒内只处理一次（多个进程/多次广播会重复进来） */
    private static volatile long sLastActionAt;
    private static volatile String sLastActionKey = "";
    /**
     * 已处理过 snooze/dismiss 的「那一次响铃」。
     *
     * 用 ALARM_ALERT 到达时刻标记一次响铃周期：同一个周期内只处理一次动作。
     * 之前的 8 秒时间窗不够 —— 手表重发/多进程重复会隔几十秒再来一次，
     * 结果是排出两个不同的推迟时间。
     */
    private static volatile long sAlertCycle;

    /** 诊断日志：写系统时钟自己的外部目录。 */
    private static void dbg(String msg) {
        try {
            Context c = sCtx;
            if (c == null) c = ctx();
            if (c == null) return;
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "clockch.log");
            if (f.length() > 256 * 1024) f.delete();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            try {
                w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                        .format(new java.util.Date()) + " " + msg + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private ClockChannel() {}

    static void install(XposedInterface api, ClassLoader cl) {
        sCl = cl;
        try {
            // 类加载成功即说明注入到了时钟进程；真正注册在拿到 Context 后
            Class<?> recv = Class.forName("com.android.deskclock.AlarmReceiver", false, cl);
            for (Method m : recv.getDeclaredMethods()) {
                if (!"onReceive".equals(m.getName()) || m.getParameterCount() != 2) continue;
                api.hook(m).intercept(chain -> {
                    try {
                        Object intent = chain.getArg(1);
                        if (intent instanceof Intent) {
                            Intent it = (Intent) intent;
                            StringBuilder keys = new StringBuilder();
                            android.os.Bundle ex = it.getExtras();
                            if (ex != null) for (String k : ex.keySet()) keys.append(k).append(',');
                            dbg("AlarmReceiver action=" + it.getAction() + " extras=[" + keys + "]");
                            Object a = ((Intent) intent).getParcelableExtra("intent.extra.alarm");
                            if (a != null) {
                                sAlarm = a;
                                Logs.fire("时钟通道", "捕获闹钟 alarm=" + a.getClass().getSimpleName());
                                dbg("captured alarm from intent action=" + ((Intent) intent).getAction());
                            } else {
                                dbg("intent without alarm extra: " + ((Intent) intent).getAction());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                Logs.i("时钟通道：hook AlarmReceiver#onReceive");

            // 真正的响铃入口：AlarmManager -> startForegroundService(ALARM_ALERT)
            try {
                Class<?> svc = Class.forName("com.android.deskclock.alarm.alert.AlarmService", false, cl);
                for (Method sm : svc.getDeclaredMethods()) {
                    if (!"onStartCommand".equals(sm.getName()) || sm.getParameterCount() != 3) continue;
                    api.hook(sm).intercept(chain -> {
                        try {
                            Object intent = chain.getArg(0);
                            if (intent instanceof Intent) {
                                Intent it = (Intent) intent;
                                Object a = it.getParcelableExtra("intent.extra.alarm");
                                StringBuilder keys = new StringBuilder();
                                android.os.Bundle ex = it.getExtras();
                                if (ex != null) {
                                    for (String k : ex.keySet()) {
                                        Object v = ex.get(k);
                                        keys.append(k).append('=').append(v).append(';');
                                    }
                                }
                                dbg("AlarmService.onStartCommand action=" + it.getAction()
                                        + " alarm=" + (a == null ? "null" : a.getClass().getSimpleName())
                                        + " extras=[" + keys + "]");
                                // 新的响铃周期开始，允许接受一次新动作
                                sAlertCycle = System.currentTimeMillis();
                                sLastActionKey = "";
                                dbg("new alert cycle=" + sAlertCycle);

                                if (a == null) {
                                    // 新版只带 intent.extra.alarm_raw（Parcel 字节），
                                    // 用时钟自己的解析器还原 Alarm
                                    try {
                                        Class<?> helper = Class.forName(
                                                "com.android.deskclock.util.AlarmHelper", false, sCl);
                                        Method parse = helper.getDeclaredMethod(
                                                "parseAlarmFromRawDataIntent", Intent.class);
                                        parse.setAccessible(true);
                                        Object parsed = parse.invoke(null, it);
                                        if (parsed != null) {
                                            sAlarm = parsed;
                                            dbg("parsed alarm from raw data ok");
                                        }
                                    } catch (Throwable t) {
                                        dbg("parse alarm failed: " + t);
                                    }
                                } else {
                                    sAlarm = a;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    Logs.i("时钟通道：hook AlarmService#onStartCommand");
                }
            } catch (Throwable t) {
                Logs.w("时钟通道：hook AlarmService 失败", t);
            }
            }
            // 捕获响铃界面实例：稍后提醒要调用它的 onSnooze()
            try {
                Class<?> act = Class.forName(
                        "com.android.deskclock.alarm.alert.AlarmAlertFullScreenActivity", false, cl);
                for (Method am : act.getDeclaredMethods()) {
                    if (!"onCreate".equals(am.getName()) && !"onResume".equals(am.getName())) continue;
                    api.hook(am).intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self != null) {
                            sActivity = self;
                            dbg("alarm activity captured via " + chain.getExecutable().getName());
                        }
                        return chain.proceed();
                    });
                }
                Logs.i("时钟通道：hook 响铃界面");
            } catch (Throwable t) {
                Logs.w("时钟通道：hook 响铃界面失败", t);
            }

            Logs.i("时钟通道：已注入系统时钟进程");
            startWarmup();
        } catch (Throwable t) {
            Logs.w("时钟通道：安装失败", t);
        }
    }

    private static void startWarmup() {
        if (sListening) return;
        sListening = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                Context c = ctx();
                if (c == null) {
                    if (++tries < 60) new Handler(Looper.getMainLooper()).postDelayed(this, 500L);
                    return;
                }
                sCtx = c;
                try {
                    BroadcastReceiver r = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent i) {
                            String op = i == null ? null : i.getStringExtra(EXTRA_OP);
                            Logs.fire("时钟通道", "收到请求 op=" + op);
                            if (OP_DISMISS.equals(op)) doDismiss(false);
                            else if (OP_SNOOZE.equals(op)) doDismiss(true);
                            else if (OP_CANCEL.equals(op)) cancelScheduled(ctx);
                        }
                    };
                    IntentFilter f = new IntentFilter(ACTION_REQUEST);
                    if (Build.VERSION.SDK_INT >= 33) {
                        c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
                    } else {
                        c.registerReceiver(r, f);
                    }
                    Logs.i("时钟通道：请求接收器已注册");
                    dbg("installed, receiver ready");
                } catch (Throwable t) {
                    Logs.e("时钟通道：注册接收器失败", t);
                }
            }
        }, 1000L);
    }

    /**
     * 用时钟自己的 AlarmReceiver 关闭/取消稍后闹钟。
     *
     * @param snooze true = 走 snooze 相关动作（当前实现为关闭）
     */
    private static void doDismiss(boolean snooze) {
        Context c = sCtx;
        if (c == null) { dbg("no ctx"); return; }

        // 去重按「响铃周期」：同一次响铃只接受一个动作
        long cycle = sAlertCycle;
        String key = (snooze ? "snooze:" : "dismiss:") + cycle;
        long now = System.currentTimeMillis();
        if (cycle != 0 && key.equals(sLastActionKey)) {
            dbg("skip duplicate " + (snooze ? "snooze" : "dismiss")
                    + " for alert cycle " + cycle);
            return;
        }
        // 兜底：同一动作 3 秒内重复也拦掉（cycle 为 0 时生效）
        if (key.equals(sLastActionKey) && now - sLastActionAt < 3000L) {
            dbg("skip duplicate (fast) " + key);
            return;
        }
        sLastActionKey = key;
        sLastActionAt = now;

        // 稍后提醒：固定 5 分钟，与手表显示一致。
        // 不用时钟自带的 snooze（时长取系统设置，实测 10 分钟），
        // 而是自己排一个精确的 5 分钟闹钟，然后关掉当前响铃。
        if (snooze) {
            // 走时钟自己的 snoozeAlarm(ctx, alarm, seconds)：
            //   - 在 App 里能看到这个闹钟处于「已推迟」状态
            //   - 由时钟负责重新排期，不污染原闹钟
            //   - 秒数由我们指定，因此固定 5 分钟，与手表一致
            if (nativeSnooze(c, SNOOZE_MINUTES)) {
                Logs.fire("时钟通道", "已稍后提醒：" + SNOOZE_MINUTES + " 分钟后再次响铃");
                return;
            }
            dbg("snooze: native snooze failed, fallback to AlarmManager");
            if (scheduleExact(c, SNOOZE_MINUTES)) {
                silenceOnly(c);
                dbg("snooze: alarm re-armed at +" + SNOOZE_MINUTES + "min");
                Logs.fire("时钟通道", "已稍后提醒：" + SNOOZE_MINUTES + " 分钟后再次响铃");
                return;
            }
            dbg("snooze: all paths failed, fallback to dismiss");
        }

        try {
            Object alarm = sAlarm != null ? sAlarm : currentAlarm();
            dbg("dismiss requested, alarm=" + (alarm == null ? "null" : alarm.getClass().getName()));

            // 复用时钟自己的关闭三步：清通知、停铃声、删一次性闹钟
            Class<?> helper = Class.forName("com.android.deskclock.util.AlarmHelper", false, sCl);
            Class<?> notif = Class.forName("com.android.deskclock.util.NotificationUtil", false, sCl);

            try {
                Method clear = notif.getDeclaredMethod("clearAlarmAlertNotification", Context.class, int.class);
                clear.setAccessible(true);
                clear.invoke(null, c, 0);
                dbg("cleared alert notification(0)");
            } catch (Throwable t) {
                dbg("clear notification failed: " + t);
            }

            try {
                Method stop = helper.getDeclaredMethod("stopAlarmKlaxon", Context.class);
                stop.setAccessible(true);
                stop.invoke(null, c);
                dbg("stopAlarmKlaxon ok");
            } catch (Throwable t) {
                dbg("stopAlarmKlaxon failed: " + t);
            }

            if (alarm != null) {
                try {
                    Method del = helper.getDeclaredMethod("tryDeleteOneshotAlarm",
                            Context.class, alarm.getClass());
                    del.setAccessible(true);
                    del.invoke(null, c, alarm);
                    dbg("oneshot alarm deleted");
                } catch (Throwable t) {
                    // 方法签名可能是父类型 Alarm
                    try {
                        Method del = helper.getDeclaredMethod("tryDeleteOneshotAlarm",
                                Context.class, Class.forName("com.android.deskclock.Alarm", false, sCl));
                        del.setAccessible(true);
                        del.invoke(null, c, alarm);
                        dbg("oneshot alarm deleted (typed)");
                    } catch (Throwable t2) {
                        dbg("delete alarm failed: " + t2);
                    }
                }
            }

            // 停掉告警服务
            try {
                Class<?> svc = Class.forName("com.android.deskclock.alarm.alert.AlarmService", false, sCl);
                c.stopService(new Intent(c, svc));
                dbg("stopService called");
            } catch (Throwable t) {
                dbg("stopService failed: " + t);
            }

            Logs.fire("时钟通道", snooze ? "已请求稍后" : "已关闭闹钟");
        } catch (Throwable t) {
            Logs.e("时钟通道：关闭失败", t);
            dbg("dismiss error: " + t);
        }
    }

    /**
     * 无界面稍后提醒：走时钟自己的 AlarmProvider#call("snoozeAlarmAlert")。
     *
     * 这是时钟给语音助手（XiaoAi）用的入口，内部会：
     *   AlarmService.getCurrentAlarm() → 发 action.alarm_snooze 广播
     * 完全不拉起任何 Activity（之前的 HandleSetAlarmActivity 会跳转时钟界面）。
     */
    /**
     * 精确排一个 N 分钟后的闹钟（不碰 UI）。
     *
     * 做法：复制当前响铃的 Alarm → 改时间 → 交给时钟自己的排期通道。
     * 这里直接用 AlarmManager + 时钟的 AlarmReceiver，避免任何 Activity。
     */
    /**
     * 精确排一个 N 分钟后的闹钟。
     *
     * 不依赖时钟数据库（addAlarm/setAlarm 在不同 ROM 上行为不一致），
     * 直接用 AlarmManager 给时钟的 AlarmReceiver 排一个 ALARM_ALERT，
     * 参数沿用当前闹钟的 alarm_raw 字节，行为与真实闹钟一致。
     */
    private static boolean scheduleExact(Context c, int minutes) {
        try {
            Object alarm = sAlarm != null ? sAlarm : currentAlarm();
            if (alarm == null) {
                dbg("scheduleExact: no alarm object");
                return false;
            }
            long when = System.currentTimeMillis() + minutes * 60_000L;

            // 先深拷贝再改时间 —— 直接改 sAlarm 会污染时钟内部的同一个对象，
            // 让原闹钟在界面上变成「已改到 5 分钟后」，看起来也像被关掉。
            Class<?> alarmCls = Class.forName("com.android.deskclock.Alarm", false, sCl);
            Object copy = cloneAlarm(alarm, alarmCls);
            if (copy == null) {
                dbg("scheduleExact: clone failed, abort");
                return false;
            }
            alarm = copy;

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(when);
            setInt(alarm, "hour", cal.get(java.util.Calendar.HOUR_OF_DAY));
            setInt(alarm, "minutes", cal.get(java.util.Calendar.MINUTE));
            setInt(alarm, "seconds", 0);
            setLong(alarm, "time", when);
            setBool(alarm, "enabled", true);
            // 推迟出来的这一次必须是「一次性、响完即弃」，不能写回用户的原闹钟
            setBool(alarm, "deleteAfterUse", true);

            android.os.Parcel parcel = android.os.Parcel.obtain();
            java.lang.reflect.Method writeToParcel = alarmCls.getMethod(
                    "writeToParcel", android.os.Parcel.class, int.class);
            writeToParcel.setAccessible(true);
            writeToParcel.invoke(alarm, parcel, 0);
            parcel.setDataPosition(0);
            byte[] raw = parcel.marshall();
            parcel.recycle();

            Intent i = new Intent("com.android.deskclock.ALARM_ALERT");
            i.setComponent(new android.content.ComponentName("com.android.deskclock",
                    "com.android.deskclock.alarm.alert.AlarmService"));
            i.putExtra("intent.extra.alarm_raw", raw);
            i.putExtra("intent.extra.alarm", (android.os.Parcelable) alarm);

            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (android.os.Build.VERSION.SDK_INT >= 23
                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getForegroundService(
                    c, 0x5742, i, flags);

            android.app.AlarmManager am =
                    (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setAlarmClock(new android.app.AlarmManager.AlarmClockInfo(when, null), pi);
            dbg("scheduleExact: AlarmManager set at " + fmt(when));
            return true;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause() == null ? ite : ite.getCause();
            dbg("scheduleExact InvocationTarget: " + cause);
        } catch (Throwable t) {
            dbg("scheduleExact failed: " + t);
        }
        return false;
    }

    /** 通过 Parcel 往返做深拷贝，避免字段遗漏。 */
    private static Object cloneAlarm(Object alarm, Class<?> alarmCls) {
        android.os.Parcel p = android.os.Parcel.obtain();
        android.os.Parcel p2 = android.os.Parcel.obtain();
        try {
            java.lang.reflect.Method w = alarmCls.getMethod(
                    "writeToParcel", android.os.Parcel.class, int.class);
            w.setAccessible(true);
            w.invoke(alarm, p, 0);
            p.setDataPosition(0);

            byte[] bytes = p.marshall();

            java.lang.reflect.Constructor<?> ctor = alarmCls.getDeclaredConstructor(
                    android.os.Parcel.class);
            ctor.setAccessible(true);
            p2.unmarshall(bytes, 0, bytes.length);
            p2.setDataPosition(0);
            return ctor.newInstance(p2);
        } catch (Throwable t) {
            dbg("cloneAlarm failed: " + t);
            return null;
        } finally {
            p.recycle();
            p2.recycle();
        }
    }

    /** 取消我们通过 AlarmManager 排下的闹钟（清理用）。 */
    static void cancelScheduled(Context c) {
        try {
            Intent i = new Intent("com.android.deskclock.ALARM_ALERT");
            i.setComponent(new android.content.ComponentName("com.android.deskclock",
                    "com.android.deskclock.alarm.alert.AlarmService"));
            // 注意：不能只用 FLAG_NO_CREATE —— 重启后 intent 里没有 extras，
            // 与原 PendingIntent 的 filterEquals 虽然成立，但部分 ROM 仍取不到。
            // 这里带 CREATE 拿一个同 filter 的引用再 cancel。
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (android.os.Build.VERSION.SDK_INT >= 23
                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getForegroundService(
                    c, 0x5742, i, flags);
            if (pi == null) {
                dbg("cancelScheduled: no pending intent");
                return;
            }
            android.app.AlarmManager am =
                    (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(pi);
            pi.cancel();
            dbg("cancelScheduled: cancelled");
        } catch (Throwable t) {
            dbg("cancelScheduled failed: " + t);
        }
    }

    private static void setLong(Object o, String field, long v) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            if (f.getType() == long.class) f.setLong(o, v);
            else if (f.getType() == int.class) f.setInt(o, (int) v);
        } catch (Throwable ignored) {
        }
    }

    private static void setInt(Object o, String field, int v) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(o, v);
        } catch (Throwable ignored) {
        }
    }

    private static void setBool(Object o, String field, boolean v) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setBoolean(o, v);
        } catch (Throwable ignored) {
        }
    }

    private static String fmt(long ms) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    /**
     * 调时钟自己的 snooze：AlarmHelper.snoozeAlarm(Context, Alarm, int seconds)。
     *
     * 它内部会：清告警通知 → 停铃声 → saveSnoozeAlert 写推迟记录 →
     * 重新排期。因此闹钟在时钟 App 里会显示为「已推迟」，
     * 而不是像我之前自建 AlarmManager 那样在列表里查无此物。
     *
     * @param minutes 推迟分钟数。
     *                时钟内部算法：minutes × 1000（当秒算）再 × 60 —— 合计 ×60000 毫秒，
     *                也就是这个参数单位就是「分钟」，传 5 才是 5 分钟。
     */
    private static boolean nativeSnooze(Context c, int minutes) {
        Object alarm = sAlarm != null ? sAlarm : currentAlarm();
        if (alarm == null) {
            dbg("nativeSnooze: no alarm object");
            return false;
        }
        try {
            Class<?> helper = Class.forName(
                    "com.android.deskclock.util.AlarmHelper", false, sCl);
            Class<?> alarmCls = Class.forName("com.android.deskclock.Alarm", false, sCl);

            java.lang.reflect.Method m = helper.getDeclaredMethod(
                    "snoozeAlarm", Context.class, alarmCls, int.class);
            m.setAccessible(true);
            m.invoke(null, c, alarm, minutes);
            dbg("nativeSnooze ok: +" + minutes + "min (alarm kept, visible in app)");
            return true;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause() == null ? ite : ite.getCause();
            dbg("nativeSnooze InvocationTarget: " + cause);
            for (StackTraceElement e : java.util.Arrays.copyOf(
                    cause.getStackTrace(), Math.min(3, cause.getStackTrace().length))) {
                dbg("  at " + e);
            }
        } catch (Throwable t) {
            dbg("nativeSnooze failed: " + t);
        }
        return false;
    }

    /**
     * 稍后提醒用：只停掉当前响铃，**不删除闹钟**。
     *
     * 之前的实现复用了 dismissOnly()，里面会调 tryDeleteOneshotAlarm()，
     * 而通过 SET_ALARM 建的一次性闹钟 deleteAfterUse=true —— 会被直接从
     * 时钟列表里删掉，用户看到的就是「点了推迟，闹钟却没了」。
     * 推迟只是把时间往后挪，闹钟必须留着。
     */
    private static void silenceOnly(Context c) {
        try {
            Class<?> helper = Class.forName("com.android.deskclock.util.AlarmHelper", false, sCl);
            Class<?> notif = Class.forName("com.android.deskclock.util.NotificationUtil", false, sCl);

            try {
                Method clear = notif.getDeclaredMethod(
                        "clearAlarmAlertNotification", Context.class, int.class);
                clear.setAccessible(true);
                clear.invoke(null, c, 0);
                dbg("snooze: cleared alert notification");
            } catch (Throwable ignored) {
            }

            try {
                Method stop = helper.getDeclaredMethod("stopAlarmKlaxon", Context.class);
                stop.setAccessible(true);
                stop.invoke(null, c);
                dbg("snooze: klaxon stopped (alarm kept)");
            } catch (Throwable ignored) {
            }

            Class<?> svc = Class.forName(
                    "com.android.deskclock.alarm.alert.AlarmService", false, sCl);
            c.stopService(new Intent(c, svc));
        } catch (Throwable t) {
            dbg("silenceOnly error: " + t);
        }
    }

    /** AlarmService.getCurrentAlarm() —— 当前正在响铃的 Alarm。 */
    private static Object currentAlarm() {
        try {
            Class<?> svc = Class.forName("com.android.deskclock.alarm.alert.AlarmService");
            Method m = svc.getDeclaredMethod("getCurrentAlarm");
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context ctx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return ((Context) app).getApplicationContext();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
