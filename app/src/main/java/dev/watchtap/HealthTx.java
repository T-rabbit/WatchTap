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
        AlarmLink.dbg("capture() entered, evIdx=" + evIdx);
        try {
            if (sSender != null && sMac != null) {
                AlarmLink.dbg("capture: already have mac=" + sMac);
                return;
            }
            Object self = null;
            try { self = chain.getThisObject(); } catch (Throwable t) {
                AlarmLink.dbg("capture: getThisObject threw " + t);
            }
            AlarmLink.dbg("capture: self=" + (self == null ? "null" : self.getClass().getName()));
            // 参数位置按实际类型定位，不假设固定下标
            Object role = null;
            String mac = null;
            Object ev = chain.getArg(evIdx);
            // 诊断：打印每个参数的类型与摘要
            try {
                StringBuilder sb = new StringBuilder("args:");
                for (int i = 0; i < 4; i++) {
                    Object a = null;
                    try { a = chain.getArg(i); } catch (Throwable ignored) {}
                    sb.append(" [").append(i).append("]=")
                      .append(a == null ? "null" : a.getClass().getSimpleName())
                      .append("(").append(a == null ? "" : String.valueOf(a)).append(")");
                }
                AlarmLink.dbg(sb.toString());
            } catch (Throwable ignored) {
            }
            for (int i = 0; i < 4; i++) {
                if (i == evIdx) continue;
                Object a = null;
                try {
                    a = chain.getArg(i);
                } catch (Throwable ignored) {
                    continue;
                }
                if (a == null) continue;
                if (mac == null && a instanceof String) {
                    String sv = (String) a;
                    if (sv.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) mac = sv;
                } else if (role == null && !(a instanceof String) && !(a instanceof byte[])) {
                    role = a;
                }
            }
            if (mac == null) return;
            sSender = self;
            sRole = role;
            sMac = mac;
            sSendMethod = sendMethod;
            sEventCls = chain.getArg(evIdx).getClass();
            AlarmLink.dbg("HealthTx-OK-v2 mac=" + sMac);
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
            // 三参构造器保留 SDK 默认 transport=2 (BT)、encrypt=0。
            // 四参构造器的第三参是 transport，不能当 encrypt 传 1（Wi-Fi）。
            java.lang.reflect.Constructor<?> ctor = sEventCls.getDeclaredConstructor(
                    int.class, int.class, byte[].class);
            ctor.setAccessible(true);
            Object ev = ctor.newInstance(sid, cid, payload);
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
