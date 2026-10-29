package dev.watchtap;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * WatchTap 入口。
 *
 * 两个采集点：
 *   1. SleepModeManager.onReceiveDeviceSleepModeData —— 手表上报睡眠模式
 *   2. BTClient.onMessageReceived —— 协议层兜底（cid=0xcc 里含 startNow）
 *
 * 所有 hook 都只读数据，不改 App 行为。勿扰由模块自己写（provider 进程）。
 */
public class Main extends XposedModule {

    private static final String PKG = "com.heytap.health";

    private XposedInterface api;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
        api = this;
        Logs.fire("module.loaded", "process=" + p.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        if (!PKG.equals(p.getPackageName())) return;
        Logs.fire("target.loaded", "pkg=" + PKG);

        ClassLoader cl = p.getDefaultClassLoader();

        try {
            hookSleepMode(cl);
        } catch (Throwable t) {
            Logs.e("hookSleepMode failed", t);
        }
        try {
            hookSleepState(cl);
        } catch (Throwable t) {
            Logs.e("hookSleepState failed", t);
        }
        try {
            hookMessages(cl);
        } catch (Throwable t) {
            Logs.e("hookMessages failed", t);
        }

        warmup();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam p) {
        if (!PKG.equals(p.getPackageName())) return;
        Logs.fire("target.ready", "application created");
        State.attach(appCtx());
        State.syncZen();
    }

    // ------------------------------------------------------------ 睡眠模式

