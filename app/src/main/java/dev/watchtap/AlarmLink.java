package dev.watchtap;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 手机闹钟 → 手表 联动（HyperOS 适配）。
 *
 * ==== 原版怎么做的 ====
 * OPPO 健康只认 ColorOS 平台闹钟：
 *
 *   PlatformClockBroadcastReceiver（监听 com.coloros.platformalarmclock…awaken_clock_action）
 *     ↓
 *   j5.c（PlatformClockManager）bindService 到 com.coloros.alarmclock
 *     ↓ AIDL IClockAidlInterface#getCurrentAlarm
 *     ↓ 回调 IClockUpdateAidlInterface#alarmClockRing(PlatformClockInfo)
 *     ↓
 *   com.heytap.wearable.watch.clock.manager.d#alarmClockRing(info)   ← 真正推给手表
 *
 * HyperOS 上没有 com.coloros.alarmclock，第一步就断了。
 *
 * ==== 本类的做法 ====
 * 绕开 ColorOS 服务，自己当「闹钟事件源」：
 *
 *   1. 拿手机真实闹钟：AlarmManager.getNextAlarmClock()（宿主 App 持有
 *      SCHEDULE_EXACT_ALARM，所以拿得到）；同时监听 ACTION_NEXT_ALARM_CLOCK_CHANGED
 *   2. 用 setAlarmClock() 在同一时刻给自己排一个「代理闹钟」（走宿主 App 身份，
 *      权限没问题），醒来即认为手机闹钟在响
 *   3. 构造 PlatformClockInfo，反射调用 d#alarmClockRing(info) —— 走的完全是
 *      App 自己的下发链路，手表端无感知
 *
 * 反向链路（手表上「关闭/稍后提醒」→ 手机）最终回到 j5.c → ColorOS 服务，
 * HyperOS 上必然失败；这里 hook 掉这两个方法，只记录日志并广播给模块 UI，
 * 不去碰系统闹钟（第三方应用无权取消别人的闹钟）。
 */
final class AlarmLink {

    /** App 内真正把闹钟事件下发给手表的类（实现 j5.a） */
    private static final String CLS_CTRL = "com.heytap.wearable.watch.clock.manager.d";
    private static final String CLS_INFO = "com.coloros.platformalarmclock.PlatformClockInfo";

    /** 调试用：adb shell am broadcast -a dev.watchtap.DEBUG_ALARM --el t <毫秒时间戳> */
    static final String ACTION_DEBUG = "dev.watchtap.DEBUG_ALARM";

    private static volatile Object sCtrl;
    private static volatile Class<?> sCtrlCls;
    private static volatile Context sCtx;
    /** 我们自己排的代理闹钟时刻，用来避免「排闹钟 → 系统广播 → 再排闹钟」的死循环 */
    private static volatile long sProxyAt;
    private static Class<?> sInfoCls;
    private static Method sRing;
    private static Method sDismiss;
    private static Method sSnooze;

    private AlarmLink() {}

    // ------------------------------------------------------------ 安装

