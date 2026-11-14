package dev.watchtap;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * WatchTap 入口（v2.20.0 → 适配 OPPO 健康 6.10.15）。
 *
 * 三个采集点。6.10.15 把所有类名重新混淆了，旧名一个都不存在，
 * 所以全部改成「候选类名 + 参数类型特征匹配」：
 *
 *   1. 手表睡眠模式
 *        旧: com.heytap.device.sleep.SleepModeManager#onReceiveDeviceSleepModeData
 *        新: com.heytap.device.sleep.n#c(SleepModelSettings)   ← 类 n 的 TAG 仍是 "SleepModeManager"
 *   2. 生理睡眠
 *        旧: ...sporthealth.repository.SleepStateRepository$Companion#reportSleepState
 *        新: 该包已整体移除 → 从报文 cid=0x20（SleepStateChangeNotify，f1=startTime、f3=state）解析
 *   3. 消息层兜底
 *        旧: ...devicemanager.btclient.BTClient#onMessageReceived
 *        新: xs.e#onMessageReceived（线程池 tag 仍是 "BTClient"，(sid,cid)=(-1,-1) 通配注册）
 *            兜底: ...connect.rawapi.impl.listener.LM3Message$mMessageCallback$1#onMessageEvent
 *
 * 所有 hook 都只读数据，不改 App 行为。勿扰由模块自己写（provider 进程）。
 */
public class Main extends XposedModule {

    private static final String PKG = "com.heytap.health";

    /** 手表→手机 全量消息入口候选（6.10.15 → 旧版本） */
    private static final String[] CAPTURE_CLASSES = {
            "xs.e",
            "com.heytap.health.connect.rawapi.impl.listener.LM3Message$mMessageCallback$1",
            "com.heytap.health.devicemanager.btclient.BTClient",
    };

    /** SleepModeManager 候选（6.10.15 → 旧版本） */
    private static final String[] SLEEP_MGR_CLASSES = {
            "com.heytap.device.sleep.n",
            "com.heytap.device.sleep.SleepModeManager",
    };

    /** 生理睡眠仓库候选（6.10.15 已整体移除，保留兼容；新版本走报文兜底） */
    private static final String[] SLEEP_STATE_CLASSES = {
            "com.heytap.device.data.sporthealth.repository.SleepStateRepository$Companion",
            "com.heytap.device.data.sporthealth.repository.SleepStateRepository",
    };

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

        // 让 App 的「睡眠模式联动免打扰」开关显示出来。
        //
        // 无条件安装，行为由运行时开关决定 —— 这样 onPackageLoaded
        // 阶段不需要读偏好（那时没有 Context，免 root 也读不到文件）。
        try {
            SwitchFixer.install(this, cl);
        } catch (Throwable t) {
            Logs.e("开关修复安装失败", t);
        }

        // 手机闹钟 → 手表 联动（HyperOS 上绕过 ColorOS 平台闹钟服务）
        try {
            AlarmLink.install(this, cl);
        } catch (Throwable t) {
            Logs.e("闹钟联动安装失败", t);
        }

