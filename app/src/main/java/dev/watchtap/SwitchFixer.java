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
 * ==== 本类的做法 ====
 * hook getRomVersionCode() 让它返回一个正数（15，对应 ColorOS 15），
 * 条件通过，开关就显示出来了。
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
 * 这个 hook 只影响「开关是否显示」，不影响联动逻辑。
 * 开关真正的状态是 SleepModelSettings.mStateSync（用户设置项），
 * 需要用户在 App 里手动打开才会真正联动。
 */
final class SwitchFixer {

    private static final String CLS_SYSUTILS =
            "com.heytap.health.base.app.SystemUtils";

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
     *
     * 好处：
     *   - 安装时机不需要 Context（onPackageLoaded 就能装）
     *   - 行为由运行时决定，那时 App 已启动，能通过 provider 读偏好
     *   - 免 root 完全可行（provider 是普通 IPC，不需要 root）
     *
     * 代价：每次 getRomVersionCode 调用多一次开关判断，
     * 但这是内存读，开销可忽略（该方法只在 UI 判断时调用）。
     */
    static boolean install(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS_SYSUTILS, false, cl);

            for (Method m : c.getDeclaredMethods()) {
                if (!"getRomVersionCode".equals(m.getName())) continue;
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != int.class) continue;

                api.hook(m).intercept(chain -> {
                    // 运行时判断：开关关着就走原逻辑
                    if (!State.fixSwitchEnabled()) {
                        return chain.proceed();
                    }
                    return FAKE_ROM_VERSION;
                });

                Logs.i("开关修复已就位（运行时按开关决定是否生效）");
                return true;
            }
            Logs.e("开关修复：没找到 getRomVersionCode 方法", null);
            return false;
        } catch (Throwable t) {
            Logs.e("开关修复安装失败（可能版本不匹配）", t);
            return false;
        }
    }

    /**
     * 自检：读出当前 getRomVersionCode 的实际返回值。
     *
     * @return 描述文本，失败返回 null
     */
    static String probe(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS_SYSUTILS, false, cl);
            Method m = c.getDeclaredMethod("getRomVersionCode");
            m.setAccessible(true);
            Object v = m.invoke(null);
            int code = v instanceof Integer ? (Integer) v : -1;

            StringBuilder sb = new StringBuilder();
            sb.append("getRomVersionCode() = ").append(code).append('\n');
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
