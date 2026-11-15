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

    private static volatile Context sCtx;
    private static volatile boolean sListening;
    /** 最近一次闹钟广播里携带的 Alarm（Parcelable），响铃/关闭都用它 */
    private static volatile Object sAlarm;
    private static volatile ClassLoader sCl;
    /** 响铃界面实例（稍后提醒需要走它自己的 onSnooze） */
    private static volatile Object sActivity;

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

        // 稍后提醒：优先走响铃界面自己的 onSnooze()（会把闹钟推后并重新排期）
        if (snooze) {
            Object act = sActivity;
            if (act != null) {
                try {
                    Method m = act.getClass().getMethod("onSnooze");
                    m.setAccessible(true);
                    m.invoke(act);
                    dbg("onSnooze invoked -> alarm postponed");
                    Logs.fire("时钟通道", "已稍后提醒（手机闹钟推后）");
                    return;
                } catch (Throwable t) {
                    dbg("onSnooze failed: " + t);
                }
            } else {
                dbg("no alarm activity, scheduling +5min instead");
                if (scheduleIn5Minutes(c)) {
                    dismissOnly(c);
                    Logs.fire("时钟通道", "已稍后提醒：手机闹钟已在 5 分钟后重排");
                    return;
                }
            }
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

    /** 用时钟自己的建闹钟流程，排一个 5 分钟后的闹钟。 */
    private static boolean scheduleIn5Minutes(Context c) {
        try {
            long at = System.currentTimeMillis() + 5 * 60 * 1000L;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(at);
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int minute = cal.get(java.util.Calendar.MINUTE);

            Intent i = new Intent("android.intent.action.SET_ALARM");
            i.setComponent(new android.content.ComponentName("com.android.deskclock",
                    "com.android.deskclock.HandleSetAlarmActivity"));
            i.putExtra("android.intent.extra.alarm.HOUR", hour);
            i.putExtra("android.intent.extra.alarm.MINUTES", minute);
            i.putExtra("android.intent.extra.alarm.SKIP_UI", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            dbg("scheduled +5min alarm at " + hour + ":" + minute);
            return true;
        } catch (Throwable t) {
            dbg("schedule +5min failed: " + t);
            return false;
        }
    }

    /** 只做关闭动作（停铃声/停服务/删一次性闹钟）。 */
    private static void dismissOnly(Context c) {
        Object alarm = sAlarm != null ? sAlarm : currentAlarm();
        try {
            Class<?> helper = Class.forName("com.android.deskclock.util.AlarmHelper", false, sCl);
            Class<?> notif = Class.forName("com.android.deskclock.util.NotificationUtil", false, sCl);
            try {
                Method clear = notif.getDeclaredMethod("clearAlarmAlertNotification", Context.class, int.class);
                clear.setAccessible(true);
                clear.invoke(null, c, 0);
            } catch (Throwable ignored) {
            }
            try {
                Method stop = helper.getDeclaredMethod("stopAlarmKlaxon", Context.class);
                stop.setAccessible(true);
                stop.invoke(null, c);
                dbg("stopAlarmKlaxon ok (snooze)");
            } catch (Throwable ignored) {
            }
            if (alarm != null) {
                try {
                    Method del = helper.getDeclaredMethod("tryDeleteOneshotAlarm",
                            Context.class, Class.forName("com.android.deskclock.Alarm", false, sCl));
                    del.setAccessible(true);
                    del.invoke(null, c, alarm);
                } catch (Throwable ignored) {
                }
            }
            Class<?> svc = Class.forName("com.android.deskclock.alarm.alert.AlarmService", false, sCl);
            c.stopService(new Intent(c, svc));
        } catch (Throwable t) {
            dbg("dismissOnly error: " + t);
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
