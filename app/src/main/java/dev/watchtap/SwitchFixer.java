package dev.watchtap;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 让 OPPO 健康的「睡眠模式联动免打扰」开关显示出来。
 *
 * ==== 背景 ====
 * App 里有个判断链决定这个开关是否可见：
 *
 *   isShowLinkagePhoneZenModeSetting()
 *     = isCurrentDeviceSupportSleepMode()     手表支持睡眠模式
 *     && isPhoneSupportZenModeLinkage()
 *          = SDK_INT >= 30 && getRomVersionCode() > 0
 *
 * getRomVersionCode() 会反射 ColorOS 私有类：
 *   com.oplus.os.OplusBuild.getOplusOSVERSION()
 *   com.color.os.ColorBuild.getColorOSVERSION()
 *
 * HyperOS 上这两个类都不存在 → 返回 0 → 开关被隐藏。
 *
 * ==== 6.10.15 的变化 ====
 *   - SystemUtils 被 R8 合并进 com.heytap.health.base.app.v，
 *     getRomVersionCode() 改名为 v#g()I（日志 tag 仍是 "SystemUtils"）
 *   - 可见性判据上移一层：SleepDataServiceImpl#D0()Z
 *       = k0.b() && SDK_INT >= 30 && v.g() > 0
 *     D0() 全 APK 只有 1 个 UI 调用点，比 v.g()（12 处调用）更精准，
 *     所以优先 hook D0()，同时保留 v.g() 作为兼容。
 *
 * ==== 本类的做法 ====
 * 开关打开时：D0() → true，且 v.g() → 15（ColorOS 15）。
 *
 * ==== 重要的坑 ====
 * **不要**同时把 isOppoBrand() 改成 true。
 *
 * OPlusDeviceUtils.isFromColorOS14() 的实现：
 *
 *   invoke-static {}, ...->isOppoBrand()Z
 *   if-eqz v0, :cond_15
 *   sget v0, Lcom/oplus/os/OplusBuild$VERSION;->SDK_VERSION:I   ← 无 try/catch
 *
 * isOppoBrand() 返回 true 后，执行流会走到那个 sget，
 * 而 OplusBuild 类在 HyperOS 上不存在 → NoClassDefFoundError → 崩溃。
 * 这个 sget 没有异常保护，所以必崩。
 *
 * ==== 与联动的关系 ====
 * 这些 hook 只影响「开关是否显示」，不影响联动逻辑。
 * 开关真正的状态是 SleepModelSettings.mStateSync（用户设置项），
 * 需要用户在 App 里手动打开才会真正联动。
 */
final class SwitchFixer {

    /** 6.10.15 的 SystemUtils（v）→ 旧版本 SystemUtils */
    private static final String[] CLS_SYSUTILS = {
            "com.heytap.health.base.app.v",
            "com.heytap.health.base.app.SystemUtils",
    };

    /** 6.10.15 的开关可见性判据 */
    private static final String[] CLS_VISIBILITY = {
            "com.heytap.device.sleep.SleepDataServiceImpl",
    };

    /** 伪造的 ROM 版本号，15 = ColorOS 15 */
    private static final int FAKE_ROM_VERSION = 15;

    private SwitchFixer() {}

    /**
     * 安装 hook。
     *
     * ==== 关键设计：无条件安装，运行时判断 ====
     *
     * 早期版本是「先读偏好，启用才安装」。但 onPackageLoaded 阶段
     * Application 还没创建，拿不到 Context，只能靠读文件 ——
     * 而 Android 11+ 禁止跨应用读文件，于是免 root 时读不到，
     * hook 就装不上。
     *
     * 现在改成：**总是安装 hook**，在拦截器里每次检查开关状态。
     */
    static boolean install(XposedInterface api, ClassLoader cl) {
        boolean a = hookVisibility(api, cl);
        boolean b = hookRomVersion(api, cl);
        if (!a && !b) {
            Logs.e("开关修复：没有可用的 hook 点（版本可能不匹配）", null);
        }
        return a || b;
    }

    /** 优先：SleepDataServiceImpl#D0()Z（6.10.15 的开关可见性判据） */
    private static boolean hookVisibility(XposedInterface api, ClassLoader cl) {
        for (String name : CLS_VISIBILITY) {
            Class<?> c = load(cl, name);
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != boolean.class) continue;
                String n = m.getName();
                if (!"D0".equals(n) && !"isPhoneSupportZenModeLinkage".equals(n)) continue;

                api.hook(m).intercept(chain -> {
                    if (!State.fixSwitchEnabled()) return chain.proceed();
                    return Boolean.TRUE;
                });
                Logs.i("开关修复已就位（可见性判据：" + name + "#" + n + "）");
                return true;
            }
        }
        return false;
    }

    /** 兼容：getRomVersionCode() / v#g()I → 15 */
    private static boolean hookRomVersion(XposedInterface api, ClassLoader cl) {
        for (String className : CLS_SYSUTILS) {
            Class<?> c = load(cl, className);
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != int.class) continue;
                String n = m.getName();
                if (!"g".equals(n) && !"getRomVersionCode".equals(n)) continue;

                api.hook(m).intercept(chain -> {
                    if (!State.fixSwitchEnabled()) return chain.proceed();
                    return FAKE_ROM_VERSION;
                });
                Logs.i("开关修复已就位（ROM 版本：" + className + "#" + n + "）");
                return true;
            }
        }
        return false;
    }

    /**
     * 自检：读出当前 ROM 版本的实际返回值。
     *
     * @return 描述文本，失败返回 null
     */
    static String probe(ClassLoader cl) {
        for (String className : CLS_SYSUTILS) {
            Class<?> c = load(cl, className);
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != int.class) continue;
                String n = m.getName();
                if (!"g".equals(n) && !"getRomVersionCode".equals(n)) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    int code = v instanceof Integer ? (Integer) v : -1;

                    StringBuilder sb = new StringBuilder();
                    sb.append(className).append('#').append(n).append("() = ").append(code).append('\n');
                    sb.append("开关可见性：").append(code > 0 ? "显示" : "隐藏").append('\n');
                    if (code <= 0) {
                        sb.append("→ 需要打开「修复联动开关」");
                    }
                    return sb.toString();
                } catch (Throwable t) {
                    return "读取失败：" + Logs.brief(t);
                }
            }
        }
        return "没有找到 ROM 版本接口（版本可能不匹配）";
    }

    private static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }
}
