package dev.watchtap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 前台服务：让模块自己不被杀，同时充当 OPPO 健康的看门狗。
 *
 * ==== 为什么用前台服务 ====
 * 普通后台服务在 Android 8+ 会被限制，长时间运行时容易被回收。
 * 前台服务带常驻通知，优先级高，能一直跑。
 *
 * ==== 看门狗逻辑 ====
 * 每 60 秒检查一次：
 *   1. OPPO 健康进程是否存活
 *   2. 保活设置是否被系统重置（比如重启后白名单可能失效）
 *
 * 进程死了就拉起，设置丢了就重设。
 *
 * ==== 注意 ====
 * 前台服务本身不需要 root，但「拉起 OPPO 健康」和「设置白名单」需要。
 * 没有 root 时服务仍然运行，只是不做这两件事。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "watchtap_keepalive";
    private static final int NOTIFY_ID = 2001;
    private static final long INTERVAL_MS = 60_000L;

    private Handler mHandler;
    private volatile boolean mRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFY_ID, buildNotification("正在监控 OPPO 健康"));

        if (!mRunning) {
            mRunning = true;
            mHandler.postDelayed(mTick, 5000L);
            Logs.fire("保活", "前台服务已启动");
        }
        // 被系统杀掉后自动重启
        return START_STICKY;
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            try {
                check();
            } catch (Throwable t) {
                Logs.w("保活检查失败", t);
            }
            mHandler.postDelayed(this, INTERVAL_MS);
        }
    };

    /** 一次检查。 */
    private void check() {
        if (!Root.available()) {
            updateNotification("无 root，仅保持模块存活");
            return;
        }

        boolean alive = KeepAlive.isRunning();
        if (!alive) {
            Logs.w("保活：检测到 OPPO 健康已停止", null);
            boolean ok = KeepAlive.wake();
            updateNotification(ok ? "已重新拉起 OPPO 健康" : "拉起失败，请手动打开");
            return;
        }

        // 抽查保活设置是否还在（每 10 次查一次，减少开销）
        if (++mCheckCount % 10 == 0) {
            KeepAlive.Status st = KeepAlive.status();
            if (!st.allGood()) {
                Logs.fire("保活", "检测到设置被重置，重新应用");
                KeepAlive.apply();
            }
            updateNotification("OPPO 健康运行中 (pid " + st.pid + ")");
        }
    }

    private int mCheckCount;

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFY_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        ensureChannel(this);

        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("WatchTap 保活中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pi)
                .setOngoing(true)          // 不可滑动清除
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private static void ensureChannel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "保活服务", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持模块运行，并在手表应用被停止时自动拉起");
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
        Logs.fire("保活", "前台服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================================================================ 启停

    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Logs.fire("保活", "启动前台服务");
        } catch (Throwable t) {
            Logs.w("启动前台服务失败", t);
        }
    }

    static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
            Logs.fire("保活", "停止前台服务");
        } catch (Throwable t) {
            Logs.w("停止前台服务失败", t);
        }
    }
}