    static void install(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> ctrl = Class.forName(CLS_CTRL, false, cl);
            sCtrlCls = ctrl;
            sInfoCls = Class.forName(CLS_INFO, false, cl);
            sRing = ctrl.getDeclaredMethod("alarmClockRing", sInfoCls);
            sRing.setAccessible(true);
            try {
                sDismiss = ctrl.getDeclaredMethod("dismissClock", long.class);
                sDismiss.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                sSnooze = ctrl.getDeclaredMethod("snoozeClock", long.class);
                sSnooze.setAccessible(true);
            } catch (Throwable ignored) {
            }

            // 捕获控制器实例（App 内部单例，构造一次）
            for (Constructor<?> k : ctrl.getDeclaredConstructors()) {
                api.hook(k).intercept(chain -> {
                    Object o = chain.proceed();
                    sCtrl = o;
                    Logs.i("闹钟联动：已捕获闹钟控制器实例");
                    return o;
                });
            }

            // 反向链路：dismiss/snooze 原本要打 ColorOS 服务，这里吞掉并记录
            if (sDismiss != null) {
                api.hook(sDismiss).intercept(chain -> {
                    long id = ((Number) chain.getArg(0)).longValue();
                    Logs.fire("闹钟联动", "手表请求：关闭闹钟 id=" + id
                            + "（HyperOS 无法取消系统闹钟，仅记录）");
                    dbg("WATCH dismiss id=" + id);
                    cancelProxy();
                    return null;
                });
            }
            if (sSnooze != null) {
                api.hook(sSnooze).intercept(chain -> {
                    long id = ((Number) chain.getArg(0)).longValue();
                    Logs.fire("闹钟联动", "手表请求：稍后提醒 id=" + id
                            + "（HyperOS 无法操作系统闹钟，仅记录）");
                    dbg("WATCH snooze id=" + id);
                    return null;
                });
            }

            // 旁路：记录所有发往手表的消息，便于确认闹钟是否真的发出去了
            try {
                Class<?> k = Class.forName(
                        "com.heytap.health.devicemanager.client.impl.multiple.k", false, cl);
                for (Method m : k.getDeclaredMethods()) {
                    if (!"h".equals(m.getName()) || m.getParameterCount() != 4) continue;
                    final int evIdx = paramIndexEndingWith(m, "MessageEvent");
                    if (evIdx < 0) continue;
                    api.hook(m).intercept(chain -> {
                        try {
                            Object ev = chain.getArg(evIdx);
                            Object sid = ev.getClass().getMethod("getServiceId").invoke(ev);
                            Object cid = ev.getClass().getMethod("getCommandId").invoke(ev);
                            Object data = ev.getClass().getMethod("getData").invoke(ev);
                            int len = data instanceof byte[] ? ((byte[]) data).length : 0;
                            dbg("SEND sid=" + sid + " cid=" + cid + " len=" + len);
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }
            } catch (Throwable t) {
                dbg("send hook failed: " + t);
            }

            // 反向链路：手表上的「关闭 / 稍后提醒」经 ClockSyncHandler 进入
            try {
                Class<?> h = Class.forName(
                        "com.heytap.wearable.watch.export.ClockSyncHandler", false, cl);
                for (Method m : h.getDeclaredMethods()) {
                    if (!"onMessageReceived".equals(m.getName())) continue;
                    if (m.getParameterCount() != 3) continue;
                    final int evIdx = paramIndexEndingWith(m, "MessageEvent");
                    if (evIdx < 0) continue;
                    api.hook(m).intercept(chain -> {
                        try {
                            Object ev = chain.getArg(evIdx);
                            Object sid = ev.getClass().getMethod("getServiceId").invoke(ev);
                            Object cid = ev.getClass().getMethod("getCommandId").invoke(ev);
                            Object data = ev.getClass().getMethod("getData").invoke(ev);
                            int len = data instanceof byte[] ? ((byte[]) data).length : 0;
                            dbg("WATCH-CLOCK sid=" + sid + " cid=" + cid + " len=" + len);
                            Logs.fire("闹钟联动", "手表闹钟动作：sid=" + sid + " cid=" + cid
                                    + " len=" + len);
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    dbg("ClockSyncHandler hooked");
                }
            } catch (Throwable t) {
                dbg("ClockSyncHandler hook failed: " + t);
            }

            // 旁路：记录所有「手表 → 手机」报文，用于确认稍后提醒/关闭是否回传
            try {
                Class<?> x = Class.forName("xs.e", false, cl);
                for (Method m : x.getDeclaredMethods()) {
                    if (!"onMessageReceived".equals(m.getName())) continue;
                    if (m.getParameterCount() != 2) continue;
                    final int evIdx = paramIndexEndingWith(m, "MessageEvent");
                    if (evIdx < 0) continue;
                    api.hook(m).intercept(chain -> {
                        try {
                            Object ev = chain.getArg(evIdx);
                            Object sid = ev.getClass().getMethod("getServiceId").invoke(ev);
                            Object cid = ev.getClass().getMethod("getCommandId").invoke(ev);
                            Object data = ev.getClass().getMethod("getData").invoke(ev);
                            int len = data instanceof byte[] ? ((byte[]) data).length : 0;
                            String hex = "";
                            if (data instanceof byte[]) {
                                StringBuilder sb = new StringBuilder();
                                for (byte b : (byte[]) data) sb.append(String.format("%02x", b));
                                hex = sb.toString();
                            }
                            dbg("RECV sid=" + sid + " cid=" + cid + " len=" + len + " hex=" + hex);
                            int sId = ((Number) sid).intValue();
                            int cId = ((Number) cid).intValue();
                            if (sId == 10) {
                                if (cId == 24) onWatchDismiss();
                                else if (cId == 25) onWatchSnooze();
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }
                dbg("inbound tap installed");
            } catch (Throwable t) {
                dbg("inbound tap failed: " + t);
            }

            Logs.fire("闹钟联动", "hook 已安装");
            dbg("install ok, ring=" + (sRing != null));
        } catch (Throwable t) {
            Logs.e("闹钟联动安装失败（版本可能不匹配）", t);
        }
    }

    // ------------------------------------------------------------ 闹钟来源

    /** 在拿到 Context 后调用：注册闹钟变化监听 + 调试入口。 */
    static void attach(Context ctx) {
        if (ctx == null) return;
        // 只在主进程工作：模块会被注入到 :once / :transport 等子进程，
        // 若每个进程都注册接收器，一次闹钟会重复下发 N 次。
        try {
            String pn = android.app.Application.getProcessName();
            String pkg = ctx.getPackageName();
            if (pn != null && !pn.equals(pkg)) {
                Logs.i("闹钟联动：子进程 " + pn + " 跳过");
                return;
            }
        } catch (Throwable ignored) {
        }
        sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            Context app = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx;

            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String a = i == null ? null : i.getAction();
                    dbg("onReceive action=" + a);
                    if (ACTION_DEBUG.equals(a)) {
                        long t = i.getLongExtra("t", System.currentTimeMillis());
                        Logs.fire("闹钟联动", "调试触发：模拟手机闹钟 t=" + t);
                        dbg("debug trigger t=" + t);
                        inject(t, "调试");
                        return;
                    }
                    if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(a)) {
                        Logs.i("闹钟联动：系统闹钟有变化，重排代理闹钟");
                        scheduleProxy(app);
                        return;
                    }
                    if (ProxyReceiver.ACTION_PROXY.equals(a)) {
                        sProxyAt = 0L;
                        long t = i.getLongExtra("t", 0L);
                        Logs.fire("闹钟联动", "代理闹钟到点，判定手机闹钟响铃 t=" + t);
                        inject(t, "手机闹钟");
                        scheduleProxy(app);     // 排下一个
                    }
                }
            };

            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_DEBUG);
            f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            f.addAction(ProxyReceiver.ACTION_PROXY);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }

            boolean enabled = Prefs.getBool(app, Prefs.K_ALARM_LINK, true);
            if (!enabled) {
                Logs.fire("闹钟联动", "已在设置里关闭，仅保留调试入口");
                return;
            }
            Logs.fire("闹钟联动", "已监听系统闹钟变化 + 调试入口");
            boolean on = Prefs.getBool(app, Prefs.K_ALARM_LINK, true);
            dbg("attach ok, alarmLink=" + on);
            scheduleProxy(app);
        } catch (Throwable t) {
            Logs.e("闹钟联动：注册监听失败", t);
        }
    }

    static final class ProxyReceiver extends BroadcastReceiver {
        static final String ACTION_PROXY = "dev.watchtap.PROXY_ALARM";

        @Override
        public void onReceive(Context c, Intent i) {
        }
    }

    // ------------------------------------------------------------ 手表回传动作

    /** 手表点「关闭」：取消代理闹钟，并提示用户在手机上关掉。 */
    static void onWatchDismiss() {
        dbg("watch action: DISMISS");
        Logs.fire("闹钟联动", "手表已关闭闹钟（手机端闹钟需手动关闭）");
        cancelProxy();
        notify("手表已关闭闹钟", "手机闹钟仍在响，请手动关闭");
    }

    /** 手表点「稍后提醒」：固定 5 分钟后再次下发。 */
    static void onWatchSnooze() {
        long when = System.currentTimeMillis() + 5 * 60 * 1000L;
        dbg("watch action: SNOOZE -> " + fmt(when));
        Logs.fire("闹钟联动", "手表已稍后提醒，5 分钟后再次提醒");
        scheduleProxyAt(when);
        notify("已稍后提醒", "5 分钟后（" + fmt(when) + "）再次提醒手表");
    }

    /** 在指定时刻排代理闹钟。 */
    static void scheduleProxyAt(long when) {
        try {
            Context c = sCtx;
            if (c == null) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0x5741,
                    new Intent(ProxyReceiver.ACTION_PROXY).setPackage(c.getPackageName()), flags);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, null), pi);
            sProxyAt = when;
            dbg("proxy scheduled at " + fmt(when));
        } catch (Throwable t) {
            dbg("scheduleProxyAt failed: " + t);
        }
    }

