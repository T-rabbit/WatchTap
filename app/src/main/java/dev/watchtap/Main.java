package dev.watchtap;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * WatchTap 入口。
 *
 * 做三件事：
 *   1. 采集手表数据（心率 / 睡眠模式 / 生理睡眠）
 *   2. 手表睡眠模式变化 → 自动切换手机勿扰
 *   3. 把状态推给 UI 进程
 *
 * 所有 hook 都装在 com.heytap.health 进程内，只读它的数据，不改它的行为。
 * 勿扰写入由模块自己完成（provider 进程），不依赖 App 的 ColorOS 代码路径。
 */
public class Main extends XposedModule {

    private static final String TAG = "WatchTap";
    private static final String PKG = "com.heytap.health";

    private XposedInterface api;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
        api = this;
        Log.i(TAG, "loaded in " + p.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        if (!PKG.equals(p.getPackageName())) return;
        Log.i(TAG, "=== target loaded ===");

        ClassLoader cl = p.getDefaultClassLoader();

        try {
            hookHeartRate(cl);
        } catch (Throwable t) {
            Log.e(TAG, "hookHeartRate failed", t);
        }
        try {
            hookSleepMode(cl);
        } catch (Throwable t) {
            Log.e(TAG, "hookSleepMode failed", t);
        }
        try {
            hookSleepState(cl);
        } catch (Throwable t) {
            Log.e(TAG, "hookSleepState failed", t);
        }
        try {
            hookMessages(cl);
        } catch (Throwable t) {
            Log.e(TAG, "hookMessages failed", t);
        }

        // Context 要等 Application 创建，延迟拿
        warmupContext();
    }

    // ------------------------------------------------------------ 心率

