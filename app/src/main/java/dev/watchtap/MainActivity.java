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
 * WatchTap 界面：实时显示手表状态，并提供勿扰手动开关。
 *
 * 数据来源：StateStore（ContentProvider），由 hook 进程写入。
 * 刷新频率：1 秒。
 */
public class MainActivity extends Activity {

    // ---- 卡片 ----
    private TextView mHrValue, mHrSub;
    private TextView mSleepModeValue, mSleepModeSub;
    private TextView mDndValue, mDndSub;
    private TextView mSleepValue, mSleepSub;
    private TextView mDiag;
    private TextView mPermBar;
    private Button mDndBtn;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRunning = true;

    private final SimpleDateFormat mFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private int mDp;

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
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, lp(pad, dp(8), pad, dp(2)));

        TextView subtitle = new TextView(this);
        subtitle.setText("手表状态监控 · 每秒刷新");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(0xFF8B94A3);
        root.addView(subtitle, lp(pad, 0, pad, dp(16)));

        // ---- 权限提示条（未授权时才显示）----
        mPermBar = new TextView(this);
        mPermBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mPermBar.setTextColor(Color.WHITE);
        mPermBar.setBackgroundColor(0xFFDC2626);
        int pp = dp(12);
        mPermBar.setPadding(pp, pp, pp, pp);
        mPermBar.setText("⚠ 未授予勿扰权限，无法自动切换。点此授权 →");
        mPermBar.setOnClickListener(v -> openDndSettings());
        mPermBar.setVisibility(View.GONE);
        root.addView(mPermBar, lp(pad, 0, pad, dp(12)));

        // ---- 卡片 1: 心率 ----
        mHrValue = new TextView(this);
        mHrSub = new TextView(this);
        root.addView(card("心率", "bpm", mHrValue, mHrSub));

        // ---- 卡片 2: 手表睡眠模式 ----
        mSleepModeValue = new TextView(this);
        mSleepModeSub = new TextView(this);
        root.addView(card("手表睡眠模式", "", mSleepModeValue, mSleepModeSub));

        // ---- 卡片 3: 勿扰模式 ----
        mDndValue = new TextView(this);
        mDndSub = new TextView(this);
        root.addView(card("手机勿扰模式", "", mDndValue, mDndSub));

        // ---- 卡片 4: 生理睡眠检测 ----
        mSleepValue = new TextView(this);
        mSleepSub = new TextView(this);
        root.addView(card("生理睡眠检测", "", mSleepValue, mSleepSub));

        // ---- 勿扰开关 ----
        mDndBtn = new Button(this);
        mDndBtn.setText("切换勿扰");
        styleButton(mDndBtn);
        mDndBtn.setOnClickListener(v -> toggleDnd());
        root.addView(mDndBtn, lp(pad, dp(8), pad, dp(8)));

        // ---- 诊断信息 ----
        mDiag = new TextView(this);
        mDiag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mDiag.setTextColor(0xFF6B7280);
        mDiag.setLineSpacing(dp(3), 1f);
        mDiag.setText("读取中…");
        root.addView(mDiag, lp(pad, dp(16), pad, dp(8)));

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        applyInsets(scroll);
        tick();
    }

    // ------------------------------------------------------------ UI 构建

    private LinearLayout card(String name, String unit, TextView value, TextView sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF1A1D24);
        int p = dp(14);
        box.setPadding(p, p, p, p);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(0xFF8B94A3);
        box.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        value.setText("--");
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        value.setTextColor(Color.WHITE);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(value);

        if (!unit.isEmpty()) {
            TextView u = new TextView(this);
            u.setText(" " + unit);
            u.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            u.setTextColor(0xFF6B7280);
            row.addView(u);
        }
        box.addView(row);

        sub.setText("等待数据");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(0xFF6B7280);
        box.addView(sub);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        wrap.setPadding(pad, dp(6), pad, dp(6));
        wrap.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private void styleButton(Button b) {
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF2563EB);
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
            mDiag.setText("跨进程通道不可用：ContentProvider(" + StateStore.AUTHORITY + ")");
            return;
        }

        int hr = b.getInt(StateStore.K_HR, -1);
        long hrTs = b.getLong(StateStore.K_HR_TS, 0);
        int sm = b.getInt(StateStore.K_SLEEP_MODE, -1);
        long smTs = b.getLong(StateStore.K_SLEEP_MODE_TS, 0);
        int zen = b.getInt(StateStore.K_ZEN, -1);
        long zenTs = b.getLong(StateStore.K_ZEN_TS, 0);
        int sl = b.getInt(StateStore.K_SLEEP_STATE, -1);
        long slTs = b.getLong(StateStore.K_SLEEP_TS, 0);
        long updated = b.getLong(StateStore.K_UPDATED, 0);
        String counts = b.getString(StateStore.K_COUNTS, "");
        String last = b.getString(StateStore.K_LAST_MSG, "");

        // 心率
        mHrValue.setText(hr > 0 ? String.valueOf(hr) : "--");
        mHrSub.setText(hr > 0 ? ago(hrTs) : "等待心率数据");

        // 手表睡眠模式
        if (sm == 1) {
            mSleepModeValue.setText("已开启");
            mSleepModeValue.setTextColor(0xFF60A5FA);
        } else if (sm == 0) {
            mSleepModeValue.setText("已关闭");
            mSleepModeValue.setTextColor(0xFF9CA3AF);
        } else {
            mSleepModeValue.setText("--");
            mSleepModeValue.setTextColor(0xFF9CA3AF);
        }
        mSleepModeSub.setText(sm >= 0 ? ago(smTs) : "等待手表上报");

        // 勿扰
        if (zen == 0) {
            mDndValue.setText("关闭");
            mDndValue.setTextColor(0xFF9CA3AF);
            mDndSub.setText("通知正常接收");
        } else if (zen == 3) {
            mDndValue.setText("仅闹钟");
            mDndValue.setTextColor(0xFFF59E0B);
            mDndSub.setText(ago(zenTs) + " · 闹钟仍会响");
        } else if (zen > 0) {
            mDndValue.setText("开启 (" + zen + ")");
            mDndValue.setTextColor(0xFFF59E0B);
            mDndSub.setText(ago(zenTs));
        } else {
            mDndValue.setText("--");
            mDndSub.setText("读取失败");
        }

        // 生理睡眠
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

        // 权限提示条
        boolean hasPerm = Dnd.hasAccess(this);
        mPermBar.setVisibility(hasPerm ? View.GONE : View.VISIBLE);

        // 诊断
        StringBuilder sb = new StringBuilder();
        sb.append("勿扰权限：").append(hasPerm ? "已授权" : "未授权")
                .append("（未授权时无法切换）\n");
        sb.append("模块最后更新：").append(updated > 0 ? ago(updated) : "-").append('\n');
        if (!counts.isEmpty()) {
            String[] c = counts.split("/");
            if (c.length == 3) {
                sb.append("累计：心率 ").append(c[0])
                        .append(" · 生理 ").append(c[1])
                        .append(" · 睡眠模式 ").append(c[2]).append('\n');
            }
        }
        if (!last.isEmpty()) {
            sb.append("最近消息：").append(last);
        }
        mDiag.setText(sb.toString());
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
        if (d < 5000) return "刚刚 · " + mFmt.format(new Date(ts));
        if (d < 60000) return (d / 1000) + " 秒前 · " + mFmt.format(new Date(ts));
        if (d < 3600000) return (d / 60000) + " 分钟前 · " + mFmt.format(new Date(ts));
        return (d / 3600000) + " 小时前 · " + mFmt.format(new Date(ts));
    }

    // ------------------------------------------------------------ 勿扰开关

    private void toggleDnd() {
        Bundle cur = read();
        int zen = cur == null ? 0 : cur.getInt(StateStore.K_ZEN, 0);
        boolean turnOn = zen == 0;

        try {
            Bundle b = new Bundle();
            b.putBoolean("on", turnOn);
            Bundle r = getContentResolver().call(
                    StateStore.URI, StateStore.METHOD_TOGGLE_DND, null, b);
            boolean ok = r != null && r.getBoolean("ok", false);

            if (ok) {
                toast(turnOn ? "已开启勿扰（仅闹钟）" : "已关闭勿扰");
            } else if (!Dnd.hasAccess(this)) {
                toast("需要授予勿扰权限");
                openDndSettings();
            } else {
                toast("切换失败");
            }
        } catch (Throwable t) {
            toast("切换失败：" + t.getClass().getSimpleName());
        }
        refresh();
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRunning = false;
        mHandler.removeCallbacksAndMessages(null);
    }
}
