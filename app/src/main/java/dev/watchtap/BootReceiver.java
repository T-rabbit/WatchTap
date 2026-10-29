package dev.watchtap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：重启后恢复保活服务。
 *
 * 没有它的话，手机重启后前台服务不会自动起来，
 * 看门狗也就停了，OPPO 健康被杀了没人管。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (action == null) return;

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        Logs.fire("保活", "收到 " + action + "，恢复保活服务");

        // 需要确认用户是否开启过保活
        try {
            KeepAliveService.start(ctx);
            // 重新应用保活设置（重启后白名单可能失效）
            if (Root.available()) {
                KeepAlive.apply();
            }
        } catch (Throwable t) {
            Logs.w("开机恢复保活失败", t);
        }
    }
}
