#!/usr/bin/env bash
# HyperOS 手机闹钟行为探测（只读，不改任何设置）
# 用法：adb 连好手机后   bash probe-alarm.sh  > alarm-probe.txt
set -u
ADB="${ADB:-adb}"
say() { echo; echo "===== $1 ====="; }

say "0. 设备"
$ADB shell getprop ro.product.manufacturer
$ADB shell getprop ro.product.model
$ADB shell getprop ro.build.version.release
$ADB shell getprop ro.mi.os.version.name 2>/dev/null
$ADB shell getprop ro.miui.ui.version.name 2>/dev/null

say "1. 时钟相关包"
$ADB shell pm list packages | grep -iE "clock|deskclock|alarm"

say "2. 下一个闹钟（AlarmManager 视角）"
$ADB shell dumpsys alarm | grep -iE "next alarm|AlarmClock|RTC_WAKEUP.*alarmclock" | head -20

say "3. 时钟 App 的版本与权限"
for p in com.android.deskclock com.miui.clock com.coloros.alarmclock; do
  echo "--- $p"
  $ADB shell dumpsys package "$p" 2>/dev/null | grep -E "versionName|firstInstallTime" | head -3
done

say "4. OPPO 健康 / 本模块 的精确闹钟与闹钟权限"
for p in com.heytap.health dev.watchtap; do
  echo "--- $p"
  $ADB shell dumpsys package "$p" 2>/dev/null | grep -iE "SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|ACCESS_CLOCK|SET_ALARM" | head -6
done

say "5. ColorOS 平台闹钟组件是否存在（预期：不存在）"
$ADB shell pm list packages | grep -i coloros
$ADB shell cmd package resolve-activity --brief -a com.coloros.platformalarmclock.platform.awaken_clock_action 2>/dev/null | tail -2

say "6. OPPO 健康是否成功绑定过平台闹钟（看历史日志）"
$ADB logcat -d -t 400 2>/dev/null | grep -iE "PlatformClockManager|PlatformClockBroadcastReceiver|alarmClockRing" | tail -15

say "完 —— 把本文件内容发回即可"