        warmup();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam p) {
        if (!PKG.equals(p.getPackageName())) return;
        Logs.fire("模块", "OPPO 健康已就绪");
        State.attach(appCtx());
        State.syncZen();
        AlarmLink.attach(appCtx());
    }

    // ------------------------------------------------------------ 睡眠模式

    /**
     * 手表睡眠模式开关。
     *
     * 6.10.15: com.heytap.device.sleep.n#c(SleepModelSettings)
     * 旧版本:  SleepModeManager#onReceiveDeviceSleepModeData(SleepModelSettings)
     *
     * 方法名会变，但参数类型 ...SleepModelSettings 稳定，其上的
     * isStartNow() / getTimestamp() 也没有改。
     */
    private void hookSleepMode(ClassLoader cl) {
        int n = 0;
        for (String className : SLEEP_MGR_CLASSES) {
            Class<?> c = load(cl, className);
            if (c == null) continue;

            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].getName().endsWith("SleepModelSettings")) continue;
                String name = m.getName();
                if (!"c".equals(name) && !"onReceiveDeviceSleepModeData".equals(name)) continue;

                api.hook(m).intercept(chain -> {
                    try {
                        Object setting = chain.getArg(0);
                        boolean startNow = bool(setting, "isStartNow", "getStartNow");
                        long ts = longVal(setting, "getTimestamp", "getTime");
                        Logs.fire("手表上报", "睡眠模式=" + (startNow ? "开" : "关")
                                + "，时间 " + fmtTs(ts));
                        State.setSleepMode(startNow ? 1 : 0, ts, true);
                    } catch (Throwable t) {
                        Logs.e("解析睡眠模式失败", t);
                    }
                    return chain.proceed();
                });
                n++;
                Logs.i("睡眠模式监听点：" + className + "#" + name);
            }
        }
        Logs.fire("监听", n > 0 ? "睡眠模式监听已就位" : "未找到睡眠模式接口（版本可能不匹配）");
    }

    // ------------------------------------------------------------ 生理睡眠

    private void hookSleepState(ClassLoader cl) {
        int n = 0;
        for (String className : SLEEP_STATE_CLASSES) {
            Class<?> c = load(cl, className);
            if (c == null) continue;

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
                Logs.i("生理睡眠监听点：" + className + "#reportSleepState");
            }
        }
        Logs.fire("监听", n > 0 ? "生理睡眠监听已就位"
                : "旧版生理睡眠接口不存在，改用报文 cid=0x20 兜底");
    }

    // ------------------------------------------------------------ 消息层兜底

    /**
     * hook 所有手表消息（手表 → 手机）。
     *
     * 主要作用：
     *   - 报文计数（State.noteMessage）
     *   - cid 0xcc / 0x49 的 field3(startNow) → 补睡眠模式状态
     *   - cid 0x20（SleepStateChangeNotify）→ 补生理睡眠状态（6.10.15 的主要来源）
     *
     * 同一 MessageEvent 实例会经过多个 hook 点，按身份去重只处理一次。
     */
    private void hookMessages(ClassLoader cl) {
        int n = 0;
        for (String className : CAPTURE_CLASSES) {
            Class<?> c = load(cl, className);
            if (c == null) continue;

            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (!"onMessageReceived".equals(name) && !"onMessageEvent".equals(name)) continue;
                if (m.getParameterCount() != 2) continue;

                int evIdx = paramIndexEndingWith(m, "MessageEvent");
                if (evIdx < 0) continue;
                final int evArg = evIdx;
                final int macArg = evIdx == 0 ? 1 : 0;

                api.hook(m).intercept(chain -> {
                    try {
                        Object event = chain.getArg(evArg);
                        Object mac = chain.getArg(macArg);
                        if (event != null && firstSight(event)) {
                            int sid = (int) call(event, "getServiceId");
                            int cid = (int) call(event, "getCommandId");
                            byte[] data = (byte[]) call(event, "getData");
                            int len = data == null ? 0 : data.length;
                            State.noteMessage(sid, cid, len);

                            // 0xcc / 0x49 = 读睡眠设置回包，field3 = startNow
                            if (sid == 5 && (cid == 0xcc || cid == 0x49) && len >= 4) {
                                int f3 = fieldOf(data, 3);
                                if (f3 >= 0) {
                                    Logs.fire("消息层", "读到睡眠模式=" + (f3 == 1 ? "开" : "关")
                                            + "（mac=" + mac + "）");
                                    State.setSleepMode(f3 == 1 ? 1 : 0,
                                            System.currentTimeMillis() / 1000L, true);
                                } else {
                                    Logs.i("消息层：这条数据不含睡眠模式状态");
                                }
                            }

                            // 0x20 = SleepStateChangeNotify：f1=startTime, f2=endTime, f3=state
                            if (sid == 5 && cid == 0x20 && len >= 2) {
                                int state = fieldOf(data, 3);
                                int start = fieldOf(data, 1);
                                if (state >= 0) {
                                    Logs.fire("手表上报", "生理状态="
                                            + (state == 1 ? "睡眠" : "清醒")
                                            + "（来自报文 cid=0x20）");
                                    State.setSleepState(state,
                                            start > 0 ? start : System.currentTimeMillis() / 1000L);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Logs.e("解析手表消息失败", t);
                    }
                    return chain.proceed();
                });
                n++;
                Logs.i("消息监听点：" + className + "#" + name);
            }
        }
        Logs.fire("监听", n > 0 ? "手表消息监听已就位" : "未找到消息接口（版本可能不匹配）");
    }

    /** 取 protobuf 里指定 varint 字段的值，不存在返回 -1。 */
    private int fieldOf(byte[] d, int want) {
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
                if (field == want) return (int) v;
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

    /** 同一个 MessageEvent 实例只处理一次（多个 hook 点会重复看到它）。 */
    private static final Map<Integer, Long> SEEN = new ConcurrentHashMap<>();
    private static final long SEEN_WINDOW_MS = 2000L;

    private static boolean firstSight(Object event) {
        int id = System.identityHashCode(event);
        long now = System.currentTimeMillis();
        Long prev = SEEN.put(id, now);
        if (SEEN.size() > 512) {
            SEEN.entrySet().removeIf(e -> now - e.getValue() > SEEN_WINDOW_MS);
        }
        return prev == null || now - prev > SEEN_WINDOW_MS;
    }

    /** 返回参数中第一个以 suffix 结尾的类型下标，没有返回 -1。 */
    private static int paramIndexEndingWith(Method m, String suffix) {
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] != null && ps[i].getName().endsWith(suffix)) return i;
        }
        return -1;
    }

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
                    AlarmLink.attach(c);
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

    private static Object call(Object o, String... names) {
        if (o == null) return null;
        for (String name : names) {
            try {
                return call(o, name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean bool(Object o, String... names) {
        Object v = call(o, names);
        return v instanceof Boolean && (Boolean) v;
    }

    private static long longVal(Object o, String... names) {
        Object r = call(o, names);
        if (r instanceof Long) return (Long) r;
        if (r instanceof Integer) return ((Integer) r).longValue();
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
