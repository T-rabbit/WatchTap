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
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * WatchTap 界面。
 *
 * 总览、联动设置、工具诊断三个页面。
 * 数据由 hook 进程写入 StateStore，本界面每秒读取一次。
 */
public class MainActivity extends Activity {

    private TextView mSleepModeValue, mSleepModeSub;
    private TextView mDndValue, mDndSub;
    private TextView mSleepValue, mSleepSub;
    private TextView mHrValue, mHrSub, mHrUpdated;
    private Button mHrBtn;
    // 心率结果缓存（由 hook 进程广播过来）
    private int mHrBpm = -1, mHrMin = -1, mHrMax = -1, mHrCount = 0;
    private long mHrTs = 0;
    private String mHrMsg = "";
    private TextView mPermBar;
    private TextView mDiag;
    private Button mUpdateBtn;
    private TextView mLogView;
    private TextView mLogToggle;
    private boolean mLogExpanded = false;
    private Button mDndBtn;
    private android.widget.Switch mMuteSwitch;
    private android.widget.Switch mNotifySwitch;
    private android.widget.Switch mAlarmSyncSwitch;
    private android.widget.Switch mCaptureSwitch;
    private Button mFixBtn;
    private TextView mFixStatus;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRunning = true;
    private float mDp;
    private boolean mBindingUi;
    private final ScrollView[] mPages = new ScrollView[3];
    private final LinearLayout[] mNavItems = new LinearLayout[3];
    private final TextView[] mNavLabels = new TextView[3];
    private int mSelectedPage;
    private LinearLayout mDetails;
    private TextView mDetailsToggle;

