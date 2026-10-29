package dev.watchtap;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据详情页（二级页面）。
 *
 * 展示：
 *   - 今日概览（步数/消耗/活动次数/锻炼时长/血氧/心率）
 *   - 心率曲线、血氧曲线
 *   - 睡眠分期（按生理状态分段）
 *   - 历史范围切换（今日 / 近 3 天 / 近 7 天）
 *
 * 数据来源：History（本地 CSV），由 hook 进程写入。
 */
public class DataActivity extends Activity {

    private int mDp;
    private int mRangeDays = 1;

    private LinearLayout mContent;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRunning = true;

    private final SimpleDateFormat mMd =
            new SimpleDateFormat("MM-dd HH:mm", Locale.US);
    private final SimpleDateFormat mHm =
            new SimpleDateFormat("HH:mm", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDp = (int) getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0F1115);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, dp(8), pad, dp(30));

        // 标题栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("数据详情");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(this);
        close.setText("返回");
        close.setAllCaps(false);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        close.setTextColor(0xFFCBD5E1);
        close.setBackgroundColor(0xFF1E293B);
        close.setMinHeight(0);
        close.setMinimumHeight(0);
        close.setPadding(dp(10), dp(5), dp(10), dp(5));
        close.setOnClickListener(v -> finish());
        Button dump = new Button(this);
        dump.setText("自检");
        dump.setAllCaps(false);
        dump.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dump.setTextColor(0xFFCBD5E1);
        dump.setBackgroundColor(0xFF7C2D12);
        dump.setMinHeight(0);
        dump.setMinimumHeight(0);
        dump.setPadding(dp(10), dp(5), dp(10), dp(5));
        dump.setOnClickListener(v -> dumpDb());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.rightMargin = dp(6);
        dump.setLayoutParams(dlp);
        bar.addView(dump);

        Button collect = new Button(this);
        collect.setText("采集");
        collect.setAllCaps(false);
        collect.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        collect.setTextColor(Color.WHITE);
        collect.setBackgroundColor(0xFF059669);
        collect.setMinHeight(0);
        collect.setMinimumHeight(0);
        collect.setPadding(dp(10), dp(5), dp(10), dp(5));
        collect.setOnClickListener(v -> runCollect());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(6);
        collect.setLayoutParams(clp);
        bar.addView(collect);

        Button deep = new Button(this);
        deep.setText("探测");
        deep.setAllCaps(false);
        deep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        deep.setTextColor(Color.WHITE);
        deep.setBackgroundColor(0xFF7C3AED);
        deep.setMinHeight(0);
        deep.setMinimumHeight(0);
        deep.setPadding(dp(10), dp(5), dp(10), dp(5));
        deep.setOnClickListener(v -> runDeep());
        // 长按探测按钮 = 转储 Health Connect 表结构
        deep.setOnLongClickListener(v -> {
            runHcDump();
            return true;
        });
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp2.rightMargin = dp(6);
        deep.setLayoutParams(dlp2);
        bar.addView(deep);

        bar.addView(close);
        root.addView(bar);

        // 范围切换
        LinearLayout ranges = new LinearLayout(this);
        ranges.setOrientation(LinearLayout.HORIZONTAL);
        ranges.setPadding(0, dp(8), 0, dp(8));
        ranges.addView(rangeBtn("今日", 1));
        ranges.addView(rangeBtn("近3天", 3));
        ranges.addView(rangeBtn("近7天", 7));
        root.addView(ranges);

        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        root.addView(mContent);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets b = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsets.CONSUMED;
        });

        tick();
    }

    private Button rangeBtn(String text, int days) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(days == mRangeDays ? 0xFF2563EB : 0xFF1E293B);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            mRangeDays = days;
            rebuild();
        });
        return b;
    }

    private void tick() {
        if (!mRunning) return;
        rebuild();
        mHandler.postDelayed(this::tick, 2000L);
    }

    private void rebuild() {
        mContent.removeAllViews();

        long now = System.currentTimeMillis();
        long from = now - mRangeDays * 24L * 3600 * 1000;

        // ---- 概览 ----
        LinearLayout overview = new LinearLayout(this);
        overview.setOrientation(LinearLayout.VERTICAL);
        overview.setBackgroundColor(0xFF1A1D24);
        int p = dp(12);
        overview.setPadding(p, p, p, p);

        addRow(overview, "步数", latest(Metrics.STEPS, from, now), "步");
        addRow(overview, "活动消耗", latest(Metrics.CALORIES, from, now), "千卡");
        addRow(overview, "活动次数", latest(Metrics.ACTIVE_COUNT, from, now), "次");
        addRow(overview, "锻炼时长", latest(Metrics.EXERCISE_MIN, from, now), "分钟");
        addRow(overview, "血氧", latest(Metrics.SPO2, from, now), "%");
        addRow(overview, "心率", latest(Metrics.HR, from, now), "bpm");
        addRow(overview, "身心状态", latest(Metrics.MIND, from, now), "");

        mContent.addView(wrap(overview, dp(4)));

        // ---- 心率曲线 ----
        List<History.Point> hr = History.read(this, Metrics.HR, from, now);
        mContent.addView(sectionTitle("心率曲线（" + hr.size() + " 个数据点）"));
        mContent.addView(curve(hr, 0xFFF87171, dp(90)));

        // ---- 血氧曲线 ----
        List<History.Point> spo2 = History.read(this, Metrics.SPO2, from, now);
        mContent.addView(sectionTitle("血氧曲线（" + spo2.size() + " 个数据点）"));
        mContent.addView(curve(spo2, 0xFF34D399, dp(90)));

        // ---- 睡眠分期 ----
        List<History.Point> stage = History.read(this, Metrics.SLEEP_STAGE, from, now);
        mContent.addView(sectionTitle("睡眠分期（" + stage.size() + " 条记录）"));
        mContent.addView(sleepStages(stage));

        // ---- 存储信息 ----
        TextView info = new TextView(this);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        info.setTextColor(0xFF6B7280);
        info.setLineSpacing(dp(2), 1f);
        int cnt = History.countAll(this);
        info.setText("记录总数：" + cnt + " 条\n"
                + "存储位置：Android/data/dev.watchtap/files/history/\n"
                + "保留期限：30 天\n\n"
                + "提示：用 adb pull 可以把 CSV 导出到电脑用表格软件打开。");
        mContent.addView(info, lp(0, dp(14), 0, 0));
    }

    // ------------------------------------------------------------ 组件

    private void addRow(LinearLayout parent, String name, String value, String unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        n.setTextColor(0xFF8B94A3);
        row.addView(n, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value + (unit.isEmpty() ? "" : " " + unit));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(v);

        parent.addView(row);
    }

    /** 取指标最新值。 */
    private String latest(String id, long from, long to) {
        List<History.Point> pts = History.read(this, id, from, to);
        if (pts.isEmpty()) return "--";
        long v = pts.get(pts.size() - 1).value;
        if (Metrics.SLEEP_STAGE.equals(id)) return Metrics.stageName((int) v);
        if (Metrics.MIND.equals(id)) return mindName((int) v);
        return String.valueOf(v);
    }

    private String mindName(int v) {
        switch (v) {
            case 1: return "放松";
            case 2: return "正常";
            case 3: return "紧张";
            case 4: return "疲劳";
            default: return "--";
        }
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(0xFF8B94A3);
        t.setPadding(0, dp(12), 0, dp(5));
        return t;
    }

    private LinearLayout wrap(View v, int topMargin) {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        w.setPadding(0, topMargin, 0, 0);
        return w;
    }

    private CurveView curve(List<History.Point> data, int color, int height) {
        CurveView cv = new CurveView(this);
        cv.setData(data, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        cv.setLayoutParams(lp);
        cv.setBackgroundColor(0xFF111827);
        return cv;
    }

    /** 睡眠分期按时间段展示成色块。 */
    private LinearLayout sleepStages(List<History.Point> data) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF111827);
        int p = dp(10);
        box.setPadding(p, p, p, p);

        if (data.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无睡眠分期数据\n（需要手表上报睡眠状态后才会记录）");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            empty.setTextColor(0xFF6B7280);
            box.addView(empty);
            return box;
        }

        // 时间轴色条
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));

        for (int i = 0; i < data.size(); i++) {
            History.Point pt = data.get(i);
            View seg = new View(this);
            seg.setBackgroundColor(Metrics.stageColor((int) pt.value));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            bar.addView(seg, lp);
        }
        box.addView(bar);

        // 图例
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setPadding(0, dp(8), 0, 0);
        int[] stages = {
                Metrics.STAGE_AWAKE, Metrics.STAGE_LIGHT,
                Metrics.STAGE_DEEP, Metrics.STAGE_REM
        };
        for (int s : stages) {
            TextView item = new TextView(this);
            item.setText("■ " + Metrics.stageName(s));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            item.setTextColor(Metrics.stageColor(s));
            item.setPadding(0, 0, dp(10), 0);
            legend.addView(item);
        }
        box.addView(legend);

        // 最近几条明细
        int show = Math.min(6, data.size());
        for (int i = data.size() - show; i < data.size(); i++) {
            History.Point pt = data.get(i);
            TextView t = new TextView(this);
            t.setText(mHm.format(new Date(pt.ts)) + "  "
                    + Metrics.stageName((int) pt.value));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            t.setTextColor(0xFF9CA3AF);
            t.setPadding(0, dp(2), 0, 0);
            box.addView(t);
        }

        return box;
    }

    /** 转储 Health Connect 的表结构。 */
    private void runHcDump() {
        StateStore.logLocal(this, "转储 Health Connect 结构");
        try {
            android.os.Bundle r = getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_HC_DUMP, null, null);
            String raw = r == null ? "无返回" : r.getString("report", "");
            if (raw == null || raw.isEmpty()) raw = "空报告";
            showReport("Health Connect 结构", raw);
        } catch (Throwable t) {
            StateStore.logLocal(this, "转储失败：" + t);
        }
    }

    /** 深度探测数据存储位置。 */
    private void runDeep() {
        StateStore.logLocal(this, "开始深度探测");


        try {
            android.os.Bundle r = getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_DEEP, null, null);
            String raw = r == null ? "无返回" : r.getString("report", "");
            if (raw == null || raw.isEmpty()) raw = "空报告";
            showReport("数据位置探测", raw);
        } catch (Throwable t) {
            StateStore.logLocal(this, "探测失败：" + t);
        }
    }

    /** 立即用 root 采集一次。 */
    private void runCollect() {
        StateStore.logLocal(this, "手动触发数据采集");
        try {
            android.os.Bundle r = getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_COLLECT, null, null);
            String raw = r == null ? "无返回" : r.getString("report", "");
            if (raw == null || raw.isEmpty()) raw = "空报告";
            showReport("采集结果", raw);
            rebuild();
        } catch (Throwable t) {
            StateStore.logLocal(this, "采集失败：" + t);
            android.widget.Toast.makeText(this, "采集失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 弹窗显示报告。 */
    private void showReport(String title, String raw) {
        final String report = raw;

        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFFE5E7EB);
        tv.setTextIsSelectable(true);
        int p = dp(12);
        tv.setPadding(p, p, p, p);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.setBackgroundColor(0xFF111827);

        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(
                                    CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "watchtap-report", report));
                    android.widget.Toast.makeText(this, "已复制",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * 数据库自检：列出 App 里所有数据库、表、列。
     *
     * 采集不到数据时点这个，能直接看出数据结构是什么样。
     */
    private void dumpDb() {
        StateStore.logLocal(this, "开始数据库自检");
        try {
            android.os.Bundle r = getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_DUMP_DB, null, null);
            String raw = r == null ? "无返回" : r.getString("report", "");
            if (raw == null || raw.isEmpty()) raw = "空报告";
            final String report = raw;

            TextView tv = new TextView(this);
            tv.setText(report);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(0xFFE5E7EB);
            tv.setTextIsSelectable(true);
            int p = dp(12);
            tv.setPadding(p, p, p, p);

            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            sv.setBackgroundColor(0xFF111827);

            new android.app.AlertDialog.Builder(this)
                    .setTitle("App 数据库结构")
                    .setView(sv)
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("复制", (d, w) -> {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(
                                        CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "watchtap-db", report));
                        android.widget.Toast.makeText(this, "已复制",
                                android.widget.Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Throwable t) {
            StateStore.logLocal(this, "自检失败：" + t);
        }
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(int v) {
        return (int) (v * mDp + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRunning = true;
        tick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRunning = false;
        mHandler.removeCallbacksAndMessages(null);
    }
}
