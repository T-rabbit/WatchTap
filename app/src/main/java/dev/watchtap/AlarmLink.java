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

    /**
     * 响铃事件来源开关。
     *
     * true  : 由时钟进程在 ALARM_ALERT 时直接上报（不再需要代理闹钟）
     * false : 退回旧的代理闹钟方案
     *
     * 保留开关是为了新方案万一在某个 ROM 上拿不到事件时能快速回退。
     */
    static final boolean USE_CLOCK_REPORT = true;

    /** 调试用（只测通知，绝不触发任何闹钟推送） */
    static final String ACTION_NOTIFY_TEST = "dev.watchtap.DEBUG_NOTIFY";

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
                    if (ACTION_NOTIFY_TEST.equals(a)) {
                        boolean on = !"off".equalsIgnoreCase(
                                String.valueOf(i.getStringExtra("state")));
                        boolean pref = State.notifyPref();
                        dbg("NOTIFY-TEST state=" + (on ? "asleep" : "awake")
                                + " notifyPref=" + pref);
                        Logs.fire("通知测试", "开关=" + pref + " 状态=" + (on ? "asleep" : "awake"));
                        if (pref) {
                            State.debugNotify(on);
                            Logs.fire("通知测试", "已调用 WatchNotify.send");
                        } else {
                            Logs.w("通知开关为关，未发送（请到 App 里打开「通知其他应用」）", null);
                        }
                        return;
                    }
                    if (ACTION_DEBUG.equals(a)) {
                        if (i.getBooleanExtra("unmute", false)) {
                            try {
                                android.media.AudioManager am = (android.media.AudioManager)
                                        sCtx.getSystemService(Context.AUDIO_SERVICE);
                                am.adjustStreamVolume(android.media.AudioManager.STREAM_ALARM,
                                        android.media.AudioManager.ADJUST_UNMUTE, 0);
                                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM,
                                        i.getIntExtra("vol", 8), 0);
                                dbg("debug unmute done");
                            } catch (Throwable t) {
                                dbg("debug unmute failed: " + t);
                            }
                            return;
                        }
                        if (i.getBooleanExtra("mute", false)) {
                            dbg("debug mute alarm stream");
                            muteAlarmStream(i.getIntExtra("restoreSec", 0));
                            return;
                        }
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
                    if (ClockChannel.ACTION_ALARM_FIRED.equals(a)) {
                        long t = i.getLongExtra(ClockChannel.EXTRA_FIRED_AT, 0L);
                        if (t <= 0) t = System.currentTimeMillis();
                        dbg("clock reported alarm fired t=" + fmt(t));
                        Logs.fire("闹钟联动", "系统时钟响铃，推送给手表 t=" + fmt(t));
                        inject(t, "手机闹钟");
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
            f.addAction(ACTION_NOTIFY_TEST);
            f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            f.addAction(ProxyReceiver.ACTION_PROXY);
            f.addAction(ClockChannel.ACTION_ALARM_FIRED);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }

            if (USE_CLOCK_REPORT) {
                // 新方案不再需要代理闹钟，清掉升级前遗留的那条
                try {
                    cancelProxy();
                    dbg("legacy proxy alarm cleared on attach");
                } catch (Throwable t) {
                    dbg("clear legacy proxy failed: " + t);
                }
            }

            boolean enabled = syncEnabled();
            if (!enabled) {
                Logs.fire("闹钟联动", "已在设置里关闭，仅保留调试入口");
                return;
            }
            Logs.fire("闹钟联动", "已监听系统闹钟变化 + 调试入口");
            dbg("attach ok, syncEnabled=" + syncEnabled());
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
    /**
     * 同步功能总开关（运行中变化也能生效）。
     *
     * 直接读模块 App 的 prefs 文件（固定路径 + root），
     * 不走 Prefs 的多路径猜测，避免跨 uid 读不到时静默回落到默认值。
     */
    static boolean syncEnabled() {
        String v = readModulePref("alarmSync");
        String l = readModulePref("alarmLink");
        boolean sync = v == null || "true".equals(v);
        boolean link = l == null || "true".equals(l);
        return sync && link;
    }

    /**
     * 读开关：走模块自己的 ContentProvider（它就跑在 OPPO 健康进程里），
     * 不依赖 root，也不跨 uid 猜文件路径。
     */
    private static String readModulePref(String key) {
        try {
            Context c = sCtx;
            if (c == null) return null;
            android.os.Bundle b = c.getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_GET, null, null);
            if (b == null) return null;
            boolean v = b.getBoolean(key, true);
            return v ? "true" : "false";
        } catch (Throwable t) {
            return null;
        }
    }

    static void onWatchDismiss() {
        if (!syncEnabled()) {
            dbg("watch DISMISS ignored: alarm sync disabled");
            return;
        }
        dbg("watch action: DISMISS");
        Logs.fire("闹钟联动", "手表已关闭闹钟 → 关闭系统闹钟");
        cancelProxy();
        boolean viaClock = requestClock(ClockChannel.OP_DISMISS);
        boolean dismissed = viaClock || dismissSystemAlarm();
        if (!viaClock) dbg("clock dismiss unavailable, fallback used=" + dismissed);
        notify("手表已关闭闹钟", dismissed ? "已关闭手机闹钟" : "关闭失败，请手动关闭手机闹钟");
    }

    /**
     * 手表点「稍后提醒」。
     *
     * 不在健康侧自己排代理闹钟 —— 手机闹钟由系统时钟推后 5 分钟，
     * 推后动作会触发 ACTION_NEXT_ALARM_CLOCK_CHANGED，
     * 我们据此把代理闹钟对齐到同一个时刻（避免两条时间线打架）。
     */
    static void onWatchSnooze() {
        if (!syncEnabled()) {
            dbg("watch SNOOZE ignored: alarm sync disabled");
            return;
        }
        long expect = System.currentTimeMillis() + 5 * 60 * 1000L;
        dbg("watch action: SNOOZE (clock will postpone to ~" + fmt(expect) + ")");
        Logs.fire("闹钟联动", "手表已稍后提醒：手机闹钟推后 5 分钟");
        requestClock(ClockChannel.OP_SNOOZE);
        notify("已稍后提醒", "手机闹钟将在约 " + fmt(expect) + " 再次响铃");
    }

    /** 请求系统时钟进程（模块注入在时钟里，走它自己的关闭逻辑）。 */
    private static boolean requestClock(String op) {
        try {
            Context c = sCtx;
            if (c == null) return false;
            Intent i = new Intent(ClockChannel.ACTION_REQUEST);
            i.setPackage("com.android.deskclock");
            i.putExtra(ClockChannel.EXTRA_OP, op);
            c.sendBroadcast(i);
            dbg("requestClock: " + op);
            return true;
        } catch (Throwable t) {
            dbg("requestClock failed: " + t);
            return false;
        }
    }

    /** 请求小米运动健康进程，用时钟控制台通道关闭/稍后闹钟。 */
    private static boolean requestMiFitness(String op) {
        try {
            Context c = sCtx;
            if (c == null) return false;
            Intent i = new Intent(MiFitnessAlarm.ACTION_REQUEST);
            i.setPackage("com.mi.health");
            i.putExtra(MiFitnessAlarm.EXTRA_OP, op);
            c.sendBroadcast(i);
            dbg("requestMiFitness: " + op);
            return true;
        } catch (Throwable t) {
            dbg("requestMiFitness failed: " + t);
            return false;
        }
    }

    /** 在指定时刻排代理闹钟（新方案下不再使用）。 */
    static void scheduleProxyAt(long when) {
        if (USE_CLOCK_REPORT) {
            dbg("proxy skipped (clock reports directly), would be " + fmt(when));
            return;
        }
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

    /**
     * 真正关闭系统闹钟。
     *
     * 系统时钟（com.android.deskclock）的 AlarmReceiver 监听
     *   action.alarm_cancel / action.snooze_cancel
     * 但受 miui.permission.ACCESS_ALARM_RECEIVER 保护，普通应用发不进去。
     * 设备有 root（KernelSU）时用 su 发送即可。
     */
    static boolean dismissSystemAlarm() {
        String[] cmds = {
                "am broadcast -a action.alarm_cancel -n com.android.deskclock/com.android.deskclock.AlarmReceiver",
                "am broadcast -a android.intent.action.DISMISS_ALARM -n com.android.deskclock/com.android.deskclock.AlarmReceiver",
        };
        for (String c : cmds) {
            try {
                String out = Root.exec(c, 5000);
                dbg("dismiss: " + c + " -> " + (out == null ? "" : out.trim().replace('\n', ' ')));
                if (out != null && out.contains("Broadcast completed")) return true;
            } catch (Throwable t) {
                dbg("dismiss error: " + t);
            }
        }
        return false;
    }

    /**
     * 通过界面点击真正关闭响铃中的闹钟。
     *
     * HyperOS 没有任何关闭闹钟的系统接口（cmd alarm 无 dismiss，
     * action.alarm_cancel 需要 Alarm parcelable 额外参数，DISMISS_ALARM 是空实现），
     * 所以只能：唤醒屏幕 → 确认前台确实是闹钟界面 → 找到「关闭」按钮 → input tap。
     * 任一步不满足就放弃，绝不盲点。
     */
    static boolean dismissViaUi() {
        try {
            Root.exec("input keyevent KEYCODE_WAKEUP", 4000);

            String top = Root.exec(
                    "dumpsys activity activities | grep -m1 topResumedActivity", 6000);
            if (top == null || !top.contains("AlarmAlertFullScreenActivity")) {
                dbg("dismissViaUi: 前台不是闹钟界面，放弃");
                return false;
            }

            Root.exec("uiautomator dump /sdcard/dc_alarm.xml", 15000);
            String xml = Root.exec("cat /sdcard/dc_alarm.xml", 8000);
            if (xml == null || !xml.contains("bounds")) return false;

            int[] xy = findDismissPoint(xml);
            if (xy == null) {
                dbg("dismissViaUi: 没找到关闭按钮");
                return false;
            }
            Root.exec("input tap " + xy[0] + " " + xy[1], 5000);
            dbg("dismissViaUi: tapped " + xy[0] + "," + xy[1]);
            return true;
        } catch (Throwable t) {
            dbg("dismissViaUi error: " + t);
            return false;
        }
    }

    /** 在 uiautomator dump 里找「关闭/Dismiss」节点，返回中心坐标。 */
    private static int[] findDismissPoint(String xml) {
        String[] keywords = {"关闭", "Dismiss", "dismiss", "停止"};
        for (String kw : keywords) {
            int idx = 0;
            while (true) {
                int t = xml.indexOf(kw, idx);
                if (t < 0) break;
                idx = t + kw.length();
                int nodeStart = xml.lastIndexOf("<node", t);
                int nodeEnd = xml.indexOf(">", t);
                if (nodeStart < 0 || nodeEnd < 0) continue;
                String node = xml.substring(nodeStart, nodeEnd);
                int b = node.indexOf("bounds=\"[");
                if (b < 0) continue;
                int e = node.indexOf("]", b);
                if (e < 0) continue;
                String[] parts = node.substring(b + 9, e).split("][");
                if (parts.length != 2) continue;
                try {
                    String[] p1 = parts[0].split(",");
                    String[] p2 = parts[1].split(",");
                    int x1 = Integer.parseInt(p1[0].trim()), y1 = Integer.parseInt(p1[1].trim());
                    int x2 = Integer.parseInt(p2[0].trim()), y2 = Integer.parseInt(p2[1].trim());
                    return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 静音闹钟音频流。
     *
     * 宿主 App（OPPO 健康）持有 android.permission.MODIFY_AUDIO_SETTINGS（已授予），
     * 所以在它进程内可以直接把 STREAM_ALARM 调到 0；到时间自动恢复原音量。
     *
     * @param restoreAfterSec 多少秒后恢复，<=0 表示不恢复
     * @return 是否成功静音
     */
    static boolean muteAlarmStream(int restoreAfterSec) {
        try {
            Context c = sCtx;
            if (c == null) return false;
            final android.media.AudioManager am =
                    (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;

            final int old = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM);
            if (old == 0) return true;
            // HyperOS 把闹钟流限制在最小 1，单纯 setStreamVolume(0) 只会变小，
            // 所以再补一次 ADJUST_MUTE（需要 MODIFY_AUDIO_SETTINGS，宿主 App 已授予）
            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, 0, 0);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_ALARM,
                    android.media.AudioManager.ADJUST_MUTE, 0);
            dbg("alarm stream muted (was " + old + ")");
            Logs.fire("闹钟联动", "已静音闹钟（原音量 " + old + "）");

            if (restoreAfterSec > 0) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_ALARM,
                                android.media.AudioManager.ADJUST_UNMUTE, 0);
                        am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, old, 0);
                        dbg("alarm stream restored to " + old);
                    } catch (Throwable ignored) {
                    }
                }, restoreAfterSec * 1000L);
            }
            return true;
        } catch (Throwable t) {
            dbg("mute failed: " + t);
            return false;
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
        if (USE_CLOCK_REPORT) {
            dbg("scheduleProxy skipped: clock reports ALARM_ALERT directly");
            return;
        }
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
        if (!syncEnabled()) {
            dbg("inject skipped: alarm sync disabled");
            return false;
        }
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