    /**
     * 心率数据入口。
     *
     * HRDataFetcher.processPacketData(byte[]) 解析 FitnessProtoV2.HeartRateDataV2，
     * 这是实时心率（BLE 推送），不是历史批量同步。
     */
    private void hookHeartRate(ClassLoader cl) {
        Class<?> c = load(cl, "com.heytap.device.data.sporthealth.pull.fetcher.HRDataFetcher");
        if (c == null) {
            Log.e(TAG, "HRDataFetcher not found");
            return;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!"processPacketData".equals(m.getName())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != byte[].class) continue;

            api.hook(m).intercept(chain -> {
                try {
                    byte[] data = (byte[]) chain.getArg(0);
                    int bpm = parseHeartRate(data);
                    if (bpm > 0) {
                        State.setHeartRate(bpm, System.currentTimeMillis() / 1000L);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "hr parse error", t);
                }
                return chain.proceed();
            });
            Log.i(TAG, "hooked HRDataFetcher.processPacketData");
            return;
        }
        Log.e(TAG, "processPacketData not found");
    }

    /**
     * 从心率包解出 bpm。
     *
     * 结构（实测）：HeartRateDataV2 { repeated Item data = 1; }
     *               Item { sint32 heartRate = 1; ... }
     * 取最后一个非 0 值。
     */
    private int parseHeartRate(byte[] d) {
        if (d == null || d.length < 3) return -1;
        int last = -1;
        int i = 0;
        while (i < d.length) {
            long tag = 0;
            int shift = 0;
            while (i < d.length) {
                int cur = d[i++] & 0xFF;
                tag |= (long) (cur & 0x7F) << shift;
                if ((cur & 0x80) == 0) break;
                shift += 7;
                if (shift > 63) return last;
            }
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);

            if (wire == 2) {
                long len = 0;
                shift = 0;
                while (i < d.length) {
                    int cur = d[i++] & 0xFF;
                    len |= (long) (cur & 0x7F) << shift;
                    if ((cur & 0x80) == 0) break;
                    shift += 7;
                }
                int end = (int) Math.min(d.length, i + len);
                if (field == 1) {
                    int v = scanHeartRate(d, i, end);
                    if (v > 0) last = v;
                }
                i = end;
            } else if (wire == 0) {
                shift = 0;
                while (i < d.length) {
                    int cur = d[i++] & 0xFF;
                    if ((cur & 0x80) == 0) break;
                    shift += 7;
                }
            } else if (wire == 5) {
                i += 4;
            } else if (wire == 1) {
                i += 8;
            } else {
                break;
            }
        }
        return last;
    }

    /** 在一个 Item 子包里找 heartRate（field 1, varint）。 */
    private int scanHeartRate(byte[] d, int start, int end) {
        int i = start;
        while (i < end && i < d.length) {
            long tag = 0;
            int shift = 0;
            while (i < end && i < d.length) {
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
                while (i < end && i < d.length) {
                    int cur = d[i++] & 0xFF;
                    v |= (long) (cur & 0x7F) << shift;
                    if ((cur & 0x80) == 0) break;
                    shift += 7;
                }
                if (field == 1 && v > 0 && v <= 250) return (int) v;
            } else if (wire == 2) {
                long len = 0;
                shift = 0;
                while (i < end && i < d.length) {
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

    // ------------------------------------------------------------ 睡眠模式

    /**
     * 手表睡眠模式开关。
     *
     * 两条路径都要挂：
     *   1. SleepModeManager.onReceiveDeviceSleepModeData(SleepModelSettings)
     *      —— 手表上报（可靠，带 startNow）
     *   2. BTClient 消息里的 cid=0xcc —— 原始协议层兜底
     *      payload 里 field3 存在即开启
     */
    private void hookSleepMode(ClassLoader cl) {
        Class<?> c = load(cl, "com.heytap.device.sleep.SleepModeManager");
        if (c == null) {
            Log.e(TAG, "SleepModeManager not found");
            return;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!"onReceiveDeviceSleepModeData".equals(m.getName())) continue;
            if (m.getParameterCount() != 1) continue;

            api.hook(m).intercept(chain -> {
                try {
                    Object setting = chain.getArg(0);
                    boolean startNow = bool(setting, "isStartNow");
                    long ts = longVal(setting, "getTimestamp");
                    Log.i(TAG, "SLEEPMODE data startNow=" + startNow + " ts=" + ts);
                    State.setSleepMode(startNow ? 1 : 0, ts, true);
                } catch (Throwable t) {
                    Log.w(TAG, "sleepmode parse error", t);
                }
                return chain.proceed();
            });
            Log.i(TAG, "hooked onReceiveDeviceSleepModeData");
        }
    }

    // ------------------------------------------------------------ 生理睡眠

    private void hookSleepState(ClassLoader cl) {
        Class<?> c = load(cl,
                "com.heytap.device.data.sporthealth.repository.SleepStateRepository$Companion");
        if (c == null) {
            Log.e(TAG, "SleepStateRepository not found");
            return;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!"reportSleepState".equals(m.getName())) continue;
            if (m.getParameterCount() != 2) continue;

            api.hook(m).intercept(chain -> {
                try {
                    int state = ((Number) chain.getArg(0)).intValue();
                    long ts = ((Number) chain.getArg(1)).longValue();
                    Log.i(TAG, "SLEEPSTATE state=" + state + " ts=" + ts);
                    State.setSleepState(state, ts);
                } catch (Throwable t) {
                    Log.w(TAG, "sleepstate parse error", t);
                }
                return chain.proceed();
            });
            Log.i(TAG, "hooked reportSleepState");
        }
    }

    // ------------------------------------------------------------ 消息层兜底

    /**
     * hook 所有手表消息，从 cid=0xcc 里补睡眠模式状态。
     *
     * 同时也记录最近一条消息，方便 UI 上排查。
     */
    private void hookMessages(ClassLoader cl) {
        Class<?> c = load(cl, "com.heytap.health.devicemanager.btclient.BTClient");
        if (c == null) return;

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

                        // 0xcc = 读/回睡眠设置，field3 存在表示开启
                        if (sid == 5 && (cid == 0xcc || cid == 0x49) && len >= 4) {
                            int f3 = field3(data);
                            if (f3 >= 0) {
                                Log.i(TAG, "SLEEPMODE 0xcc field3=" + f3);
                                State.setSleepMode(f3 == 1 ? 1 : 0,
                                        System.currentTimeMillis() / 1000L, true);
                            }
                        }
                        State.push();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "msg parse error", t);
                }
                return chain.proceed();
            });
            Log.i(TAG, "hooked onMessageReceived");
            return;
        }
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

    private void warmupContext() {
        final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                if (State.ctx() != null) {
                    State.syncZen();
                    return;
                }
                if (++tries < 40) h.postDelayed(this, 500L);
            }
        }, 500L);
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
