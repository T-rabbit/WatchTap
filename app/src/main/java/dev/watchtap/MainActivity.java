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
import java.util.Locale;

/**
 * WatchTap 界面。
 *
 * 三个状态卡片 + 勿扰开关 + 操作日志。
 * 数据由 hook 进程写入 StateStore，本界面每秒读取一次。
 */
public class MainActivity extends Activity {

    private TextView mSleepModeValue, mSleepModeSub;
    private TextView mDndValue, mDndSub;
    private TextView mSleepValue, mSleepSub;
    private TextView mPermBar;
    private TextView mDiag;
    private Button mUpdateBtn;
    private TextView mLogView;
    private TextView mLogToggle;
    private boolean mLogExpanded = false;
    private Button mDndBtn;
    private android.widget.Switch mMuteSwitch;
    private android.widget.Switch mNotifySwitch;
    private Button mFixBtn;
    private TextView mFixStatus;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRunning = true;
    private int mDp;

    private final SimpleDateFormat mFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDp = (int) getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0F1115);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(8), pad, dp(32));

        // 标题
        TextView title = new TextView(this);
        title.setText("WatchTap");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, lp(pad, dp(6), pad, dp(1)));

        TextView subtitle = new TextView(this);
        subtitle.setText("手表状态监控 · 每秒刷新");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(0xFF8B94A3);
        root.addView(subtitle, lp(pad, 0, pad, dp(10)));

        // 权限提示条
        mPermBar = new TextView(this);
        mPermBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mPermBar.setTextColor(Color.WHITE);
        mPermBar.setBackgroundColor(0xFFDC2626);
        int pp = dp(12);
        mPermBar.setPadding(pp, pp, pp, pp);
        mPermBar.setText("⚠ 未授予勿扰权限，点此授权 →");
        mPermBar.setOnClickListener(v -> openDndSettings());
        mPermBar.setVisibility(View.GONE);
        root.addView(mPermBar, lp(pad, 0, pad, dp(12)));

        // 卡片 1: 手表睡眠模式
        mSleepModeValue = new TextView(this);
        mSleepModeSub = new TextView(this);
        root.addView(card("手表睡眠模式", mSleepModeValue, mSleepModeSub));

        // 卡片 2: 手机勿扰
        mDndValue = new TextView(this);
        mDndSub = new TextView(this);
        root.addView(card("手机勿扰模式", mDndValue, mDndSub));

        // 卡片 3: 生理睡眠
        mSleepValue = new TextView(this);
        mSleepSub = new TextView(this);
        root.addView(card("生理睡眠检测", mSleepValue, mSleepSub));

        // 开关区（统一构造）
        mMuteSwitch = makeSwitch(true, this::saveMutePref);
        root.addView(switchRow("同步静音",
                "开启后勿扰会静音全部通知；关闭则允许优先通知",
                mMuteSwitch));

        mNotifySwitch = makeSwitch(false, this::saveNotifyPref);
        root.addView(switchRow("通知其他应用",
                "手表进入/退出睡眠模式时发一条安静通知，供 Tasker 等自动化捕获",
                mNotifySwitch));

        // 修复联动开关（按钮式：点一下执行一次，不需要保持开启）
        mFixBtn = new Button(this);
        mFixBtn.setText("修复联动开关显示");
        mFixBtn.setAllCaps(false);
        mFixBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mFixBtn.setTextColor(Color.WHITE);
        mFixBtn.setBackgroundColor(0xFF7C3AED);
        mFixBtn.setMinHeight(0);
        mFixBtn.setMinimumHeight(0);
        mFixBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
        mFixBtn.setOnClickListener(v -> doFixSwitch());

        root.addView(mFixBtn, lp(pad, dp(6), pad, dp(2)));

        TextView fixDesc = new TextView(this);
        fixDesc.setText("让 OPPO 健康里出现「睡眠模式联动手机免打扰」选项。"
                + "点一次即可，之后重启 OPPO 健康生效");
        fixDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        fixDesc.setTextColor(0xFF6B7280);
        fixDesc.setPadding(dp(4), 0, dp(4), dp(4));
        root.addView(fixDesc);

        mFixStatus = new TextView(this);
        mFixStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mFixStatus.setTextColor(0xFF9CA3AF);
        mFixStatus.setPadding(dp(4), dp(2), dp(4), dp(6));
        mFixStatus.setVisibility(View.GONE);
        root.addView(mFixStatus);

        // 勿扰按钮
        mDndBtn = new Button(this);
        mDndBtn.setText("切换勿扰");
        mDndBtn.setAllCaps(false);
        mDndBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mDndBtn.setTextColor(Color.WHITE);
        mDndBtn.setBackgroundColor(0xFF2563EB);
        mDndBtn.setMinHeight(0);
        mDndBtn.setMinimumHeight(0);
        mDndBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
        mDndBtn.setOnClickListener(v -> toggleDnd());
        root.addView(mDndBtn, lp(pad, dp(8), pad, dp(4)));

        // 诊断按钮：逐条测试勿扰写入路径
        Button probe = new Button(this);
        probe.setText("诊断勿扰写入");
        probe.setAllCaps(false);
        probe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        probe.setMinHeight(0);
        probe.setMinimumHeight(0);
        probe.setPadding(dp(8), dp(6), dp(8), dp(6));
        probe.setTextColor(0xFFCBD5E1);
        probe.setBackgroundColor(0xFF7C2D12);
        probe.setOnClickListener(v -> runProbe());
        root.addView(probe, lp(pad, dp(4), pad, dp(4)));

        // 刷新按钮
        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setAllCaps(false);
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        refresh.setMinHeight(0);
        refresh.setMinimumHeight(0);
        refresh.setPadding(dp(8), dp(6), dp(8), dp(6));
        refresh.setTextColor(0xFFCBD5E1);
        refresh.setBackgroundColor(0xFF1E293B);
        refresh.setOnClickListener(v -> {
            StateStore.logLocal(this, "UI.refresh 手动刷新");
            refresh();
        });
        root.addView(refresh, lp(pad, dp(4), pad, dp(4)));

        // 检查更新
        mUpdateBtn = new Button(this);
        mUpdateBtn.setText("检查更新");
        mUpdateBtn.setAllCaps(false);
        mUpdateBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mUpdateBtn.setMinHeight(0);
        mUpdateBtn.setMinimumHeight(0);
        mUpdateBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
        mUpdateBtn.setTextColor(0xFFCBD5E1);
        mUpdateBtn.setBackgroundColor(0xFF1E293B);
        mUpdateBtn.setOnClickListener(v -> checkUpdate());
        root.addView(mUpdateBtn, lp(pad, dp(4), pad, dp(14)));

        // 诊断
        mDiag = new TextView(this);
        mDiag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mDiag.setTextColor(0xFF6B7280);
        mDiag.setLineSpacing(dp(3), 1f);
        mDiag.setText("读取中…");
        root.addView(mDiag, lp(pad, dp(8), pad, dp(12)));

        // 日志标题（点击展开/收起）
        mLogToggle = new TextView(this);
        mLogToggle.setText("▸ 操作日志");
        mLogToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mLogToggle.setTextColor(0xFF8B94A3);
        mLogToggle.setPadding(dp(4), dp(8), dp(4), dp(8));
        mLogToggle.setOnClickListener(v -> toggleLog());
        root.addView(mLogToggle, lp(pad, dp(4), pad, 0));

        // 日志正文
        mLogView = new TextView(this);
        mLogView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mLogView.setTextColor(0xFF7DD3FC);
        mLogView.setTypeface(Typeface.MONOSPACE);
        mLogView.setLineSpacing(dp(2), 1f);
        mLogView.setTextIsSelectable(true);
        mLogView.setBackgroundColor(0xFF111827);
        int lpad = dp(10);
        mLogView.setPadding(lpad, lpad, lpad, lpad);
        mLogView.setText("等待日志…");
        mLogView.setVisibility(View.GONE);   // 默认收起
        root.addView(mLogView, lp(pad, 0, pad, dp(16)));

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        applyInsets(scroll);
        StateStore.logLocal(this, "UI.onCreate 界面启动");
        tick();
    }

    // ------------------------------------------------------------ UI 构建

    private LinearLayout card(String name, TextView value, TextView sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF1A1D24);
        int p = dp(10);
        box.setPadding(p, p, p, p);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(0xFF8B94A3);
        box.addView(label);

        value.setText("--");
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        value.setTextColor(Color.WHITE);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(value);

        sub.setText("等待数据");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setTextColor(0xFF6B7280);
        box.addView(sub);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        wrap.setPadding(pad, dp(3), pad, dp(3));
        wrap.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 布尔回调，避免 lambda 参数类型推断问题。 */
    private interface OnBool {
        void on(boolean value);
    }

    /** 造一个紧凑的 Switch。 */
    private android.widget.Switch makeSwitch(boolean checked, OnBool cb) {
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((btn, value) -> cb.on(value));
        sw.setMinHeight(0);
        sw.setMinimumHeight(0);
        return sw;
    }

    /** 一行：左边标题+说明，右边开关。 */
    private LinearLayout switchRow(String title, String desc, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xFF1A1D24);
        int p = dp(10);
        row.setPadding(p, p, p, p);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Color.WHITE);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        d.setTextColor(0xFF6B7280);

        texts.addView(t);
        texts.addView(d);

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (control != null) row.addView(control);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int wp = dp(16);
        wrap.setPadding(wp, dp(2), wp, dp(2));
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
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
                + "最新版本：" + r.latestTag + "\n\n"
                + (notes.isEmpty() ? "（无更新说明）" : notes);

        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(0xFFE5E7EB);
        tv.setTextIsSelectable(true);
        int p = dp(14);
        tv.setPadding(p, p, p, p);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.setBackgroundColor(0xFF111827);

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
        mLogToggle.setText(mLogExpanded ? "▾ 操作日志" : "▸ 操作日志");
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
            mSleepModeValue.setTextColor(0xFF60A5FA);
        } else if (sm == 0) {
            mSleepModeValue.setText("已关闭");
            mSleepModeValue.setTextColor(0xFF9CA3AF);
        } else {
            mSleepModeValue.setText("未收到");
            mSleepModeValue.setTextColor(0xFF9CA3AF);
        }
        // 明确区分「当前」和「上次记录」
        if (sm >= 0 && smAt > 0) {
            String when = ago(smAt);
            boolean stale = System.currentTimeMillis() - smAt > 5 * 60 * 1000;
            mSleepModeSub.setText((stale ? "上次记录：" : "更新：") + when);
            mSleepModeSub.setTextColor(stale ? 0xFFF59E0B : 0xFF6B7280);
        } else {
            mSleepModeSub.setText("等待手表上报");
            mSleepModeSub.setTextColor(0xFF6B7280);
        }

        // ---- 勿扰 ----
        if (zen == 0) {
            mDndValue.setText("已关闭");
            mDndValue.setTextColor(0xFF9CA3AF);
            mDndSub.setText("通知正常接收");
        } else if (zen > 0) {
            // zen: 1=仅优先 2=完全静音 3=仅闹钟，都算「已开启」
            mDndValue.setText("已开启");
            mDndValue.setTextColor(0xFFF59E0B);
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
            mSleepValue.setTextColor(0xFF818CF8);
        } else if (sl == 0) {
            mSleepValue.setText("清醒");
            mSleepValue.setTextColor(0xFF9CA3AF);
        } else {
            mSleepValue.setText("--");
            mSleepValue.setTextColor(0xFF9CA3AF);
        }
        mSleepSub.setText(sl >= 0 ? ago(slTs) : "等待上报");

        // ---- 同步静音开关 ----
        boolean mute = Prefs.getBool(this, Prefs.K_MUTE, true);
        if (mMuteSwitch.isChecked() != mute) {
            mMuteSwitch.setOnCheckedChangeListener(null);
            mMuteSwitch.setChecked(mute);
            mMuteSwitch.setOnCheckedChangeListener((btn, checked) -> saveMutePref(checked));
        }

        // ---- 通知开关 ----
        boolean notify = Prefs.getBool(this, Prefs.K_NOTIFY, false);
        if (mNotifySwitch.isChecked() != notify) {
            mNotifySwitch.setOnCheckedChangeListener(null);
            mNotifySwitch.setChecked(notify);
            mNotifySwitch.setOnCheckedChangeListener((btn, checked) -> saveNotifyPref(checked));
        }

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
            tv.setTextColor(0xFFE5E7EB);
            tv.setTextIsSelectable(true);
            int pp = dp(16);
            tv.setPadding(pp, pp, pp, pp);

            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            sv.setBackgroundColor(0xFF111827);

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
        HookPrefsChannel.push(Prefs.K_NOTIFY, on);
        StateStore.logLocal(this, "通知其他应用：" + (on ? "已开启" : "已关闭"));
        if (on) {
            toast("已开启，手表变化时会发通知");
        }
    }

    /** 保存「同步静音」偏好。 */
    private void saveMutePref(boolean mute) {
        Prefs.setBool(this, Prefs.K_MUTE, mute);
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
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRunning = true;
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
    protected void onPause() {
        super.onPause();
        mRunning = false;
        mHandler.removeCallbacksAndMessages(null);
    }
}
