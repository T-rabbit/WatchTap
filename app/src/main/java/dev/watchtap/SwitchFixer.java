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
     * @return 是否装上了
     */
    static boolean install(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(CLS_SYSUTILS, false, cl);

            for (Method m : c.getDeclaredMethods()) {
                if (!"getRomVersionCode".equals(m.getName())) continue;
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != int.class) continue;

                api.hook(m).intercept(chain -> FAKE_ROM_VERSION);

                Logs.fire("开关修复", "已让 App 认为这是 ColorOS "
                        + FAKE_ROM_VERSION);
                return true;
            }
            Logs.e("开关修复：没找到 getRomVersionCode 方法", null);
            return false;
        } catch (Throwable t) {
            Logs.e("开关修复失败（可能版本不匹配）", t);
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
