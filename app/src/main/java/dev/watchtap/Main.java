package dev.watchtap;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * WatchTap — 读取 OPPO 健康收到的手表消息。
 *
 * 唯一的 hook 点：
 *   com.heytap.health.devicemanager.btclient.BTClient
 *     .onMessageReceived(String mac, MessageEvent event)
 *
 * 这是 App 内所有手表消息的唯一入口，内部再分发到
 * dispatchMsgResponse（请求回包）和 dispatchToListener（主动推送）。
 *
 * 输出：
 *   adb logcat -s WatchTap
 */
public class Main extends XposedModule {

    private static final String TAG = "WatchTap";
    private static final String PKG = "com.heytap.health";

    /** 分步启用：1=只放开联动闸门，2=同时拦截 DND 写入 */
    private static final int STEP = 2;
    private static final String CLS_BT =
            "com.heytap.health.devicemanager.btclient.BTClient";

    private XposedInterface api;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
        api = this;
        Log.i(TAG, "loaded in " + p.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        if (!PKG.equals(p.getPackageName())) return;
        Log.i(TAG, "target loaded, installing hook");

        try {
            install(p.getDefaultClassLoader());
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
        try {
            installSend(p.getDefaultClassLoader());
        } catch (Throwable t) {
            Log.e(TAG, "installSend failed", t);
        }
        try {
            LinkageHook.install(this, p.getDefaultClassLoader(), STEP);
            Log.i(TAG, "LinkageHook installed, STEP=" + STEP);
        } catch (Throwable t) {
            Log.e(TAG, "LinkageHook failed", t);
        }

        // 延迟拿 Context：onPackageLoaded 时 Application 还没创建
        scheduleCtxWarmup();
    }

    private void install(ClassLoader cl) throws Throwable {
        Class<?> bt = Class.forName(CLS_BT, false, cl);

        for (Method m : bt.getDeclaredMethods()) {
            if (!"onMessageReceived".equals(m.getName())) continue;
            if (m.getParameterCount() != 2) continue;
            if (m.getParameterTypes()[1] == null) continue;

            api.hook(m).intercept(chain -> {
                print(chain.getArg(0), chain.getArg(1));
                return chain.proceed();
            });

            Log.i(TAG, "hooked onMessageReceived");
            return;
        }
        Log.e(TAG, "onMessageReceived not found");
    }

    /**
     * hook 所有「手机 → 手表」的发送。
     *
     * BTClient 有 4 个 sendMsg 重载，最终都构造 CallbackMsg 走到 sendMsgInner。
     * 这里 hook 掉所有能被 hook 的重载，取其中的 MessageEvent 打印。
     *
     * 目的：看手机是否向手表上报 Build.BRAND / ROM 版本等系统信息。
     */
    private void installSend(ClassLoader cl) throws Throwable {
        Class<?> bt = Class.forName(CLS_BT, false, cl);

        int n = 0;
        for (Method m : bt.getDeclaredMethods()) {
            if (!"sendMsg".equals(m.getName())) continue;

            // 只处理参数里含 MessageEvent 的重载
            boolean hasEvent = false;
            for (Class<?> p : m.getParameterTypes()) {
                if (p != null && p.getName().endsWith("MessageEvent")) {
                    hasEvent = true;
                    break;
                }
            }
            if (!hasEvent) continue;

            api.hook(m).intercept(chain -> {
                try {
                    for (Object a : chain.getArgs()) {
                        if (a != null && a.getClass().getName().endsWith("MessageEvent")) {
                            boolean patched = tryPatchCapability(a);
                            printSend(a, patched);
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "send parse error", t);
                }
                return chain.proceed();
            });
            n++;
            Log.i(TAG, "hooked sendMsg/" + m.getParameterCount());
        }

        // 兜底：sendMsg(CallbackMsg) 与 sendMsgInner(CallbackMsg)
        for (String name : new String[]{"sendMsgInner", "sendMsg"}) {
            for (Method m : bt.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> p0 = m.getParameterTypes()[0];
                if (p0 == null || !p0.getName().endsWith("CallbackMsg")) continue;

                api.hook(m).intercept(chain -> {
                    try {
                        Object cb = chain.getArg(0);
                        Object ev = field(cb, "msgEvent");
                        if (ev != null) printSend(ev);
                    } catch (Throwable t) {
                        Log.w(TAG, "cb parse error", t);
                    }
                    return chain.proceed();
                });
                n++;
                Log.i(TAG, "hooked " + name + "(CallbackMsg)");
            }
        }

        Log.i(TAG, "send hooks installed=" + n);
    }

    /** 打印一条发出的消息。 */
    private void printSend(Object event) {
        printSend(event, false);
    }

    private void printSend(Object event, boolean patched) {
        try {
            int sid = (int) get(event, "getServiceId");
            int cid = (int) get(event, "getCommandId");
            byte[] data = (byte[]) get(event, "getData");
            int len = data == null ? 0 : data.length;

            String line = "SEND sid=" + sid
                    + " cid=0x" + Integer.toHexString(cid)
                    + " len=" + len
                    + " hex=" + hex(data)
                    + " str=" + printable(data)
                    + (patched ? " [PATCHED]" : "");

            // 对能力上报包打印调用栈，定位构造者
            if (sid == 1 && cid == 0x64) {
                StringBuilder sb = new StringBuilder("STACK for cid=0x64:\n");
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                for (int i = 0; i < Math.min(14, st.length); i++) {
                    sb.append("  at ").append(st[i]).append('\n');
                }
                append(sb.toString());
            }
            Log.i(TAG, line);
            append(line);
        } catch (Throwable t) {
            Log.w(TAG, "printSend error", t);
        }
    }

    /**
     * 尝试改写信封：把 SLEEP_SERVICE(0x2) 的 permission status 补成 1。
     *
     * 能力包结构（实测解析）：
     *   Permission { repeated FeatureItem features = 1; }
     *   FeatureItem { FeaturesType featureType = 1; repeated PermissionItem permissions = 2; }
     *   PermissionItem { int permission = 1; int status = 2; }
     *
     * 现状：SLEEP_SERVICE 的条目是 f1=2, f2[{f1=5}]
     *       —— permission=5 但 status 缺失（默认 0）。
     *       其它有权限的功能（如 RUN）是 f2[{f1=2},{f1=8,f2=1}]，带 status=1。
     *
     * 改写目标：把 SLEEP_SERVICE 那条补成 f2[{f1=5,f2=1}]，
     * 即 0a020805 -> 0a0408051001 的等价形式。
     *
     * 返回 true 表示确实改了。
     */
    private boolean tryPatchCapability(Object event) {
        try {
            int sid = (int) get(event, "getServiceId");
            int cid = (int) get(event, "getCommandId");
            if (sid != 1 || cid != 0x64) return false;

            byte[] data = (byte[]) get(event, "getData");
            if (data == null || data.length == 0) return false;

            byte[] patched = patchSleepService(data);
            if (patched == null || patched.length == data.length) return false;

            // 用反射把改后的字节写回 MessageEvent
            java.lang.reflect.Field f = null;
            for (Class<?> c = event.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField("mData");
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f == null) {
                Log.w(TAG, "mData field not found");
                return false;
            }
            f.setAccessible(true);
            f.set(event, patched);
            Log.i(TAG, "PATCH applied: " + data.length + " -> " + patched.length);
            append("PATCH applied: SLEEP_SERVICE status -> 1");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "patch failed", t);
            return false;
        }
    }

    /**
     * 在能力包里找到 FeatureItem{f1=2, f2=[{f1=5}]}，
     * 把内部的 PermissionItem 补上 status=1。
     *
     * 具体做法：识别字节序列
     *   0a 06 08 02 12 02 08 05      （FeatureItem, len=6, type=2, perms=[{p=5}]）
     * 改写为
     *   0a 08 08 02 12 04 08 05 10 01 （多 2 字节，permission 后加 status=1）
     *
     * 为了不依赖硬编码偏移，这里做通用的 protobuf 重写。
     */
    private byte[] patchSleepService(byte[] data) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int i = 0;
            boolean changed = false;

            while (i < data.length) {
                int tagStart = i;
                long tag = 0;
                int shift = 0;
                while (i < data.length) {
                    int cur = data[i++] & 0xFF;
                    tag |= (long) (cur & 0x7F) << shift;
                    if ((cur & 0x80) == 0) break;
                    shift += 7;
                }
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                byte[] tagBytes = java.util.Arrays.copyOfRange(data, tagStart, i);

                if (wire == 0) {
                    int vStart = i;
                    long v = 0;
                    shift = 0;
                    while (i < data.length) {
                        int cur = data[i++] & 0xFF;
                        v |= (long) (cur & 0x7F) << shift;
                        if ((cur & 0x80) == 0) break;
                        shift += 7;
                    }
                    out.write(tagBytes, 0, tagBytes.length);
                    out.write(data, vStart, i - vStart);
                } else if (wire == 2) {
                    int lenStart = i;
                    long len = 0;
                    shift = 0;
                    while (i < data.length) {
                        int cur = data[i++] & 0xFF;
                        len |= (long) (cur & 0x7F) << shift;
                        if ((cur & 0x80) == 0) break;
                        shift += 7;
                    }
                    byte[] lenBytes = java.util.Arrays.copyOfRange(data, lenStart, i);
                    byte[] sub = java.util.Arrays.copyOfRange(data, i, (int) (i + len));
                    i += (int) len;

                    // 是否是 features 字段(f=1) 且内部 featureType==2
                    byte[] newSub = null;
                    if (field == 1) {
                        newSub = patchFeatureItem(sub);
                    }

                    if (newSub != null && newSub.length != sub.length) {
                        out.write(tagBytes, 0, tagBytes.length);
                        writeVarint(out, newSub.length);
                        out.write(newSub, 0, newSub.length);
                        changed = true;
                    } else {
                        out.write(tagBytes, 0, tagBytes.length);
                        out.write(lenBytes, 0, lenBytes.length);
                        out.write(sub, 0, sub.length);
                    }
                } else {
                    // 不支持的 wire type，原样输出剩余
                    out.write(data, tagStart, data.length - tagStart);
                    break;
                }
            }
            return changed ? out.toByteArray() : null;
        } catch (Throwable t) {
            Log.w(TAG, "patchSleepService error", t);
            return null;
        }
    }

    /** 若 FeatureItem 的 featureType==2 且 permissions 里缺 status，则补齐。 */
    private byte[] patchFeatureItem(byte[] sub) {
        // 先看 featureType
        int ftype = -1;
        int i = 0;
        java.util.List<int[]> perms = new java.util.ArrayList<>();  // [start,end] of each perms entry
        while (i < sub.length) {
            int tagStart = i;
            long tag = 0;
            int shift = 0;
            while (i < sub.length) {
                int cur = sub[i++] & 0xFF;
                tag |= (long) (cur & 0x7F) << shift;
                if ((cur & 0x80) == 0) break;
                shift += 7;
            }
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);

            if (wire == 0) {
                long v = 0;
                shift = 0;
                while (i < sub.length) {
                    int cur = sub[i++] & 0xFF;
                    v |= (long) (cur & 0x7F) << shift;
                    if ((cur & 0x80) == 0) break;
                    shift += 7;
                }
                if (field == 1) ftype = (int) v;
            } else if (wire == 2) {
                long len = 0;
                shift = 0;
                while (i < sub.length) {
                    int cur = sub[i++] & 0xFF;
                    len |= (long) (cur & 0x7F) << shift;
                    if ((cur & 0x80) == 0) break;
                    shift += 7;
                }
                int subStart = i;
                i += (int) len;
                if (field == 2) {
                    perms.add(new int[]{tagStart, i});
                }
            } else {
                return null;
            }
        }

        if (ftype != 2) return null;       // 只处理 SLEEP_SERVICE

        // 检查是否已有 status
        boolean hasStatus = false;
        for (int[] r : perms) {
            byte[] p = java.util.Arrays.copyOfRange(sub, r[0], r[1]);
            if (containsStatus(p)) {
                hasStatus = true;
                break;
            }
        }
        if (hasStatus) return null;        // 已有，不动

        // 重建：在最后一个 perms 条目里补 status=1
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int prev = 0;
        for (int[] r : perms) {
            out.write(sub, prev, r[0] - prev);
            byte[] p = java.util.Arrays.copyOfRange(sub, r[0], r[1]);
            byte[] np = appendStatus(p);
            out.write(np, 0, np.length);
            prev = r[1];
        }
        out.write(sub, prev, sub.length - prev);
        return out.toByteArray();
    }

    /** 判断 PermissionItem 里是否含 f2(status)。 */
    private boolean containsStatus(byte[] p) {
        int i = 0;
        // 跳过 tag(2) 和 len(1)
        while (i < p.length && (p[i] & 0x80) != 0) i++;
        i++;
        while (i < p.length && (p[i] & 0x80) != 0) i++;
        i++;
        while (i < p.length) {
            int t = p[i++] & 0xFF;
            int f = t >> 3, w = t & 7;
            if (f == 2) return true;
            if (w == 0) {
                while (i < p.length && (p[i++] & 0x80) != 0) { }
            } else break;
        }
        return false;
    }

    /** 在 PermissionItem 末尾追加 f2=1，并修正外层长度。 */
    private byte[] appendStatus(byte[] p) {
        // p = [0x0a][len][payload...]
        int i = 0;
        while ((p[i] & 0x80) != 0) i++;
        int tagEnd = i + 1;
        int lenStart = tagEnd;
        while ((p[i] & 0x80) != 0) i++;
        int lenEnd = i + 1;
        byte[] payload = java.util.Arrays.copyOfRange(p, lenEnd, p.length);

        java.io.ByteArrayOutputStream pl = new java.io.ByteArrayOutputStream();
        pl.write(payload, 0, payload.length);
        pl.write(0x10);   // field 2, varint
        pl.write(0x01);   // value 1
        byte[] newPayload = pl.toByteArray();

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(p, 0, lenStart);
        writeVarint(out, newPayload.length);
        out.write(newPayload, 0, newPayload.length);
        return out.toByteArray();
    }

    private static void writeVarint(java.io.ByteArrayOutputStream out, long v) {
        while (true) {
            if ((v & ~0x7FL) == 0) {
                out.write((int) v);
                return;
            }
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
    }

    /** 取对象字段。 */
    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** 把 payload 里的可打印字符抽出来，便于找 ColorOS / Xiaomi 这类字符串。 */
    private static String printable(byte[] d) {
        if (d == null || d.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127) sb.append((char) c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '.') sb.append('.');
        }
        String s = sb.toString();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /** 打印一条消息：sid / cid / 长度 / MAC / payload hex */
    private void print(Object mac, Object event) {
        if (event == null) return;
        try {
            int sid = (int) get(event, "getServiceId");
            int cid = (int) get(event, "getCommandId");
            byte[] data = (byte[]) get(event, "getData");
            int len = data == null ? 0 : data.length;

            String line = "sid=" + sid
                    + " cid=0x" + Integer.toHexString(cid)
                    + " len=" + len
                    + " mac=" + mac
                    + " hex=" + hex(data);
            Log.i(TAG, line);
            append(line);
        } catch (Throwable t) {
            Log.w(TAG, "parse error", t);
        }
    }

    // ---------------------------------------------------------- 文件输出

    private static volatile Context sCtx;

    /** 启动后持续尝试获取 Context，拿到就缓存。 */
    private static void scheduleCtxWarmup() {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                if (sCtx != null) return;
                try {
                    Context c = ctx();
                    if (c != null) {
                        sCtx = c;
                        doAppend("(warmup at try " + tries + ")");
                        return;
                    }
                } catch (Throwable ignored) {
                }
                if (++tries < 30) h.postDelayed(this, 1000L);
            }
        }, 1000L);
    }

    /** 追加一行到 /sdcard/Android/data/com.heytap.health/files/watchtap.log */
    private static void append(final String line) {
        if (sCtx == null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Context c = ctx();
                    if (c != null) {
                        sCtx = c;
                        doAppend(line);
                    }
                } catch (Throwable ignored) {
                }
            });
            return;
        }
        doAppend(line);
    }

    private static void doAppend(String line) {
        try {
            Context c = sCtx;
            if (c == null) return;
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "watchtap.log");
            if (f.length() > 8L * 1024 * 1024) return;

            java.io.FileWriter w = new java.io.FileWriter(f, true);
            try {
                w.write(line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static Context ctx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return ((Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object get(Object o, String name) throws Exception {
        Method m = o.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    private static String hex(byte[] d) {
        if (d == null || d.length == 0) return "";
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