    /**
     * 手表睡眠模式开关。
     *
     * SleepModeManager.onReceiveDeviceSleepModeData(SleepModelSettings)
     * 里能拿到 isStartNow()，这是可靠的上报路径。
     */
    private void hookSleepMode(ClassLoader cl) {
        Class<?> c = load(cl, "com.heytap.device.sleep.SleepModeManager");
        if (c == null) {
            Logs.e("SleepModeManager NOT FOUND", null);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"onReceiveDeviceSleepModeData".equals(m.getName())) continue;
            if (m.getParameterCount() != 1) continue;

            api.hook(m).intercept(chain -> {
                try {
                    Object setting = chain.getArg(0);
                    boolean startNow = bool(setting, "isStartNow");
                    long ts = longVal(setting, "getTimestamp");
                    Logs.fire("HOOK.sleepMode",
                            "startNow=" + startNow + " ts=" + ts);
                    State.setSleepMode(startNow ? 1 : 0, ts, true);
                } catch (Throwable t) {
                    Logs.e("hook.sleepMode parse error", t);
                }
                return chain.proceed();
            });
            n++;
        }
        Logs.fire("hook.sleepMode", n > 0 ? "installed=" + n : "NOT FOUND");
    }

    // ------------------------------------------------------------ 生理睡眠

    private void hookSleepState(ClassLoader cl) {
        Class<?> c = load(cl,
                "com.heytap.device.data.sporthealth.repository.SleepStateRepository$Companion");
        if (c == null) {
            Logs.e("SleepStateRepository NOT FOUND", null);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"reportSleepState".equals(m.getName())) continue;
            if (m.getParameterCount() != 2) continue;

            api.hook(m).intercept(chain -> {
                try {
                    int state = ((Number) chain.getArg(0)).intValue();
                    long ts = ((Number) chain.getArg(1)).longValue();
                    Logs.fire("HOOK.sleepState", "state=" + state + " ts=" + ts);
                    State.setSleepState(state, ts);
                } catch (Throwable t) {
                    Logs.e("hook.sleepState parse error", t);
                }
                return chain.proceed();
            });
            n++;
        }
        Logs.fire("hook.sleepState", n > 0 ? "installed=" + n : "NOT FOUND");
    }

    // ------------------------------------------------------------ 消息层兜底

    /**
     * hook 所有手表消息。
     *
     * 主要作用：从 cid=0xcc 的 payload 里补睡眠模式状态（field3）。
     * 实测这条消息在 App 主动读取时返回完整状态，是 onReceiveDeviceSleepModeData
     * 之外的第二个来源。
     */
    private void hookMessages(ClassLoader cl) {
        Class<?> c = load(cl, "com.heytap.health.devicemanager.btclient.BTClient");
        if (c == null) {
            Logs.e("BTClient NOT FOUND", null);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"onMessageReceived".equals(m.getName())) continue;
            if (m.getParameterCount() != 2) continue;

            api.hook(m).intercept(chain -> {
                try {
                    Object event = chain.getArg(1);
                    if (event != null) {
                        int sid = (int) call(event, "getServiceId");
                        int cid = (int) call(event, "getCommandId");
                        byte[] data = (byte[]) call(event, "getData");
                        int len = data == null ? 0 : data.length;
                        State.noteMessage(sid, cid, len);

                        // 0xcc / 0x49 = 读睡眠设置回包，field3 = startNow
                        if (sid == 5 && (cid == 0xcc || cid == 0x49) && len >= 4) {
                            int f3 = field3(data);
                            if (f3 >= 0) {
                                Logs.fire("HOOK.msg.sleepMode",
                                        "cid=0x" + Integer.toHexString(cid)
                                                + " field3=" + f3 + " len=" + len);
                                State.setSleepMode(f3 == 1 ? 1 : 0,
                                        System.currentTimeMillis() / 1000L, true);
                            } else {
                                Logs.fire("HOOK.msg.sleepMode",
                                        "cid=0x" + Integer.toHexString(cid)
                                                + " no field3 (len=" + len + ")");
                            }
                        }
                    }
                } catch (Throwable t) {
                    Logs.e("hook.msg parse error", t);
                }
                return chain.proceed();
            });
            n++;
            break;
        }
        Logs.fire("hook.messages", n > 0 ? "installed" : "NOT FOUND");
    }

    /** 取 protobuf 里 field3 的值，不存在返回 -1。 */
    private int field3(byte[] d) {
        int i = 0;
        while (i < d.length) {
            long tag = 0;
            int shift = 0;
            while (i < d.length) {
                int cur = d[i++] & 0xFF;
                tag |= (long) (cur & 0x7F) << shift;
                if ((cur & 0x80) == 0) break;
                shift += 7;
            }
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long v = 0;
                shift = 0;
                while (i < d.length) {
                    int cur = d[i++] & 0xFF;
                    v |= (long) (cur & 0x7F) << shift;
                    if ((cur & 0x80) == 0) break;
                    shift += 7;
                }
                if (field == 3 && v <= 1) return (int) v;
            } else if (wire == 2) {
                long len = 0;
                shift = 0;
                while (i < d.length) {
                    int cur = d[i++] & 0xFF;
                    len |= (long) (cur & 0x7F) << shift;
                    if ((cur & 0x80) == 0) break;
                    shift += 7;
                }
                i += (int) len;
            } else if (wire == 5) {
                i += 4;
            } else if (wire == 1) {
                i += 8;
            } else {
                break;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ 工具

    private void warmup() {
        final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                android.content.Context c = appCtx();
                if (c != null) {
                    State.attach(c);
                    State.syncZen();
                    Logs.fire("context.ready", "try=" + tries);
                    return;
                }
                if (++tries < 60) h.postDelayed(this, 500L);
                else Logs.e("context NOT available after 30s", null);
            }
        }, 500L);
    }

    private static android.content.Context appCtx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object call(Object o, String name) throws Exception {
        Method m = o.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    private static boolean bool(Object o, String name) {
        try {
            return Boolean.TRUE.equals(call(o, name));
        } catch (Throwable t) {
            return false;
        }
    }

    private static long longVal(Object o, String name) {
        try {
            Object r = call(o, name);
            if (r instanceof Long) return (Long) r;
            if (r instanceof Integer) return ((Integer) r).longValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }
}
