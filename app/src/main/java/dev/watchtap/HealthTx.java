package dev.watchtap;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;

/**
 * 主动向手表发请求（复用 OPPO 健康自己的发送通道）。
 *
 * 为什么需要它：
 *   心率数据不是手表主动推的，而是 App 发请求后手表才回一批。
 *   请求体就 12 字节：{field1=起始时间戳, field2=结束时间戳}。
 *   想「手动上报一次」就必须自己构造 MessageEvent 并发出去。
 *
 * 发送方法：com.heytap.health.devicemanager.client.impl.multiple.k#h(
 *              role(gt.g), mac(String), MessageEvent, callback)
 *   role 和 mac 是每次真实发送时截获保存的，自己造不出来。
 */
final class HealthTx {

    /** 保存自真实调用：发送器实例、role、mac */
    private static volatile Object sSender;
    private static volatile Object sRole;
    private static volatile String sMac;
    private static volatile Method sSendMethod;
    private static volatile Class<?> sEventCls;

    private HealthTx() {}

    /** 在 SEND hook 里调用，保存句柄。 */
    static void capture(XposedInterface.Chain chain, Method sendMethod, int evIdx) {
        try {
            if (sSender != null && sMac != null) return;   // 已有就不重复取
            Object self = chain.getThisObject();
            Object role = chain.getArg(0);
            Object mac = chain.getArg(1);
            if (self == null || mac == null) return;
            sSender = self;
            sRole = role;
            sMac = String.valueOf(mac);
            sSendMethod = sendMethod;
            sEventCls = chain.getArg(evIdx).getClass();
            AlarmLink.dbg("HealthTx captured: mac=" + sMac);
        } catch (Throwable ignored) {
        }
    }

    static boolean ready() {
        return sSender != null && sMac != null && sSendMethod != null && sEventCls != null;
    }

    static String status() {
        if (ready()) return "就绪（mac=" + sMac + "）";
        return "未就绪：还没有截获到一次真实发送";
    }

    /**
     * 发一个请求。
     *
     * @param sid serviceId
     * @param cid commandId
     * @param payload 数据体
     */
    static boolean request(int sid, int cid, byte[] payload) {
        if (!ready()) return false;
        try {
            // MessageEvent(int serviceId, int commandId, int encrypt, byte[] data)
            Object ev = null;
            for (java.lang.reflect.Constructor<?> ctor : sEventCls.getDeclaredConstructors()) {
                Class<?>[] ps = ctor.getParameterTypes();
                if (ps.length == 4 && ps[0] == int.class && ps[1] == int.class
                        && ps[2] == int.class && ps[3] == byte[].class) {
                    ctor.setAccessible(true);
                    ev = ctor.newInstance(sid, cid, 1 /* ENCRYPT_NO */, payload);
                    break;
                }
            }
            if (ev == null) {
                AlarmLink.dbg("HealthTx: 找不到 MessageEvent(int,int,int,byte[]) 构造");
                return false;
            }
            sSendMethod.setAccessible(true);
            Object r = sSendMethod.invoke(sSender, sRole, sMac, ev, null);
            AlarmLink.dbg("HealthTx.request sid=" + sid + " cid=" + cid
                    + " len=" + payload.length + " ret=" + r);
            return Boolean.TRUE.equals(r) || r == null;
        } catch (Throwable t) {
            AlarmLink.dbg("HealthTx.request failed: " + t);
            return false;
        }
    }
}