    /** 简单通知，告诉用户手表侧发生了什么。 */
    private static void notify(String title, String text) {
        try {
            Context c = sCtx;
            if (c == null) return;
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "watchtap_alarm";
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        ch, "闹钟联动", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
            }
            android.app.Notification n = new android.app.Notification.Builder(c, ch)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build();
            nm.notify(0x5742, n);
        } catch (Throwable t) {
            dbg("notify failed: " + t);
        }
    }

    /** 手表关闭闹钟时，把我们自己的代理闹钟也取消，避免再次注入。 */
    private static void cancelProxy() {
        try {
            Context c = sCtx;
            if (c == null) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0x5741,
                    new Intent(ProxyReceiver.ACTION_PROXY).setPackage(c.getPackageName()), flags);
            am.cancel(pi);
            sProxyAt = 0L;
            dbg("proxy cancelled");
        } catch (Throwable ignored) {
        }
    }

    /** 读系统下一个闹钟，用 setAlarmClock 在同一时刻排一个代理闹钟。 */
    static void scheduleProxy(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            AlarmManager.AlarmClockInfo info = am.getNextAlarmClock();
            if (info == null) {
                Logs.i("闹钟联动：系统当前没有闹钟");
                return;
            }
            long when = info.getTriggerTime();
            long now = System.currentTimeMillis();
            if (when == sProxyAt) {
                dbg("skip reschedule: next alarm is our own proxy");
                return;
            }
            if (when <= now + 1000L) {
                Logs.i("闹钟联动：下一个闹钟已过或即将触发，跳过");
                return;
            }

            Intent i = new Intent(ProxyReceiver.ACTION_PROXY).setPackage(ctx.getPackageName());
            i.putExtra("t", when);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0x5741, i, flags);

            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, null), pi);
            sProxyAt = when;
            dbg("proxy scheduled at " + fmt(when));
            Logs.i("闹钟联动：代理闹钟已排 " + fmt(when) + "（剩余 "
                    + ((when - now) / 1000) + " 秒）");
        } catch (Throwable t) {
            Logs.e("闹钟联动：排代理闹钟失败", t);
        }
    }

    // ------------------------------------------------------------ 注入

    /** 构造 PlatformClockInfo 并调用 App 自己的 alarmClockRing()。 */
    static boolean inject(long triggerTime, String source) {
        if (sCtrl == null) {
            // App 懒加载才有实例，这里自己造一个（无参构造只包一个 lambda，无外部依赖）
            try {
                sCtrl = sCtrlCls.getDeclaredConstructor().newInstance();
                dbg("controller created reflectively");
            } catch (Throwable t) {
                Logs.e("闹钟联动：无法创建控制器", t);
                dbg("create controller failed: " + t);
                return false;
            }
        }
        try {
            long t = triggerTime > 0 ? triggerTime : System.currentTimeMillis();
            Object info = sInfoCls.newInstance();

            setter(info, "setAlarmTime", long.class, t);
            setter(info, "setScheduleId", long.class, t);
            setter(info, "setTagName", String.class, source);
            setter(info, "setLanguage", String.class, "zh-CN");
            setter(info, "setmIsGarbAlarm", boolean.class, false);  // 否则会被设备能力判断拦掉
            setter(info, "setDelayReminder", boolean.class, true);  // 允许稍后提醒
            setter(info, "setmSnoozeTime", int.class, 5);           // 稍后提醒间隔（分钟）
            setter(info, "setmRingNum", int.class, 1);

            sRing.invoke(sCtrl, info);
            dbg("inject ok at " + fmt(t));
            Logs.fire("闹钟联动", "已推送给手表：" + source + " t=" + fmt(t));
            return true;
        } catch (Throwable t) {
            Logs.e("闹钟联动：注入失败", t);
            dbg("inject failed: " + t);
            return false;
        }
    }

    /** 安装阶段（还没有 Context）先缓存，attach 后统一落盘。 */
    private static final java.util.List<String> PRE = new java.util.ArrayList<>();

    /** 诊断日志：直接写宿主 App 自己的外部目录，不需要 root。 */
    private static void dbg(String msg) {
        try {
            Context c = sCtx;
            if (c == null) {
                if (PRE.size() < 80) PRE.add(msg);
                return;
            }
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "alarmlink.log");
            if (f.length() > 512 * 1024) f.delete();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            try {
                java.text.SimpleDateFormat fmt =
                        new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);
                if (!PRE.isEmpty()) {
                    for (String p : PRE) w.write(fmt.format(new java.util.Date()) + " [early] " + p + "\n");
                    PRE.clear();
                }
                w.write(fmt.format(new java.util.Date()) + " " + msg + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static int paramIndexEndingWith(Method m, String suffix) {
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] != null && ps[i].getName().endsWith(suffix)) return i;
        }
        return -1;
    }

    private static void setter(Object o, String name, Class<?> type, Object v) {
        try {
            Method m = o.getClass().getMethod(name, type);
            m.setAccessible(true);
            m.invoke(o, v);
        } catch (Throwable ignored) {
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "未知";
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }
}
