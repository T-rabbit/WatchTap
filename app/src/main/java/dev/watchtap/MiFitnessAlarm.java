package dev.watchtap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 借用「小米运动健康」的时钟控制台通道来真正关闭/稍后手机闹钟。
 *
 * ==== 逆向结论（com.mi.health 3.59.2 + com.android.deskclock 18.28.0）====
 *
 * 时钟 App 暴露了 AIDL：
 *   com.android.deskclock.console.IAlarmConsoleV1          ← 小米运动健康实现
 *       onAlert(ConsoleAlarm) / onDismiss(ConsoleAlarm) / onSnooze(ConsoleAlarm)
 *       registerConsoleCallback(IAlarmConsoleV1Callback)
 *   com.android.deskclock.console.IAlarmConsoleV1Callback  ← 时钟实现
 *       dismiss(int alarmId)     ← 手环点「关闭」时走这里，时钟真正关闭闹钟
 *       snooze(int alarmId)      ← 手环点「稍后提醒」
 *
 * 绑定由时钟发起：bindService(action="com.xiaomi.wearable.CONSOLE_ALARM_ALERT",
 *                            package="com.mi.health")
 * 连接到 com.xiaomi.fitness.devicesettings.common.alarm.AlarmConsoleService
 * （受 com.mi.fitness.permission.CONSOLE_ALARM_RECEIVE 保护）。
 *
 * 所以本类在被注入到 com.mi.health 进程后：
 *   1. hook AlarmConsoleService$mBinder$1#registerConsoleCallback —— 抓到时钟侧回调代理
 *   2. hook onAlert —— 记住当前响铃闹钟的 id
 *   3. 收到 OPPO 健康进程发来的广播 → 调用 callback.dismiss(id) / snooze(id)
 */
final class MiFitnessAlarm {

    /** OPPO 健康进程 → 小米运动健康进程 的动作请求 */
    static final String ACTION_REQUEST = "dev.watchtap.CONSOLE_ALARM";
    static final String EXTRA_OP = "op";
    static final String OP_DISMISS = "dismiss";
    static final String OP_SNOOZE = "snooze";

    private static final String CLS_BINDER =
            "com.xiaomi.fitness.devicesettings.common.alarm.AlarmConsoleService$mBinder$1";

    private static volatile Object sCallback;   // 时钟侧的 IAlarmConsoleV1Callback 代理
    private static volatile int sAlarmId = -1;  // 当前响铃闹钟 id
    private static volatile boolean sListening;
    private static volatile Context sCtx;

    private MiFitnessAlarm() {}

