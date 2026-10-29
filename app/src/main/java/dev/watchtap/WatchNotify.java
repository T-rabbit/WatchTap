package dev.watchtap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * 睡眠模式事件通知。
 *
 * ==== 用途 ====
 * 手表进入/退出睡眠模式时，发一条通知。其他自动化软件（Tasker、MacroDroid、
 * AutoNotification 等）可以通过监听通知来触发自己的动作。
 *
 * ==== 通道设计 ====
 * 通道重要性设为 LOW：不发声、不弹窗、不出现在锁屏，只在通知栏留一条记录。
 * 这样自动化软件能读到，又不会打扰用户。
 *
 * 通道一旦创建，重要性就不能再改（Android 限制）。所以通道 ID 里带上
 * 「重要级别」信息，改设计时用新 ID 即可。
 *
 * ==== 自动化识别方式 ====
 * 通知里的关键信息：
 *   - 通道 ID：watchtap_sleep
 *   - 标题：「手表已进入睡眠模式」/「手表已退出睡眠模式」
 *   - 包名：dev.watchtap
 *   - extra：EXTRA_STATE / EXTRA_SLEEP_MODE / EXTRA_TIME
 *
 * Tasker 里可以用 Profile → Event → UI → Notification，选择本应用，
 * 再用 %evtprm 系列变量读取标题。
 */
final class WatchNotify {

    private static final String CHANNEL_ID = "watchtap_sleep_v1";
    private static final String CHANNEL_NAME = "手表睡眠模式";

    static final String EXTRA_STATE = "watchtap_sleep_state";
    static final String EXTRA_SLEEP_MODE = "watchtap_sleep_mode";
    static final String EXTRA_TIME = "watchtap_time";

    static final int NOTIFY_ID = 1001;

    private WatchNotify() {}

    /** 确保通知通道存在。 */
    private static void ensureChannel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("手表进入或退出睡眠模式时发出，供自动化软件监听");
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            ch.setBypassDnd(true);   // 勿扰时也能发出，否则自动化收不到
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Logs.w("创建通知通道失败", t);
        }
    }

    /**
     * 发一条睡眠模式通知。
     *
     * @param on  true=进入睡眠模式，false=退出
     */
    static boolean send(Context ctx, boolean on) {
        if (ctx == null) {
            Logs.e("发送通知失败：拿不到上下文", null);
            return false;
        }
        try {
            ensureChannel(ctx);

            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;

            String title = on ? "手表已进入睡眠模式" : "手表已退出睡眠模式";
            String text = on
                    ? "可用于触发其他自动化动作"
                    : "睡眠模式已结束";

            Intent intent = new Intent(ctx, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                    .setContentIntent(pi)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build();

            // 附加数据供自动化读取
            Bundle extras = n.extras;
            if (extras == null) extras = new Bundle();
            extras.putString(EXTRA_STATE, on ? "asleep" : "awake");
            extras.putInt(EXTRA_SLEEP_MODE, on ? 1 : 0);
            extras.putLong(EXTRA_TIME, System.currentTimeMillis());
            n.extras = extras;

            nm.notify(NOTIFY_ID, n);
            Logs.fire("通知", "已发送「" + title + "」");
            return true;
        } catch (Throwable t) {
            Logs.e("发送通知失败", t);
            return false;
        }
    }

    /** 撤回通知（避免堆积）。 */
    static void cancel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFY_ID);
        } catch (Throwable ignored) {
        }
    }
}