    private final SimpleDateFormat mFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDp = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(WatchUi.BG);
        getWindow().setNavigationBarColor(WatchUi.BG);
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout shell = column();
        shell.setBackgroundColor(WatchUi.BG);
        shell.addView(buildHeader());
        FrameLayout pageHost = new FrameLayout(this);
        shell.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (int i = 0; i < mPages.length; i++) {
            mPages[i] = new ScrollView(this);
            mPages[i].setFillViewport(true);
            mPages[i].setClipToPadding(false);
            mPages[i].setVerticalScrollBarEnabled(false);
            mPages[i].setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            pageHost.addView(mPages[i], new FrameLayout.LayoutParams(-1, -1));
        }
        buildOverview(pageRoot(0));
        buildSettings(pageRoot(1));
        buildTools(pageRoot(2));
        shell.addView(buildNavigation());
        setContentView(shell);
        applyInsets(shell);
        selectPage(savedInstanceState == null ? 0 : savedInstanceState.getInt("ui.page", 0));
        StateStore.logLocal(this, "UI.onCreate 界面启动");
        // 数据刷新由 onResume 启动，避免创建和恢复时各开启一条刷新链。
    }

    // ------------------------------------------------------------ 界面构建

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView text(String value, int sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setIncludeFontPadding(false);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout buildHeader() {
        LinearLayout header = row();
        header.setPadding(dp(22), dp(12), dp(22), dp(12));
        View logo = WatchUi.icon(this, "watch", WatchUi.ACCENT, 30);
        header.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView name = text("WatchTap", 20, WatchUi.TEXT, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.setMarginStart(dp(10));
        header.addView(name, np);
        TextView badge = text("手表 · 手机联动", 10, WatchUi.ACCENT, true);
        badge.setPadding(dp(10), dp(7), dp(10), dp(7));
        badge.setBackground(WatchUi.surface(this, WatchUi.ACCENT_DARK, 20, 0));
        header.addView(badge);
        return header;
    }

    private LinearLayout pageRoot(int index) {
        // 大屏使用居中内容栏；窄屏可自然换行，不固定卡片高度。
        FrameLayout center = new FrameLayout(this);
        LinearLayout root = column();
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / mDp);
        int width = widthDp > 640 ? dp(640) : ViewGroup.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(12), dp(22), dp(26));
        center.addView(root, p);
        mPages[index].addView(center, new ScrollView.LayoutParams(-1, -2));
        return root;
    }

    private void heading(LinearLayout root, String title, String sub) {
        root.addView(text(title, 28, WatchUi.TEXT, true), lp(0, 0, 0, dp(8)));
        TextView note = text(sub, 13, WatchUi.SECONDARY, false);
        note.setLineSpacing(dp(3), 1f);
        root.addView(note, lp(0, 0, 0, dp(24)));
    }

    private void section(LinearLayout root, String title, String caption) {
        LinearLayout line = row();
        line.setPadding(dp(2), 0, dp(2), 0);
        line.addView(text(title, 15, WatchUi.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (caption != null && !caption.isEmpty()) line.addView(text(caption, 11, WatchUi.MUTED, false));
        root.addView(line, lp(0, dp(22), 0, dp(12)));
    }

    private LinearLayout panel(int color) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(WatchUi.surface(this, color, 22, WatchUi.BORDER));
        return card;
    }

    private void panelLabel(LinearLayout panel, String icon, String title, int color) {
        LinearLayout label = row();
        label.addView(WatchUi.icon(this, icon, color, 20), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(title, 13, WatchUi.SECONDARY, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(9));
        label.addView(t, tp);
        panel.addView(label, lp(0, 0, 0, dp(16)));
    }

    private void statusValue(LinearLayout box, TextView value, TextView sub, int size) {
        value.setText("--");
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        value.setTextColor(WatchUi.TEXT);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setIncludeFontPadding(false);
        box.addView(value, lp(0, 0, 0, dp(8)));
        sub.setText("等待手表上报");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(WatchUi.MUTED);
        sub.setLineSpacing(dp(3), 1f);
        sub.setIncludeFontPadding(false);
        box.addView(sub);
    }

    private Button action(String title, boolean primary, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(title);
        WatchUi.button(b, primary);
        b.setOnClickListener(click);
        return b;
    }

    private void buildOverview(LinearLayout root) {
        heading(root, "状态总览", "睡眠、勿扰与心率，查看最近一次上报。");
        mPermBar = text("需要勿扰权限\n点此授权，让睡眠联动正常工作  →", 13, WatchUi.AMBER, true);
        mPermBar.setPadding(dp(16), dp(14), dp(16), dp(14));
        mPermBar.setLineSpacing(dp(5), 1f);
        mPermBar.setBackground(WatchUi.ripple(this, 0xFF30291D, 16, 0xFF584731));
        mPermBar.setOnClickListener(v -> openDndSettings());
        mPermBar.setVisibility(View.GONE);
        root.addView(mPermBar, lp(0, 0, 0, dp(14)));

        LinearLayout sleepMode = panel(0xFF152720);
        panelLabel(sleepMode, "moon", "手表睡眠模式", WatchUi.ACCENT);
        mSleepModeValue = new TextView(this);
        mSleepModeSub = new TextView(this);
        statusValue(sleepMode, mSleepModeValue, mSleepModeSub, 34);
        root.addView(sleepMode, lp(0, 0, 0, dp(12)));

        LinearLayout dnd = panel(WatchUi.SURFACE);
        panelLabel(dnd, "bell", "手机勿扰", WatchUi.BLUE);
        mDndValue = new TextView(this);
        mDndSub = new TextView(this);
        statusValue(dnd, mDndValue, mDndSub, 24);
        LinearLayout physiology = panel(WatchUi.SURFACE);
        panelLabel(physiology, "moon", "生理睡眠", WatchUi.VIOLET);
        mSleepValue = new TextView(this);
        mSleepSub = new TextView(this);
        statusValue(physiology, mSleepValue, mSleepSub, 24);
        boolean compact = getResources().getConfiguration().screenWidthDp >= 360
                && getResources().getConfiguration().fontScale <= 1.25f;
        if (compact) {
            LinearLayout pair = row();
            pair.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -1, 1);
            a.setMarginEnd(dp(6));
            pair.addView(dnd, a);
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -1, 1);
            b.setMarginStart(dp(6));
            pair.addView(physiology, b);
            root.addView(pair);
        } else {
            root.addView(dnd, lp(0, 0, 0, dp(12)));
            root.addView(physiology);
        }
        mDndBtn = action("切换手机勿扰", false, v -> toggleDnd());
        root.addView(mDndBtn, lp(0, dp(12), 0, 0));

        section(root, "健康数据", "收到上报后更新");
        LinearLayout heart = panel(WatchUi.SURFACE);
        panelLabel(heart, "heart", "手表心率", WatchUi.ROSE);
        mHrValue = new TextView(this);
        mHrSub = new TextView(this);
        mHrUpdated = text("上次更新 · 暂无", 12, WatchUi.MUTED, false);
        statusValue(heart, mHrValue, mHrSub, 30);
        heart.addView(mHrUpdated, lp(0, dp(8), 0, 0));
        mHrBtn = action("上报一次", true, v -> doFetchHeartRate());
        heart.addView(mHrBtn, lp(0, dp(20), 0, 0));
        root.addView(heart);
        TextView note = text("生理睡眠与心率均来自手表上报。没有新数据时，保留最近记录。", 12, WatchUi.MUTED, false);
        note.setLineSpacing(dp(4), 1f);
        root.addView(note, lp(dp(4), dp(14), dp(4), 0));
    }

    private void divider(LinearLayout box) {
        View line = new View(this);
        line.setBackgroundColor(WatchUi.BORDER);
        box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void buildSettings(LinearLayout root) {
        heading(root, "联动设置", "让手表与手机，按你的习惯配合。");
        LinearLayout daily = panel(WatchUi.SURFACE);
        daily.setPadding(dp(16), dp(2), dp(16), dp(2));
        mAlarmSyncSwitch = makeSwitch(false, this::saveAlarmSyncPref);
        daily.addView(switchRow("sync", "手机闹钟同步", "手机响铃时提醒手表；关闭与推迟同步到手机，推迟固定 5 分钟。", mAlarmSyncSwitch));
        divider(daily);
        mMuteSwitch = makeSwitch(false, this::saveMutePref);
        daily.addView(switchRow("bell", "同步静音", "开启勿扰时静音全部通知；关闭后保留优先通知。", mMuteSwitch));
        divider(daily);
        mNotifySwitch = makeSwitch(false, this::saveNotifyPref);
        daily.addView(switchRow("arrow", "通知其他应用", "睡眠模式变化时发送安静通知，供 Tasker 等自动化读取。", mNotifySwitch));
        root.addView(daily);

        section(root, "采集与分析", "按需开启");
        LinearLayout capture = panel(WatchUi.SURFACE);
        capture.setPadding(dp(16), dp(2), dp(16), dp(2));
        mCaptureSwitch = makeSwitch(false, this::saveCapturePref);
        capture.addView(switchRow("code", "报文采集", "记录原始报文供离线分析，文件持续保留，不会自动删除。", mCaptureSwitch));
        root.addView(capture);
        TextView tip = text("采集仅供调试分析。完成后可关闭开关，已保存的数据会保留。", 12, WatchUi.MUTED, false);
        tip.setLineSpacing(dp(4), 1f);
        root.addView(tip, lp(dp(4), dp(14), dp(4), 0));
    }

    private void buildTools(LinearLayout root) {
        heading(root, "工具与诊断", "版本、兼容性与运行记录，都在这里。");
        LinearLayout support = panel(WatchUi.SURFACE);
        panelLabel(support, "shield", "兼容与修复", WatchUi.ACCENT);
        support.addView(text("OPPO 健康", 20, WatchUi.TEXT, true));
        support.addView(text("6.11.7 / 6.10.15", 13, WatchUi.SECONDARY, false), lp(0, dp(6), 0, dp(14)));
        TextView fix = text("找不到「睡眠模式联动手机免打扰」？修复后会重启 OPPO 健康，再到它的设置里开启联动。", 12, WatchUi.SECONDARY, false);
        fix.setLineSpacing(dp(4), 1f);
        support.addView(fix);
        mFixBtn = action("修复联动开关显示", false, v -> doFixSwitch());
        support.addView(mFixBtn, lp(0, dp(16), 0, 0));
        mFixStatus = text("", 12, WatchUi.ACCENT, false);
        mFixStatus.setLineSpacing(dp(4), 1f);
        mFixStatus.setVisibility(View.GONE);
        support.addView(mFixStatus, lp(0, dp(12), 0, 0));
        root.addView(support);

        section(root, "运行维护", "");
        LinearLayout maintenance = panel(WatchUi.SURFACE);
        maintenance.addView(action("刷新状态", false, v -> {
            StateStore.logLocal(this, "UI.refresh 手动刷新");
            refresh();
        }), lp(0, 0, 0, dp(10)));
        maintenance.addView(action("诊断勿扰写入", false, v -> runProbe()));
        TextView caution = text("诊断会测试勿扰写入路径，期间可能短暂切换勿扰状态。", 12, WatchUi.MUTED, false);
        caution.setLineSpacing(dp(4), 1f);
        maintenance.addView(caution, lp(0, dp(10), 0, dp(16)));
        divider(maintenance);
        mDetailsToggle = text("运行详情    ＋", 14, WatchUi.SECONDARY, true);
        mDetailsToggle.setGravity(Gravity.CENTER_VERTICAL);
        mDetailsToggle.setMinHeight(dp(48));
        mDetailsToggle.setBackground(WatchUi.ripple(this, Color.TRANSPARENT, 10, 0));
        mDetailsToggle.setOnClickListener(v -> {
            boolean open = mDetails.getVisibility() != View.VISIBLE;
            mDetails.setVisibility(open ? View.VISIBLE : View.GONE);
            mDetailsToggle.setText(open ? "运行详情    －" : "运行详情    ＋");
        });
        maintenance.addView(mDetailsToggle, lp(0, dp(6), 0, 0));
        mDetails = column();
        mDiag = text("读取中…", 12, WatchUi.MUTED, false);
        mDiag.setLineSpacing(dp(5), 1f);
        mDiag.setTextIsSelectable(true);
        mDetails.addView(mDiag);
        mDetails.setVisibility(View.GONE);
        maintenance.addView(mDetails, lp(0, dp(4), 0, dp(6)));
        root.addView(maintenance);

        section(root, "操作日志", "");
        LinearLayout logs = panel(WatchUi.SURFACE);
        logs.setPadding(dp(16), dp(4), dp(16), dp(4));
        mLogToggle = text("展开操作日志    ＋", 14, WatchUi.SECONDARY, true);
        mLogToggle.setGravity(Gravity.CENTER_VERTICAL);
        mLogToggle.setMinHeight(dp(48));
        mLogToggle.setBackground(WatchUi.ripple(this, Color.TRANSPARENT, 10, 0));
        mLogToggle.setOnClickListener(v -> toggleLog());
        logs.addView(mLogToggle);
        mLogView = text("等待日志…", 11, WatchUi.SECONDARY, false);
        mLogView.setTypeface(Typeface.MONOSPACE);
        mLogView.setLineSpacing(dp(3), 1f);
        mLogView.setTextIsSelectable(true);
        mLogView.setPadding(dp(12), dp(12), dp(12), dp(12));
        mLogView.setBackground(WatchUi.surface(this, WatchUi.BG, 12, 0));
        mLogView.setVisibility(View.GONE);
        logs.addView(mLogView, lp(0, 0, 0, dp(12)));
        root.addView(logs);

        mUpdateBtn = action("检查更新", false, v -> checkUpdate());
        root.addView(mUpdateBtn, lp(0, dp(24), 0, dp(12)));
        TextView version = text("WatchTap  ·  " + versionName(), 12, WatchUi.MUTED, false);
        version.setGravity(Gravity.CENTER);
        root.addView(version);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable ignored) { return ""; }
    }

    /** 布尔回调，避免 lambda 参数类型推断问题。 */
    private interface OnBool { void on(boolean value); }

    private android.widget.Switch makeSwitch(boolean checked, OnBool cb) {
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setId(View.generateViewId());
        sw.setShowText(false);
        sw.setChecked(checked);
        sw.setMinHeight(dp(48));
        sw.setMinimumWidth(dp(48));
        sw.setSplitTrack(false);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{WatchUi.ACCENT, WatchUi.SECONDARY}));
        sw.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{0xFF31594F, 0xFF3C4B59}));
        sw.setOnCheckedChangeListener((btn, value) -> { if (!mBindingUi) cb.on(value); });
        return sw;
    }

    private void bindSwitch(android.widget.Switch sw, boolean checked) {
        if (sw == null || sw.isChecked() == checked) return;
        mBindingUi = true;
        try { sw.setChecked(checked); }
        finally { mBindingUi = false; }
    }

    private LinearLayout switchRow(String icon, String title, String desc, android.widget.Switch control) {
        LinearLayout line = row();
        line.setPadding(0, dp(16), 0, dp(16));
        line.setMinimumHeight(dp(92));
        line.addView(WatchUi.icon(this, icon, WatchUi.ACCENT, 22), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout labels = column();
        TextView label = text(title, 15, WatchUi.TEXT, true);
        label.setId(View.generateViewId());
        label.setLabelFor(control.getId());
        labels.addView(label, lp(0, 0, 0, dp(5)));
        TextView note = text(desc, 12, WatchUi.MUTED, false);
        note.setLineSpacing(dp(3), 1f);
        labels.addView(note);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.setMarginStart(dp(12));
        p.setMarginEnd(dp(12));
        line.addView(labels, p);
        control.setContentDescription(title);
        line.addView(control, new LinearLayout.LayoutParams(-2, dp(48)));
        line.setBackground(WatchUi.ripple(this, Color.TRANSPARENT, 12, 0));
        line.setOnClickListener(v -> control.setChecked(!control.isChecked()));
        return line;
    }

    private LinearLayout buildNavigation() {
        LinearLayout navigation = row();
        navigation.setPadding(dp(14), dp(10), dp(14), dp(10));
        navigation.setBackground(WatchUi.surface(this, WatchUi.BG, 0, 0));
        String[] titles = {"总览", "联动", "工具"};
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(8), dp(8), dp(8), dp(8));
            item.setMinimumHeight(dp(56));
            item.setContentDescription(titles[i]);
            item.setOnClickListener(v -> selectPage(index));
            item.setFocusable(true);
            TextView label = text(titles[i], 11, WatchUi.MUTED, true);
            mNavItems[i] = item;
            mNavLabels[i] = label;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            p.setMargins(dp(4), 0, dp(4), 0);
            navigation.addView(item, p);
        }
        return navigation;
    }

    private void selectPage(int page) {
        mSelectedPage = Math.max(0, Math.min(2, page));
        String[] icons = {"watch", "settings", "tools"};
        for (int i = 0; i < mPages.length; i++) {
            boolean selected = i == mSelectedPage;
            mPages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            LinearLayout nav = mNavItems[i];
            nav.removeAllViews();
            nav.addView(WatchUi.icon(this, icons[i], selected ? WatchUi.ACCENT : WatchUi.MUTED, 22),
                    new LinearLayout.LayoutParams(dp(22), dp(22)));
            mNavLabels[i].setTextColor(selected ? WatchUi.ACCENT : WatchUi.MUTED);
            LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-2, -2);
            label.topMargin = dp(5);
            nav.addView(mNavLabels[i], label);
            nav.setSelected(selected);
            nav.setBackground(WatchUi.ripple(this, selected ? WatchUi.ACCENT_DARK : Color.TRANSPARENT, 18, 0));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("ui.page", mSelectedPage);
        super.onSaveInstanceState(outState);
    }

    /** 检查 GitHub 更新。 */
    private void checkUpdate() {
        mUpdateBtn.setEnabled(false);
        mUpdateBtn.setText("检查中…");
        StateStore.logLocal(this, "开始检查更新");

        UpdateChecker.check(this, r -> {
            mUpdateBtn.setEnabled(true);
            mUpdateBtn.setText("检查更新");

            if (!r.ok) {
                StateStore.logLocal(this, "检查更新失败：" + r.error);
                toast("检查失败：" + r.error);
                return;
            }

            if (!r.hasUpdate) {
                StateStore.logLocal(this, "已是最新版本 " + r.currentTag);
                toast("已是最新版本（" + r.currentTag + "）");
                return;
            }

            StateStore.logLocal(this, "发现新版本 " + r.latestTag);
            showUpdateDialog(r);
        });
    }

    /** 显示更新弹窗。 */
    private void showUpdateDialog(UpdateChecker.Result r) {
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 600) {
            notes = notes.substring(0, 600) + "…";
        }

        String msg = "当前版本：" + r.currentTag + "\n"
                + "最新版本：" + r.latestTag + "\n"
                + (r.apkName.isEmpty() ? "" : "下载文件：" + r.apkName + "\n")
                + "\n"
                + (notes.isEmpty() ? "（无更新说明）" : notes);

        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(WatchUi.TEXT);
        tv.setTextIsSelectable(true);
        int p = dp(14);
        tv.setPadding(p, p, p, p);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.setBackgroundColor(WatchUi.SURFACE);

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 " + r.latestTag)
                .setView(sv)
                .setPositiveButton("下载", (d, w) -> {
                    if (!r.apkUrl.isEmpty()) {
                        UpdateChecker.openDownload(this, r.apkUrl);
                    } else {
                        UpdateChecker.openReleases(this);
                    }
                })
                .setNegativeButton("稍后", null);

        if (!r.apkUrl.isEmpty()) {
            b.setNeutralButton("复制链接", (d, w) -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "watchtap-update", r.apkUrl));
                toast("链接已复制");
            });
        }
        b.show();
    }

    /** 展开/收起操作日志。默认收起，避免主界面太长。 */
    private void toggleLog() {
        mLogExpanded = !mLogExpanded;
        mLogView.setVisibility(mLogExpanded ? View.VISIBLE : View.GONE);
        mLogToggle.setText(mLogExpanded ? "收起操作日志    －" : "展开操作日志    ＋");
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int btm) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, btm);
        return p;
    }

    private int dp(int v) {
        return (int) (v * mDp + 0.5f);
    }

    private void applyInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    // ------------------------------------------------------------ 刷新

    private void tick() {
        if (!mRunning) return;
        refresh();
        refreshHeartRate();
        registerHrReceiver();
        mHandler.postDelayed(this::tick, 1000L);
    }

    private void refresh() {
        Bundle b = read();
        if (b == null) {
            mDiag.setText("跨进程通道不可用：" + StateStore.AUTHORITY);
            return;
        }

        int sm = b.getInt(StateStore.K_SLEEP_MODE, -1);
        long smTs = b.getLong(StateStore.K_SLEEP_MODE_TS, 0);
        long smAt = b.getLong(StateStore.K_SLEEP_MODE_AT, 0);
        int zen = b.getInt(StateStore.K_ZEN, -1);
        long zenTs = b.getLong(StateStore.K_ZEN_TS, 0);
        int sl = b.getInt(StateStore.K_SLEEP_STATE, -1);
        long slTs = b.getLong(StateStore.K_SLEEP_TS, 0);
        long updated = b.getLong(StateStore.K_UPDATED, 0);
        String counts = b.getString(StateStore.K_COUNTS, "");
        String last = b.getString(StateStore.K_LAST_MSG, "");
        String logs = b.getString("logs", "");
        boolean access = b.getBoolean("dndAccess", false);

        // ---- 手表睡眠模式 ----
        if (sm == 1) {
            mSleepModeValue.setText("已开启");
            mSleepModeValue.setTextColor(WatchUi.ACCENT);
        } else if (sm == 0) {
            mSleepModeValue.setText("已关闭");
            mSleepModeValue.setTextColor(WatchUi.SECONDARY);
        } else {
            mSleepModeValue.setText("未收到");
            mSleepModeValue.setTextColor(WatchUi.SECONDARY);
        }
        // 明确区分「当前」和「上次记录」
        if (sm >= 0 && smAt > 0) {
            String when = ago(smAt);
            boolean stale = System.currentTimeMillis() - smAt > 5 * 60 * 1000;
            mSleepModeSub.setText((stale ? "上次记录：" : "更新：") + when);
            mSleepModeSub.setTextColor(stale ? WatchUi.AMBER : WatchUi.MUTED);
        } else {
            mSleepModeSub.setText("等待手表上报");
            mSleepModeSub.setTextColor(WatchUi.MUTED);
        }

        // ---- 勿扰 ----
        if (zen == 0) {
            mDndValue.setText("已关闭");
            mDndValue.setTextColor(WatchUi.SECONDARY);
            mDndSub.setText("通知正常接收");
        } else if (zen > 0) {
            // zen: 1=仅优先 2=完全静音 3=仅闹钟，都算「已开启」
            mDndValue.setText("已开启");
            mDndValue.setTextColor(WatchUi.AMBER);
            String mode = zen == 1 ? "仅优先通知"
                    : zen == 2 ? "完全静音"
                    : zen == 3 ? "仅闹钟"
                    : ("模式 " + zen);
            mDndSub.setText(mode + " · " + ago(zenTs));
        } else {
            mDndValue.setText("--");
            mDndSub.setText("读取失败");
        }

        // ---- 生理睡眠 ----
        if (sl == 1) {
            mSleepValue.setText("睡眠中");
            mSleepValue.setTextColor(WatchUi.VIOLET);
        } else if (sl == 0) {
            mSleepValue.setText("清醒");
            mSleepValue.setTextColor(WatchUi.SECONDARY);
        } else {
            mSleepValue.setText("--");
            mSleepValue.setTextColor(WatchUi.SECONDARY);
        }
        mSleepSub.setText(sl >= 0 ? ago(slTs) : "等待上报");

        // 显示偏好时不触发用户操作回调；原有 provider 回填保持不变。
        boolean mute = Prefs.getBool(this, Prefs.K_MUTE, false);
        boolean notify = Prefs.getBool(this, Prefs.K_NOTIFY, false);
        boolean alarmSync = Prefs.getBool(this, Prefs.K_ALARM_SYNC, true);
        boolean cap = Prefs.getBool(this, Prefs.K_CAPTURE, false);
        bindSwitch(mMuteSwitch, mute);
        bindSwitch(mNotifySwitch, notify);
        bindSwitch(mAlarmSyncSwitch, alarmSync);
        bindSwitch(mCaptureSwitch, cap);
        ProviderPrefs.setBoolean(this, Prefs.K_CAPTURE, cap);
        ProviderPrefs.setBoolean(this, Prefs.K_NOTIFY, notify);
        ProviderPrefs.setBoolean(this, Prefs.K_MUTE, mute);
        ProviderPrefs.setBoolean(this, Prefs.K_ALARM_SYNC, alarmSync);
        mDndBtn.setText(zen > 0 ? "关闭手机勿扰" : "开启手机勿扰");

        // ---- 权限条 ----
        mPermBar.setVisibility(access ? View.GONE : View.VISIBLE);

        // ---- 诊断 ----
        StringBuilder sb = new StringBuilder();
        sb.append("联动方式：在 OPPO 健康进程内用 su 完成，")
                .append("模块进程退出也不影响\n");
        sb.append("勿扰权限：").append(access ? "已授权" : "未授权")
                .append(" · ").append(Dnd.diagnose(this)).append('\n');
        sb.append("模块最后更新：").append(updated > 0 ? ago(updated) : "-").append('\n');
        if (!counts.isEmpty()) {
            String[] c = counts.split("/");
            if (c.length == 2) {
                sb.append("累计：生理 ").append(c[0])
                        .append(" · 睡眠模式 ").append(c[1]).append('\n');
            }
        }
        if (!last.isEmpty()) {
            sb.append("最近消息：").append(last);
        }
        mDiag.setText(sb.toString());

        // ---- 日志 ----
        if (!logs.isEmpty()) {
            mLogView.setText(logs);
        }
    }

    private Bundle read() {
        try {
            return getContentResolver().call(StateStore.URI, StateStore.METHOD_GET, null, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private String ago(long ts) {
        if (ts <= 0) return "未知";
        long d = System.currentTimeMillis() - ts;
        if (d < 0) d = 0;
        String clock = mFmt.format(new Date(ts));
        if (d < 5000) return "刚刚 · " + clock;
        if (d < 60000) return (d / 1000) + " 秒前 · " + clock;
        if (d < 3600000) return (d / 60000) + " 分钟前 · " + clock;
        return (d / 3600000) + " 小时前 · " + clock;
    }

    // ------------------------------------------------------------ 勿扰

    private void toggleDnd() {
        // 不用缓存值判断方向：它可能过期（日志里出现过 req=true 但实际已开启）。
        // 直接读系统当前 zen_mode。
        int zen = Dnd.zen(this);
        boolean turnOn = (zen == 0);

        StateStore.logLocal(this, "UI.toggleDnd 点击 liveZen=" + zen
                + " req=" + turnOn);

        try {
            Bundle b = new Bundle();
            b.putBoolean("on", turnOn);
            b.putBoolean("mute", mMuteSwitch.isChecked());
            Bundle r = getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_TOGGLE_DND, null, b);

            boolean ok = r != null && r.getBoolean("ok", false);
            boolean access = r != null && r.getBoolean("access", false);
            int after = r == null ? -1 : r.getInt("zen", -1);

            if (ok) {
                toast(turnOn ? "已开启勿扰" : "已关闭勿扰");
            } else if (!access) {
                toast("需要勿扰权限，正在跳转…");
                StateStore.logLocal(this, "UI.toggleDnd 失败：无权限");
                openDndSettings();
            } else {
                toast("切换失败（zen=" + after + "），详见日志");
                StateStore.logLocal(this, "UI.toggleDnd 失败 zen=" + after);
            }
        } catch (Throwable t) {
            toast("切换异常：" + t.getClass().getSimpleName());
            StateStore.logLocal(this, "UI.toggleDnd 异常 " + t);
        }
        refresh();
    }

    /**
     * 逐条测试勿扰写入路径，把结果打到 UI 和日志。
     *
     * 用途：一次定位「哪条路径能真正改 zen_mode」，
     * 避免在没有设备时靠猜。
     */
    private void runProbe() {
        StateStore.logLocal(this, "UI.probe 开始诊断勿扰写入");
        try {
            Bundle r = getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_PROBE_DND, null, null);
            String raw = r == null ? "无返回" : r.getString("report", "");
            if (raw == null || raw.isEmpty()) raw = "空报告";
            final String report = raw;

            StateStore.logLocal(this, "UI.probe 结果:\n" + report);

            // 报告可能较长，用可滚动 TextView
            TextView tv = new TextView(this);
            tv.setText(report);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(WatchUi.TEXT);
            tv.setTextIsSelectable(true);
            int pp = dp(16);
            tv.setPadding(pp, pp, pp, pp);

            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            sv.setBackgroundColor(WatchUi.SURFACE);

            new android.app.AlertDialog.Builder(this)
                    .setTitle("勿扰写入诊断")
                    .setView(sv)
                    .setPositiveButton("知道了", null)
                    .setNeutralButton("复制", (d, w) -> {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(
                                        CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "watchtap-probe", report));
                        toast("已复制到剪贴板");
                    })
                    .show();
        } catch (Throwable t) {
            StateStore.logLocal(this, "UI.probe 异常 " + t);
            toast("诊断失败：" + t.getClass().getSimpleName());
        }
        refresh();
    }


    /**
     * 执行开关修复：让 OPPO 健康显示联动选项。
     *
     * 免 root 版本：
     *   1. 写进当前进程的偏好
     *   2. 通过 provider 推给 OPPO 健康进程（普通 IPC，不需要特殊权限）
     *   3. 重启 OPPO 健康
     *
     * hook 是无条件安装的，所以只要开关值能传到 hook 进程，
     * 下次它判断时就生效 —— 不需要提前写文件。
     */
    private void doFixSwitch() {
        Prefs.setBool(this, Prefs.K_FIX_SWITCH, true);

        // provider 通道（免 root）
        boolean pushed = false;
        try {
            Bundle b = new Bundle();
            b.putBoolean(StateStore.K_FIX_SWITCH, true);
            getContentResolver().call(StateStore.URI, StateStore.METHOD_PUT, null, b);

            // 同时推给 hook 进程，让它立刻更新内存缓存
            pushed = HookPrefsChannel.push(Prefs.K_FIX_SWITCH, true);
        } catch (Throwable t) {
            StateStore.logLocal(this, "推送失败：" + t);
        }

        StateStore.logLocal(this, "执行开关修复（root=" + Root.available()
                + ", pushed=" + pushed + "）");

        mFixStatus.setVisibility(View.VISIBLE);
        mFixStatus.setText("✓ 已启用修复\n\n正在重启 OPPO 健康…");

        restartTarget();

        mFixStatus.postDelayed(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("✓ 已启用修复\n\n");
            sb.append("OPPO 健康已重启。现在打开它的设置，看是否有\n");
            sb.append("「睡眠模式联动手机防打扰」选项。\n\n");
            sb.append("看到后手动打开它，选项显示会被 App 记住，\n");
            sb.append("之后无需再点这个按钮。\n\n");
            if (!Root.available()) {
                sb.append("（无 root 模式：若没生效，请手动重启一次 OPPO 健康）");
            }
            mFixStatus.setText(sb.toString());
        }, 3000L);

        toast("已启用，正在重启 OPPO 健康");
    }

    /**
     * 重启 OPPO 健康，让 hook 重新加载。
     *
     * 优先用 root（彻底杀掉进程）；没 root 就用 ActivityManager 强杀，
     * 效果差一些但通常够用。
     */
    private void restartTarget() {
        final String pkg = "com.heytap.health";

        // 有 root：直接 force-stop，最可靠
        if (Root.available()) {
            String out = Root.exec("am force-stop " + pkg, 6000);
            StateStore.logLocal(this, "已停止 OPPO 健康（root）" + out.trim());

            // 隔一秒再拉起，避免 force-stop 后立即启动被限制
            mFixStatus.postDelayed(() -> {
                Root.exec("monkey -p " + pkg
                        + " -c android.intent.category.LAUNCHER 1", 8000);
                StateStore.logLocal(this, "已拉起 OPPO 健康");
            }, 1200L);
            return;
        }

        // 没 root：用 ActivityManager 强杀
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(pkg);
                StateStore.logLocal(this, "已尝试结束 OPPO 健康后台进程（无 root）");
            }
        } catch (Throwable t) {
            StateStore.logLocal(this, "结束进程失败：" + t);
        }

        // 拉起
        try {
            android.content.Intent i = getPackageManager()
                    .getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                StateStore.logLocal(this, "已拉起 OPPO 健康");
            }
        } catch (Throwable t) {
            StateStore.logLocal(this, "拉起失败：" + t);
        }
    }

    /** 保存「通知其他应用」偏好。 */
    private void saveNotifyPref(boolean on) {
        Prefs.setBool(this, Prefs.K_NOTIFY, on);
        ProviderPrefs.setBoolean(this, Prefs.K_NOTIFY, on);
        HookPrefsChannel.push(Prefs.K_NOTIFY, on);
        StateStore.logLocal(this, "通知其他应用：" + (on ? "已开启" : "已关闭"));
        if (on) {
            toast("已开启，手表变化时会发通知");
        }
    }

    /** 手动触发一次心率上报（真正发送在 hook 进程里做，这里只发指令）。 */
    private void doFetchHeartRate() {
        try {
            android.content.Intent i = new android.content.Intent(AlarmLink.ACTION_NOTIFY_TEST);
            i.setPackage("com.heytap.health");
            i.putExtra("hr", "1");
            sendBroadcast(i);
            toast("已请求，等手表回传…");
        } catch (Throwable t) {
            toast("发送请求失败：" + t);
        }
        mHrBtn.setEnabled(false);
        mHrBtn.setAlpha(0.6f);
        mHrBtn.setText("等待回传…");
        mHrBtn.postDelayed(() -> {
            if (mHrBtn != null) {
                mHrBtn.setEnabled(true);
                mHrBtn.setAlpha(1f);
                mHrBtn.setText("上报一次");
            }
        }, 6000);
    }

    /** hook 进程回报的结果。 */
    private final android.content.BroadcastReceiver mHrResult = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            try {
                if (i != null && i.hasExtra("bpm")) {
                    mHrBpm = i.getIntExtra("bpm", -1);
                    mHrMin = i.getIntExtra("min", -1);
                    mHrMax = i.getIntExtra("max", -1);
                    mHrCount = i.getIntExtra("count", 0);
                    mHrTs = i.getLongExtra("ts", 0);
                    String m = i.getStringExtra("msg");
                    mHrMsg = m == null ? "" : m;
                }
            } catch (Throwable ignored) {
            }
            refreshHeartRate();
        }
    };

    private boolean mHrRxDone;

    private void registerHrReceiver() {
        if (mHrRxDone) return;
        try {
            android.content.IntentFilter f =
                    new android.content.IntentFilter(AlarmLink.ACTION_HR_RESULT);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mHrResult, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mHrResult, f);
            }
            mHrRxDone = true;
        } catch (Throwable ignored) {
        }
    }

    /** 刷新心率卡片：展示最近一次广播缓存，不在刷新时发起请求。 */
    private void refreshHeartRate() {
        if (mHrValue == null) return;
        int bpm = mHrBpm, min = mHrMin, max = mHrMax, cnt = mHrCount;
        long ts = mHrTs;
        String msg = mHrMsg;

        if (bpm < 0) {
            mHrValue.setText("-- bpm");
            mHrValue.setTextColor(WatchUi.SECONDARY);
            mHrSub.setText(msg == null || msg.isEmpty() ? "还没有心率数据，点击按钮上报一次" : msg);
            mHrUpdated.setText("上次更新 · 暂无");
            return;
        }

        long now = System.currentTimeMillis() / 1000L;
        boolean fresh = ts > 0 && now - ts < 300;
        mHrValue.setText(bpm + " bpm");
        mHrValue.setTextColor(fresh ? WatchUi.ACCENT : WatchUi.TEXT);
        mHrSub.setText("范围 " + min + "–" + max + " bpm  ·  " + cnt + " 个数据点");
        mHrUpdated.setText("更新于 " + agoText(ts) + "（" + hhmm(ts) + "）");
        mHrUpdated.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mHrUpdated.setTextColor(WatchUi.MUTED);
    }

    private String agoText(long sec) {
        if (sec <= 0) return "未知";
        long ago = System.currentTimeMillis() / 1000L - sec;
        if (ago < 60) return "刚刚";
        if (ago < 3600) return (ago / 60) + " 分钟前";
        if (ago < 86400) return (ago / 3600) + " 小时前";
        return (ago / 86400) + " 天前";
    }

    private String hhmm(long sec) {
        if (sec <= 0) return "-";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(sec * 1000L);
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /** 保存「报文采集」偏好。 */
    private void saveCapturePref(boolean on) {
        Prefs.setBool(this, Prefs.K_CAPTURE, on);
        ProviderPrefs.setBoolean(this, Prefs.K_CAPTURE, on);
        // hook 进程读的是这个文件，必须同步过去否则开关不生效
        HookPrefsChannel.push(Prefs.K_CAPTURE, on);
        StateStore.logLocal(this, "报文采集：" + (on ? "已开启" : "已关闭"));
    }

    /** 保存「手机闹钟同步」偏好。 */
    private void saveAlarmSyncPref(boolean on) {
        Prefs.setBool(this, Prefs.K_ALARM_SYNC, on);
        ProviderPrefs.setBoolean(this, Prefs.K_ALARM_SYNC, on);
        HookPrefsChannel.push(Prefs.K_ALARM_SYNC, on);
        pushAlarmSyncToProvider(on);
        StateStore.logLocal(this, "手机闹钟同步：" + (on ? "已开启" : "已关闭"));
        if (on) {
            toast("已开启：手机闹钟会推送到手表");
        } else {
            toast("已关闭：不再同步手机闹钟");
        }
    }

    /** 把开关值同步给 hook 进程用的 provider。 */
    private void pushAlarmSyncToProvider(boolean on) {
        try {
            android.os.Bundle in = new android.os.Bundle();
            in.putBoolean(StateStore.K_ALARM_SYNC, on);
            getContentResolver().call(StateStore.URI, StateStore.METHOD_SYNC_PREFS, null, in);
        } catch (Throwable ignored) {
        }
    }

    /** 保存「同步静音」偏好。 */
    private void saveMutePref(boolean mute) {
        Prefs.setBool(this, Prefs.K_MUTE, mute);
        ProviderPrefs.setBoolean(this, Prefs.K_MUTE, mute);
        HookPrefsChannel.push(Prefs.K_MUTE, mute);
        StateStore.logLocal(this, "同步静音：" + (mute ? "已开启" : "已关闭"));
    }

    private void openDndSettings() {
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        // 不弹出 Toast；操作结果由界面状态与日志呈现。
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRunning = true;
        mHandler.removeCallbacksAndMessages(null);
        registerHrReceiver();
        tick();
        ensureNotifyPermission();
    }

    /** Android 13+ 需要运行时申请通知权限，否则发不出通知。 */
    private void ensureNotifyPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) return;
            String perm = "android.permission.POST_NOTIFICATIONS";
            if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[]{perm}, 2001);
            StateStore.logLocal(this, "申请通知权限");
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (mHrRxDone) {
            try { unregisterReceiver(mHrResult); } catch (Throwable ignored) {}
            mHrRxDone = false;
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRunning = false;
        mHandler.removeCallbacksAndMessages(null);
    }
}