    static void install(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> binder = Class.forName(CLS_BINDER, false, cl);

            for (Method m : binder.getDeclaredMethods()) {
                String n = m.getName();

                if ("registerConsoleCallback".equals(n) && m.getParameterCount() == 1) {
                    api.hook(m).intercept(chain -> {
                        Object cb = chain.getArg(0);
                        if (cb != null) {
                            sCallback = cb;
                            Logs.fire("闹钟通道", "已拿到时钟控制台回调：" + cb.getClass().getName());
                            dbg("callback=" + cb.getClass().getName());
                            ensureListening();
                        }
                        return chain.proceed();
                    });
                    Logs.i("闹钟通道：hook registerConsoleCallback");
                }

                if ("onAlert".equals(n) && m.getParameterCount() == 1) {
                    api.hook(m).intercept(chain -> {
                        Object alarm = chain.getArg(0);
                        int id = idOf(alarm);
                        if (id >= 0) sAlarmId = id;
                        Logs.fire("闹钟通道", "时钟上报闹钟 id=" + id);
                        dbg("onAlert id=" + id);
                        ensureListening();
                        return chain.proceed();
                    });
                    Logs.i("闹钟通道：hook onAlert");
                }

                if (("onDismiss".equals(n) || "onSnooze".equals(n))
                        && m.getParameterCount() == 1) {
                    final String action = n;
                    api.hook(m).intercept(chain -> {
                        Logs.fire("闹钟通道", "时钟通知：" + action + " id=" + idOf(chain.getArg(0)));
                        return chain.proceed();
                    });
                }
            }

            // 只有真正被时钟绑定的那个进程才注册接收器（其它进程没有回调可用）
            try {
                Class<?> svc = Class.forName(
                        "com.xiaomi.fitness.devicesettings.common.alarm.AlarmConsoleService",
                        false, cl);
                for (Method m : svc.getDeclaredMethods()) {
                    if (!"onBind".equals(m.getName())) continue;
                    api.hook(m).intercept(chain -> {
                        Logs.i("闹钟通道：onBind —— 本进程接管请求");
                        ensureListening();
                        return chain.proceed();
                    });
                    Logs.i("闹钟通道：hook AlarmConsoleService#onBind");
                }
            } catch (Throwable t) {
                Logs.w("闹钟通道：hook onBind 失败", t);
            }

            Logs.fire("闹钟通道", "已安装（小米运动健康进程）");
        } catch (Throwable t) {
            Logs.e("闹钟通道：安装失败", t);
        }
    }

    // ------------------------------------------------------------ 接收请求

    private static void ensureListening() {
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
                dbg("receiver registering");
                try {
                    BroadcastReceiver r = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent i) {
                            String op = i == null ? null : i.getStringExtra(EXTRA_OP);
                            Logs.fire("闹钟通道", "收到请求 op=" + op + " id=" + sAlarmId);
                            dbg("request op=" + op + " id=" + sAlarmId);
                            if (OP_DISMISS.equals(op)) doAction("dismiss");
                            else if (OP_SNOOZE.equals(op)) doAction("snooze");
                        }
                    };
                    IntentFilter f = new IntentFilter(ACTION_REQUEST);
                    if (Build.VERSION.SDK_INT >= 33) {
                        c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
                    } else {
                        c.registerReceiver(r, f);
                    }
                    Logs.i("闹钟通道：已在小米运动健康进程注册请求接收器");
                } catch (Throwable t) {
                    Logs.e("闹钟通道：注册接收器失败", t);
                }
            }
        }, 800L);
    }

    /** 调用时钟的 dismiss(id) / snooze(id)。 */
    private static void doAction(String which) {
        Object cb = sCallback;
        int id = sAlarmId;
        if (cb == null) {
            Logs.e("闹钟通道：还没有拿到时钟回调", null);
            dbg(which + " skipped: no callback");
            return;
        }
        if (id < 0) {
            Logs.e("闹钟通道：还不知道当前闹钟 id", null);
            dbg(which + " skipped: no id");
            return;
        }

        Method[] cands = intMethods(cb.getClass());
        if (cands.length == 0) {
            dbg("no int-arg method on " + cb.getClass().getName());
            return;
        }

        // 接口声明顺序：dismiss 在前、snooze 在后；代理方法名被混淆成 a/b，
        // 所以按候选顺序：dismiss→第 1 个，snooze→第 2 个；不足则复用第 1 个。
        Method target;
        try {
            target = cb.getClass().getMethod(which, int.class);
            target.setAccessible(true);
        } catch (Throwable ignored) {
            target = cands[("snooze".equals(which) && cands.length > 1) ? 1 : 0];
        }

        try {
            target.setAccessible(true);
            target.invoke(cb, id);
            Logs.fire("闹钟通道", "已调用时钟 " + target.getName() + "(" + id + ")");
            dbg(which + " -> " + target.getName() + "(" + id + ") invoked");
        } catch (Throwable t) {
            Logs.e("闹钟通道：调用失败", t);
            dbg(which + " invoke failed: " + t);
        }
    }

    /** 代理类里所有「一个 int 参数」的方法。 */
    private static Method[] intMethods(Class<?> c) {
        java.util.List<Method> out = new java.util.ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                out.add(m);
            }
        }
        return out.toArray(new Method[0]);
    }

    // ------------------------------------------------------------ 工具

    /** 诊断日志：写小米运动健康自己的外部目录。 */
    private static void dbg(String msg) {
        try {
            Context c = sCtx;
            if (c == null) c = ctx();
            if (c == null) return;
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "mialarm.log");
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

    /** 取闹钟 id：兼容未混淆（id/getId）与混淆后（字段 a / 方法 c()）两种形态。 */
    private static int idOf(Object alarm) {
        if (alarm == null) return -1;

        for (String m : new String[]{"getId", "c"}) {
            try {
                Method mm = alarm.getClass().getMethod(m);
                mm.setAccessible(true);
                Object v = mm.invoke(alarm);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {
            }
        }
        for (String f : new String[]{"id", "a"}) {
            try {
                Field ff = alarm.getClass().getDeclaredField(f);
                ff.setAccessible(true);
                Object v = ff.get(alarm);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {
            }
        }
        return -1;
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
