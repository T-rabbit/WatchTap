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
        Logs.fire("模块", "已加载到 " + p.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        if (!PKG.equals(p.getPackageName())) return;
        Logs.fire("模块", "开始接管 OPPO 健康");

        ClassLoader cl = p.getDefaultClassLoader();

        try {
            hookHeartRate(cl);
        } catch (Throwable t) {
            Logs.e("心率监听安装失败", t);
        }
        try {
            hookSleepMode(cl);
        } catch (Throwable t) {
            Logs.e("睡眠模式监听安装失败", t);
        }
        try {
            hookSleepState(cl);
        } catch (Throwable t) {
            Logs.e("生理睡眠监听安装失败", t);
        }
        try {
            hookMessages(cl);
        } catch (Throwable t) {
            Logs.e("消息监听安装失败", t);
        }
        try {
            HealthHook.install(this, cl);
        } catch (Throwable t) {
            Logs.e("健康数据采集安装失败", t);
        }

        warmup();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam p) {
        if (!PKG.equals(p.getPackageName())) return;
        Logs.fire("模块", "OPPO 健康已就绪");
        State.attach(appCtx());
        State.syncZen();
    }

    // ------------------------------------------------------------ 心率

    /**
     * 实时心率。
     *
     * HRDataFetcher.processPacketData(byte[]) 解析 FitnessProtoV2.HeartRateDataV2，
     * 这是 BLE 实时推送，比数据库里的值新。
     */
    private void hookHeartRate(ClassLoader cl) {
        Class<?> c = load(cl,
                "com.heytap.device.data.sporthealth.pull.fetcher.HRDataFetcher");
        if (c == null) {
            Logs.i("未找到实时心率接口，将从数据库读取");
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"processPacketData".equals(m.getName())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != byte[].class) continue;

            api.hook(m).intercept(chain -> {
                try {
                    int bpm = parseHeartRate((byte[]) chain.getArg(0));
                    if (bpm > 0) {
                        if (bpm != sLastHr) {
                            sLastHr = bpm;
                            Logs.fire("心率", bpm + " bpm");
                        }
                        State.record(Metrics.HR, bpm);
                    }
                } catch (Throwable t) {
                    Logs.e("解析心率失败", t);
                }
                return chain.proceed();
            });
            n++;
        }
        Logs.fire("监听", n > 0 ? "心率监听已就位" : "未找到心率接口");
    }

    private int sLastHr = -1;

    /** 从心率包解出 bpm（取最后一个有效值）。 */
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
                    int v = scanHr(d, i, end);
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

    private int scanHr(byte[] d, int start, int end) {
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
     * SleepModeManager.onReceiveDeviceSleepModeData(SleepModelSettings)
     * 里能拿到 isStartNow()，这是可靠的上报路径。
     */
    private void hookSleepMode(ClassLoader cl) {
        Class<?> c = load(cl, "com.heytap.device.sleep.SleepModeManager");
        if (c == null) {
            Logs.e("未找到睡眠模式相关类，可能版本不匹配", null);
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
                    Logs.fire("手表上报", "睡眠模式=" + (startNow ? "开" : "关")
                            + "，时间 " + fmtTs(ts));
                    State.setSleepMode(startNow ? 1 : 0, ts, true);
                } catch (Throwable t) {
                    Logs.e("解析睡眠模式失败", t);
                }
                return chain.proceed();
            });
            n++;
        }
        Logs.fire("监听", n > 0 ? "睡眠模式监听已就位" : "未找到睡眠模式接口");
    }

    // ------------------------------------------------------------ 生理睡眠

    private void hookSleepState(ClassLoader cl) {
        Class<?> c = load(cl,
                "com.heytap.device.data.sporthealth.repository.SleepStateRepository$Companion");
        if (c == null) {
            Logs.e("未找到生理睡眠相关类，可能版本不匹配", null);
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
                    Logs.fire("手表上报", "生理状态=" + (state == 1 ? "睡眠" : "清醒")
                            + "，时间 " + fmtTs(ts));
                    State.setSleepState(state, ts);
                } catch (Throwable t) {
                    Logs.e("解析生理睡眠失败", t);
                }
                return chain.proceed();
            });
            n++;
        }
        Logs.fire("监听", n > 0 ? "生理睡眠监听已就位" : "未找到生理睡眠接口");
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
            Logs.e("未找到消息收发类，可能版本不匹配", null);
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
                                Logs.fire("消息层", "读到睡眠模式=" + (f3 == 1 ? "开" : "关"));
                                State.setSleepMode(f3 == 1 ? 1 : 0,
                                        System.currentTimeMillis() / 1000L, true);
                            } else {
                                Logs.i("消息层：这条数据不含睡眠模式状态");
                            }
                        }
                    }
                } catch (Throwable t) {
                    Logs.e("解析手表消息失败", t);
                }
                return chain.proceed();
            });
            n++;
            break;
        }
        Logs.fire("监听", n > 0 ? "手表消息监听已就位" : "未找到消息接口");
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
                    Logs.i("已连接到 OPPO 健康进程");
                    return;
                }
                if (++tries < 60) h.postDelayed(this, 500L);
                else Logs.e("30 秒内未能连接 OPPO 健康，请确认模块已启用", null);
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

    /** 时间戳转 HH:mm:ss，给日志用。 */
    private static String fmtTs(long sec) {
        if (sec <= 0) return "未知";
        long ms = sec < 100000000000L ? sec * 1000L : sec;
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    private static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }
}
