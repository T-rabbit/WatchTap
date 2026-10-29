package dev.watchtap;

import android.app.NotificationManager;
import android.content.Context;
import android.provider.Settings;

/**
 * 勿扰模式读写。
 *
 * ==== 为什么模块自己控制 ====
 * 原方案是 hook OPPO 健康的 ColorOS 私有写入路径（OPlusNotificationUtils /
 * NotificationManagerNative），让它自己调系统 API。
 *
 * 但实测发现 App 的 changePhoneDND 走的是 NotificationManagerNative 分支
 * （isFromColorOS15() 为 false），内部经 Epona 反射系统私有接口，在 HyperOS
 * 上必然失败，且异常被 App 自己吞掉，日志里只能看到 "Change phone zenMode error="。
 *
 * 所以改成：模块直接调标准 Android API 写勿扰。绕开 App 的一切内部逻辑。
 *
 * ==== 前提 ====
 * 需要 ACCESS_NOTIFICATION_POLICY 权限，否则 setInterruptionFilter 抛
 * SecurityException。授权方式：
 *   adb shell cmd notification allow_dnd dev.watchtap
 * 或 设置 → 通知 → 勿扰权限访问 → WatchTap
 *
 * 注：hook 进程（com.heytap.health）调用时用的是宿主权限，宿主没有这个权限。
 * 所以实际写入由 provider 进程（dev.watchtap，有权限）执行。
 */
final class Dnd {

    /** NotificationManager.INTERRUPTION_FILTER_ALL */
    static final int FILTER_ALL = 1;
    /** NotificationManager.INTERRUPTION_FILTER_ALARMS，只放行闹钟 */
    static final int FILTER_ALARMS = 5;

    /** zen_mode 值：0=关 1=仅优先 2=完全静音 3=仅闹钟 */
    static final int ZEN_OFF = 0;
    static final int ZEN_ALARMS = 3;

    private Dnd() {}

    /**
     * 设置勿扰。
     *
     * @param on true → 只放行闹钟（zen_mode=3）；false → 关闭（zen_mode=0）
     * @return 是否成功
     */
    static boolean set(Context ctx, boolean on) {
        if (ctx == null) return false;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;

            int filter = on ? FILTER_ALARMS : FILTER_ALL;
            nm.setInterruptionFilter(filter);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读当前 zen_mode。 */
    static int zen(Context ctx) {
        if (ctx == null) return -1;
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "zen_mode", 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 是否有勿扰授权。 */
    static boolean hasAccess(Context ctx) {
        if (ctx == null) return false;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationPolicyAccessGranted();
        } catch (Throwable t) {
            return false;
        }
    }
}
