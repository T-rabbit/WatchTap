package dev.watchtap;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标定义表。
 *
 * 每个指标有固定的 id / 名称 / 单位 / 展示类型。采集端往 History 里写数据时
 * 用这里的 id 作为键，展示端按 id 取值，避免字符串散落各处。
 *
 * ==== 展示类型 ====
 *   SCALAR   单值（步数、消耗…），只显示最新值
 *   CURVE    时序数值（心率、血氧），画曲线
 *   CATEGORY 分类状态（睡眠分期），按段展示
 */
final class Metrics {

    static final int SCALAR = 0;
    static final int CURVE = 1;
    static final int CATEGORY = 2;

    // ---- 指标 id ----
    static final String HR = "hr";              // 心率
    static final String SPO2 = "spo2";          // 血氧
    static final String STEPS = "steps";        // 步数
    static final String CALORIES = "calories";  // 活动消耗
    static final String ACTIVE_COUNT = "activeCount"; // 活动次数
    static final String EXERCISE_MIN = "exerciseMin"; // 锻炼时长
    static final String SLEEP_STAGE = "sleepStage";   // 睡眠分期
    static final String SLEEP_MODE = "sleepMode";     // 手表睡眠模式
    static final String ZEN = "zen";                  // 手机勿扰
    static final String MIND = "mind";                // 身心状态

    /** 睡眠分期取值（对应 OPPO 的睡眠阶段编码） */
    static final int STAGE_AWAKE = 0;
    static final int STAGE_LIGHT = 1;
    static final int STAGE_DEEP = 2;
    static final int STAGE_REM = 3;

    static final class Def {
        final String id;
        final String name;
        final String unit;
        final int type;

        Def(String id, String name, String unit, int type) {
            this.id = id;
            this.name = name;
            this.unit = unit;
            this.type = type;
        }
    }

    private static final List<Def> ALL = new ArrayList<>();

    static {
        ALL.add(new Def(HR, "心率", "bpm", CURVE));
        ALL.add(new Def(SPO2, "血氧", "%", CURVE));
        ALL.add(new Def(STEPS, "步数", "步", SCALAR));
        ALL.add(new Def(CALORIES, "活动消耗", "千卡", SCALAR));
        ALL.add(new Def(ACTIVE_COUNT, "活动次数", "次", SCALAR));
        ALL.add(new Def(EXERCISE_MIN, "锻炼时长", "分钟", SCALAR));
        ALL.add(new Def(SLEEP_STAGE, "睡眠分期", "", CATEGORY));
        ALL.add(new Def(SLEEP_MODE, "手表睡眠模式", "", CATEGORY));
        ALL.add(new Def(ZEN, "手机勿扰", "", CATEGORY));
        ALL.add(new Def(MIND, "身心状态", "", SCALAR));
    }

    private Metrics() {}

    static List<Def> all() {
        return ALL;
    }

    static Def get(String id) {
        for (Def d : ALL) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    static String nameOf(String id) {
        Def d = get(id);
        return d == null ? id : d.name;
    }

    /** 睡眠分期转中文。 */
    static String stageName(int stage) {
        switch (stage) {
            case STAGE_AWAKE: return "清醒";
            case STAGE_LIGHT: return "浅睡";
            case STAGE_DEEP: return "深睡";
            case STAGE_REM: return "快速眼动";
            default: return "未知";
        }
    }

    static int stageColor(int stage) {
        switch (stage) {
            case STAGE_AWAKE: return 0xFF9CA3AF;
            case STAGE_LIGHT: return 0xFF60A5FA;
            case STAGE_DEEP: return 0xFF4F46E5;
            case STAGE_REM: return 0xFFA78BFA;
            default: return 0xFF4B5563;
        }
    }
}
