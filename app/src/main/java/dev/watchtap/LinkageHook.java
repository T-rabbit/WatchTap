package dev.watchtap;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 睡眠联动启用层。
 *
 * ==== 目标 ====
 * 让 App 认为「这台手机支持睡眠模式 → 免打扰联动」，
 * 从而启动它的轮询逻辑（主动向手表发 cid=0xcc 读状态）。
 *
 * ==== 依据 ====
 * SleepSettingUtil.isPhoneSupportZenModeLinkage():
 *     SDK_INT >= 30 && SystemUtils.getRomVersionCode() > 0
 *
 * SystemUtils.getRomVersionCode() 内部反射 OplusBuild / ColorBuild，
 * HyperOS 上两个类都不存在 → 返回 0 → 联动被永久禁用。
 *
 * ==== 分步启用（避免重蹈崩溃覆辙）====
 * STEP 1: 只让 getRomVersionCode 返回非 0
 * STEP 2: 若稳定，再加写 DND 的拦截
 */
final class LinkageHook {

    private static final String TAG = "WatchTap";
    private static final String CLS_SYSUTILS =
            "com.heytap.health.base.app.SystemUtils";

    static void install(XposedInterface api, ClassLoader cl, int step) {
        if (step >= 1) {
            hookRomVersion(api, cl);
        }
        if (step >= 2) {
            DndInterceptor.install(api, cl);
        }
        // 伪造 ColorOS 私有字段 zen_mode_ext_info（isPhoneSupportDND 依赖它）
        hookZenExtInfo(api);
        // 诊断：直接观察 isPhoneSupportDND 的返回值
        hookDndSupport(api, cl);
        // 诊断：观察 linkagePhoneZenMode 是否被调用
        hookLinkageCall(api, cl);
        // 诊断：观察联动链路各闸门的真实值
        hookLinkageChain(api, cl);
    }

    /**
     * 诊断 hook：观察 onReceiveDeviceSleepModeData 是否被调用、各值是多少。
     *
     * 这个方法是联动的必经之路，如果它压根没被调用，
     * 说明手表根本没上报（而不是闸门挡住了）。
     */
    private static void hookLinkageChain(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> mgr = Class.forName("com.heytap.device.sleep.SleepModeManager", false, cl);
            for (Method m : mgr.getDeclaredMethods()) {
                if (!"onReceiveDeviceSleepModeData".equals(m.getName())) continue;

                api.hook(m).intercept(chain -> {
                    try {
                        Object setting = chain.getArg(0);
                        boolean startNow = boolOf(setting, "isStartNow");
                        boolean syncOn = boolOf(setting, "isSyncSleepMode");
                        long ts = longOf(setting, "getTimestamp");
                        String line = "CHAIN onReceiveDeviceSleepModeData"
                                + " startNow=" + startNow
                                + " isSyncSleepMode=" + syncOn
                                + " ts=" + ts;
                        Log.i(TAG, line);
                        append(line);
                    } catch (Throwable t) {
                        Log.w(TAG, "chain parse error", t);
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "LINK: hooked onReceiveDeviceSleepModeData (diagnose)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "LINK: chain hook failed", t);
        }
    }

    /**
     * 伪造 Settings.Global."zen_mode_ext_info"。
     *
     * HyperOS 上这个字段是 null，导致：
     *   DoNotDisturbRepository.isPhoneSupportDND() = false
     *   → SleepModeManager 跳过 linkagePhoneZenMode()
     *
     * ColorOS 的真实格式："时间戳ms,allowCalls,allowMsgs,allowEvents,status"
     *   status: 0=关, 1=开
     *
     * 这里按当前真实 zen_mode 合成对应值。
     */
    private static void hookZenExtInfo(XposedInterface api) {
        try {
            Method getString = android.provider.Settings.Global.class.getMethod(
                    "getString", android.content.ContentResolver.class, String.class);

            api.hook(getString).intercept(chain -> {
                Object key = chain.getArg(1);
                Object real = chain.proceed();

                if (!"zen_mode_ext_info".equals(key)) return real;
                if (real != null && !((String) real).isEmpty()) return real;

                int zen = rawZen();
                String fake = System.currentTimeMillis() + ",0,0,0," + (zen != 0 ? 1 : 0);
                Log.i(TAG, "LINK: zen_mode_ext_info(null) -> " + fake);
                return fake;
            });
            Log.i(TAG, "LINK: hooked zen_mode_ext_info");
        } catch (Throwable t) {
            Log.e(TAG, "LINK: hook zenExtInfo failed", t);
        }
    }

    private static int rawZen() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (!(app instanceof android.content.Context)) return 0;
            return android.provider.Settings.Global.getInt(
                    ((android.content.Context) app).getContentResolver(), "zen_mode", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 观察 isPhoneSupportDND() 的真实返回值。 */
    private static void hookDndSupport(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.heytap.device.sleep.DoNotDisturbRepository$Companion", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"isPhoneSupportDND".equals(m.getName())) continue;
                api.hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    String line = "GATE isPhoneSupportDND = " + r;
                    Log.i(TAG, line);
                    append(line);
                    return r;
                });
                Log.i(TAG, "LINK: hooked isPhoneSupportDND");
            }
        } catch (Throwable t) {
            Log.e(TAG, "LINK: hook isPhoneSupportDND failed", t);
        }
    }

    /** 观察 linkagePhoneZenMode 是否被调用。 */
    private static void hookLinkageCall(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.heytap.device.sleep.SleepModeManager", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"linkagePhoneZenMode".equals(m.getName())) continue;
                api.hook(m).intercept(chain -> {
                    String line = "GATE linkagePhoneZenMode called: enable="
                            + chain.getArg(0) + " time=" + chain.getArg(1);
                    Log.i(TAG, line);
                    append(line);
                    return chain.proceed();
                });
                Log.i(TAG, "LINK: hooked linkagePhoneZenMode");
            }
        } catch (Throwable t) {
            Log.e(TAG, "LINK: hook linkage failed", t);
        }
    }

    private static boolean boolOf(Object o, String m) {
        try {
            java.lang.reflect.Method mm = o.getClass().getMethod(m);
            mm.setAccessible(true);
            Object r = mm.invoke(o);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long longOf(Object o, String m) {
        try {
            java.lang.reflect.Method mm = o.getClass().getMethod(m);
            mm.setAccessible(true);
            Object r = mm.invoke(o);
            if (r instanceof Long) return (Long) r;
            if (r instanceof Integer) return ((Integer) r).longValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void append(String line) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (!(app instanceof android.content.Context)) return;
            android.content.Context c = ((android.content.Context) app).getApplicationContext();
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(dir, "watchtap.log"), true);
            try {
                w.write(line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * STEP 1：getRomVersionCode() → 15
     *
     * 这是让 isPhoneSupportZenModeLinkage() 通过的唯一闸门。
     */
    private static void hookRomVersion(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS_SYSUTILS, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"getRomVersionCode".equals(m.getName())) continue;
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != int.class) continue;

                api.hook(m).intercept(chain -> 15);
                Log.i(TAG, "LINK: getRomVersionCode -> 15");
                return;
            }
            Log.e(TAG, "LINK: getRomVersionCode not found");
        } catch (Throwable t) {
            Log.e(TAG, "LINK: hook romVersion failed", t);
        }
    }
}
