# WatchTap

一个 LSPosed 模块：拦截 **OPPO 健康（com.heytap.health）** 与 **OPPO Watch** 之间的全部蓝牙消息，并在 HyperOS 上打通「手表睡眠模式 → 手机免打扰」联动。

基于 **libxposed API 102**，适配 Android 17 / HyperOS 4.0。

---

## 功能

| 功能 | 说明 |
|---|---|
| **实时监控** | 手表睡眠模式 / 勿扰状态 / 生理睡眠，每秒刷新 |
| **自动联动** | 手表进入睡眠模式 → 手机自动开启勿扰（仅放行闹钟） |
| **手动控制** | UI 一键切换勿扰 + 写路径诊断 |
| **全链路日志** | logcat + 文件 + UI 三通道，功能触发均有记录 |

### 界面

```
┌──────────────────────────────────┐
│ WatchTap                          │
│ 手表状态监控 · 每秒刷新            │
├──────────────────────────────────┤
│ 手表睡眠模式                       │
│  已开启                            │
│  更新：刚刚 · 12:01:33             │
├──────────────────────────────────┤
│ 手机勿扰模式                       │
│  仅闹钟                            │
│  刚刚 · 12:01:35 · 闹钟仍会响      │
├──────────────────────────────────┤
│ 生理睡眠检测                       │
│  睡眠中                            │
│  1 分钟前 · 12:00:28               │
├──────────────────────────────────┤
│      [ 切换勿扰 ]                  │
│      [ 诊断勿扰写入 ]              │
│      [ 刷新状态 ]                  │
├──────────────────────────────────┤
│ 勿扰权限：已授权 · sdk=37 ...      │
│ 累计：生理 3 · 睡眠模式 2          │
├──────────────────────────────────┤
│ 操作日志（最近 60 条）             │
│ 12-29 12:01:35 F AUTO.dnd => ...  │
│ 12-29 12:01:35 F DND.set => ...   │
└──────────────────────────────────┘
```

睡眠模式超过 5 分钟没更新时，副标题变橙色并显示「上次记录」，明确区分实时值与历史值。

---

## 一、抓包

### 唯一的 hook 点

```java
com.heytap.health.devicemanager.btclient.BTClient
    .onMessageReceived(String mac, MessageEvent event)
```

这是 App 内**所有**手表消息的唯一入口，内部再分发到两条路径：

```java
onMessageReceived(mac, event)
  ├─ dispatchMsgResponse(mac, event)   // call 请求的回包
  └─ dispatchToListener(mac, event)    // 主动推送
```

hook 这一处 = 100% 覆盖，不需要 hook 几十个 `MsgProcessor`。

### 输出

```
adb pull /sdcard/Android/data/com.heytap.health/files/watchtap.log
```

格式：

```
sid=1 cid=0x23 len=16 mac=4C:92:D2:C3:F0:78 hex=08021801200128013206e87eef0576f4
SEND sid=5 cid=0xcc len=0 hex=
GATE linkagePhoneZenMode called: enable=true time=1793207121
```

- 不带 `SEND` 前缀 = **手表 → 手机**
- `SEND` 前缀 = **手机 → 手表**

也可以实时看：

```bash
adb logcat -s WatchTap
```

> 部分 HyperOS 设备限制 logcat 读取，此时以文件为准。

---

## 二、睡眠联动与勿扰控制

### 设计决策：模块自己控制勿扰

**没有**沿用 hook OPPO 健康 ColorOS 私有写入路径的做法。原因：

```
App 的 changePhoneDND()
  └─ if (isFromColorOS15())
       ├─ true  → OPlusNotificationUtils.setZenMode()
       │           └─ new android.app.OplusNotificationManager()  ← 类不存在
       └─ false → NotificationManagerNative.setZenMode()
                   └─ Epona 反射系统私有接口  ← HyperOS 上失败
```

实测在 HyperOS 上，`isFromColorOS15()` 返回 false，走后者，异常被 App 自己
catch 掉，日志只有 `Change phone zenMode error=`，`zen_mode` 始终不变。

**所以改为模块直接调标准 Android API**：

```java
NotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALARMS)
```

### 为什么用 ALARMS 而不是 NONE

| 过滤器 | 值 | zen_mode | 效果 |
|---|---|---|---|
| `INTERRUPTION_FILTER_ALL` | 1 | 0 | 关闭 |
| `INTERRUPTION_FILTER_PRIORITY` | 2 | 1 | 仅优先联系人 |
| `INTERRUPTION_FILTER_NONE` | 4 | 2 | **完全静音，闹钟也不响** |
| `INTERRUPTION_FILTER_ALARMS` | 5 | 3 | **仅闹钟，其余静音** ← 用这个 |

### ⚠️ targetSdk 必须是 34

**这是勿扰切不动的根因。**

Android 15（API 35）起，targetSdk ≥ 35 的应用调用 `setInterruptionFilter` 时，
系统会自动转换成 `AutomaticZenRule`——写的是「应用自己的勿扰规则」，不是全局勿扰。

表现很有迷惑性：

- 权限已授予 ✓
- 调用不抛异常 ✓
- 但 `Settings.Global.zen_mode` 始终不变 ✗

所以 `build.gradle` 和 `build_apk.sh` 里都固定 `targetSdk = 34`。

### 三条写入路径 + 结果复核

即便 targetSdk 正确，不同 ROM 对第三方写全局勿扰的限制也不同。
所以按优先级依次尝试，每步都用 `zen_mode` 复核：

```
[path1] NotificationManager.setZenMode(int, Uri, String)          反射
[path2] INotificationManager.setZenMode(String, int, Uri, String) 更底层
[path3] setInterruptionFilter                                     兜底
```

判断成功**以 zen_mode 实际变化为准**，不看方法返回值。

如果三条都失败，点 UI 上的「诊断勿扰写入」，会逐条测试并弹窗显示每步结果。

### 跨进程架构

```
┌─────────────────────────────┐
│ com.heytap.health（hook 进程）│
│  ├ 采集心率/睡眠/睡眠模式      │
│  └ State.push() ─────────┐   │
└──────────────────────────┼───┘
                           │ ContentResolver.call()
                           ▼
┌─────────────────────────────┐
│ dev.watchtap（本模块进程）    │
│  ├ StateStore  存状态        │
│  ├ Dnd         写勿扰 ←有权限 │
│  └ MainActivity 读状态显示   │
└─────────────────────────────┘
```

**关键点：勿扰写入必须在模块自己的进程执行。**

hook 进程的身份是 `com.heytap.health`，它没有 `ACCESS_NOTIFICATION_POLICY`
权限；而模块进程有。所以 `State.autoDnd()` 不直接写，而是通过
`StateStore.METHOD_TOGGLE_DND` 请求 provider 代写。

### 权限

必须授予勿扰权限，否则 `setInterruptionFilter` 抛 `SecurityException`：

```bash
adb shell cmd notification allow_dnd dev.watchtap
```

或：设置 → 通知 → 勿扰权限访问 → 勾选 WatchTap

UI 首屏会显示红色提示条，点它直接跳到授权页。

### 完整链路

```
手表进入睡眠模式
  ↓
SleepModeManager.onReceiveDeviceSleepModeData  startNow=true
  ↓
State.setSleepMode(1, ts, fromPush=true)
  ↓
State.autoDnd(true) ─── ContentResolver.call ──→ StateStore
                                                    ↓
                                              Dnd.set(ctx, true)
                                                    ↓
                                     setInterruptionFilter(FILTER_ALARMS)
                                                    ↓
                                              zen_mode = 3
```

不依赖 OPPO 健康的任何 ColorOS 代码路径。

## 三、逆向过程（重要结论）

以下都是实测得出，不是推测。

### 手表不发睡眠模式切换事件

清空日志 → 手表切换睡眠模式 → 35 秒：

```
收到: 4 条（全是 sid=2 设备心跳）
发送: 0 条
```

**手表和手机在睡眠模式切换时完全没有通信。**

App 只在两个时机主动读手表状态：
- 设备连接建立
- 睡眠设置项变更

没有周期性轮询。

### 手表不关心手机是不是 ColorOS

抓取握手包（`sid=1 cid=0x7`，325 字节）后发现，内容包括：

```
f1  = "bt_1.0"                    协议版本
f3  = "device_version_1"
f5  = 手表 MAC
f7  = "OWW251_11_A.265_d784fa8b"  手表固件
f10 = "OWW251"                    手表型号
f14 = "OPPO"                      手表品牌
```

**全是手表描述自己，手机没有任何回传品牌信息的包。**
全表搜索 `xiaomi` / `coloros` / `oppo` / `oplus` / `rom` / `brand`：零命中。

手机发出的 182 字节能力清单（`sid=1 cid=0x64`）是**功能权限列表**，与手机系统无关：

```protobuf
Permission { repeated FeatureItem features = 1; }
FeatureItem { FeaturesType featureType = 1; repeated PermissionItem permissions = 2; }
PermissionItem { int permission = 1; int status = 2; }
```

18 个功能的枚举对照（`PermissionProto$Permission$FeaturesType`）：

| 值 | 枚举 | 含义 |
|---|---|---|
| 0x0 | `PIC_SHARE` | 图片分享 |
| 0x1 | `STEP_RANKING` | 步数排行 |
| 0x2 | `SLEEP_SERVICE` | 睡眠服务 |
| 0x3 | `FAMILY_HEALTH` | 家庭健康 |
| 0x4 | `RUN` | 跑步 |
| 0x5 | `WATCH_FACE` | 表盘 |
| 0x6 | `APP_CACHE` | 应用缓存 |
| 0x7 | `DEVICE_PAIR` | 设备配对 |
| 0x8 | `DEVICE_CALL` | 设备通话 |
| 0x9 | `E_SIM` | eSIM |
| 0xa | `DEVICE_SCHEDULE` | 设备日程 |
| 0xb | `WATCH_SOS_CALL` | 手表 SOS |
| 0xc | `DEVICE_A_GPS` | AGPS |
| 0xd | `MUSIC_MANAGEMENT` | 音乐管理 |
| 0xe | `QUERY_DEVICE` | 查询设备 |
| 0x11 | `WALLET_DEVICE_WALLET` | 设备钱包 |
| 0x12 | `COMMUNITY` | 社区 |
| 0x13 | `HEALTH_ARCHIVES` | 健康档案 |
| 0x14 | `HEALTH_AI_AGENT` | 健康助手 |
| 0x15 | `DOCTOR` | 问诊 |
| 0x16 | `OAF_WIFI_P2P` | WiFi P2P |

这份清单表达的是「这台手机上的 OPPO 健康允许手表使用哪些**云端服务**」，与 ROM 无关。

### 改写能力位无效

曾尝试把 `SLEEP_SERVICE` 的 `status` 从默认 0 补成 1：

```
改前: 0a06 0802 1202 0805          → type=2, perms=[{permission=5}]
改后: 0a09 0802 1205 0805 1001     → type=2, perms=[{permission=5, status=1}]
```

改写成功（包长 182 → 185 字节），但**手表行为没有任何变化**：
- `cid=0x20`（睡眠状态变更通知）依然 0 条
- 对 `cid=0xcc` 读请求依然只回时间戳

### 手表对读请求的响应

```
发送 cid=0xcc 请求 4 次，只回 2 次
回包: sid=5 cid=0xcc len=6 hex=089cc888d706
                            └ 只有 field1 = 时间戳，没有 field3(startNow)
```

完整状态（带 `field3`）只在**连接建立后的首次**返回。

---

## 四、关键数据结构

### SleepModelSetting（`FitnessProto$SleepModelSetting`）

| 字段 | 编号 | 说明 |
|---|---|---|
| `time` | 1 | 秒级时间戳 |
| `accordRestSwitch` | 2 | 作息联动 |
| **`startNow`** | **3** | **睡眠模式当前是否开启** |
| `stateSync` | 4 | 联动免打扰开关 |
| `stateSyncTime` | 5 | 开关更新时间 |

**判据**：payload 里 `field3` 存在且为 1 → 睡眠模式开启；字段缺失 → 关闭。

```
08c8ad88d706 1801   → startNow=1  开启
08fdae88d706        → 无 field3   关闭
```

### 相关 cid

| sid | cid | 说明 |
|---|---|---|
| 5 | `0x49` / `0xcc` | 读睡眠设置（`0xcc` 为 MCU 变体） |
| 5 | `0x4a` / `0xcd` | 写睡眠设置 |
| 5 | `0x4f` / `0xd1` | 设备上报睡眠设置 |
| 5 | `0x20` | `SleepStateChangeNotify` 睡眠状态变更（实测从未出现） |
| 1 | `0x64` | 功能权限清单 |
| 1 | `0x6e` | 手机是否支持 DND |
| 1 | `0xa8` | 通知设备手机 DND 变化 |

---

## 五、构建

### 方式 A：Gradle

```bash
export ANDROID_HOME=/path/to/android-sdk   # 需 compileSdk 35
./gradlew :app:assembleRelease
```

### 方式 B：命令行（无需 Gradle）

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_JAR=/path/to/android-35/android.jar
export API_JAR=/path/to/libxposed-api-102/classes.jar
export BUILD_TOOLS=/path/to/build-tools/35.0.0

./build_apk.sh release
```

实测环境：
- JDK 17.0.21
- `android-35/android.jar`（Android 15 platform）
- build-tools 35.0.1
- `io.github.libxposed:api:102.0.0`（注意发布的是 `.aar`，需解出 `classes.jar`）

产物：约 21KB 的已签名 APK。

---

## 六、安装

```bash
adb install -r watchtap-release.apk
```

### 启用（两种方式）

**GUI**：LSPosed 管理器 → 模块 → 启用 WatchTap → 勾选作用域 `com.heytap.health` → 重启手机

**命令行**：

```bash
adb shell su -c "lspctl module enable --user=0 dev.watchtap"
adb shell su -c "lspctl scope add --user=0 dev.watchtap com.heytap.health"
```

> `lspctl` 是 LSPosed 自带的调试 CLI，位于
> `/data/adb/modules/zygisk_lsposed/lspctl`。它还提供
> `hook-debug enable` / `hook-debug dump`，可以查看每个 hook 的实际状态
> （`hooked=xxx eq=true` 表示生效），排查"模块没生效"非常有用。

### 开启联动

装好重启后：

1. 打开 OPPO 健康
2. 进入 **设备 → 睡眠设置**
3. 打开 **「睡眠模式联动手机免打扰」**（这个开关是 hook 1 让它出现的）
4. 手表进入睡眠模式，手机应自动开启免打扰

验证：

```bash
adb shell settings get global zen_mode   # 0=关, 2=免打扰
```

---

## 七、排查

### 确认 hook 装上

LSPosed 自带调试 CLI：

```bash
adb shell su -c "lspctl hook-debug enable"
# 重启目标应用后
adb shell su -c "lspctl hook-debug dump"
```

输出里每个 hook 会显示：

```
void com.heytap.health.devicemanager.btclient.BTClient.onMessageReceived(...)
  hooked=0xd3e65ef0 backup=0x700f4ef020
  expectedEntryPoint=0xd08850f0 realEntryPoint=0xd08850f0 eq=true
```

`eq=true` 才算真正生效。

### 看实时日志

```bash
adb logcat -s WatchTap
```

关注这些前缀：

| 前缀 | 含义 |
|---|---|
| `hooked ...` | hook 安装成功 |
| `SLEEPMODE` | 睡眠模式变化 |
| `SLEEPSTATE` | 生理睡眠变化 |
| `AUTO: dnd` | 自动联动执行结果 |
| `STATE:` | 状态过滤（忽略过期值） |

### 状态不更新

1. 检查 UI 上「模块最后更新」时间——如果很久没变，说明 hook 进程没数据
2. `lspctl hook-debug dump` 确认 hook 状态
3. 确认作用域包含 `com.heytap.health`

## 八、已知限制

1. **手表不发状态变更事件**

   实测：清空日志 → 手表切换睡眠模式 → 35 秒内收到 4 条消息（全是 `sid=2` 设备心跳），发送 0 条。

   手表固件不会主动通知手机。手机只在两个时机能读到状态：
   - 与手表建立连接后
   - App 内部设置项变化时

   因此从手表切换睡眠模式时，模块可能收不到事件，联动不会立即触发。
   **这是手表行为，模块无法改变。**

2. **勿扰权限需手动授予**

   `ACCESS_NOTIFICATION_POLICY` 是特殊权限，无法通过安装自动获得。
   未授权时 UI 顶部会显示红色提示条。

3. **UI 数据依赖 hook 进程存活**

   OPPO 健康被系统杀掉后，状态停止更新。UI 上「模块最后更新」会显示时间差。

4. **仅测试于 OPPO Watch X2 + HyperOS 4.0（Android 17）+ LSPosed 2.2.1**。

5. **心率解析基于实测包结构**，不同固件版本可能不同。若心率一直显示 `--`，
   用抓包功能看 `sid` 与 `len`，再对照 `Main.parseHeartRate()` 调整。

---

## 九、项目结构

```
watchtap/
├── app/src/main/
│   ├── AndroidManifest.xml          Activity + Provider + 勿扰权限
│   ├── java/dev/watchtap/
│   │   ├── Main.java                Xposed 入口：4 个采集 hook
│   │   ├── State.java               hook 侧状态缓存 + 上报 + 自动联动
│   │   ├── StateStore.java          ContentProvider，跨进程通道
│   │   ├── Dnd.java                 勿扰读写（模块自己控制）
│   │   └── MainActivity.java        UI：4 卡片 + 每秒刷新
│   ├── res/values/strings.xml
│   └── resources/META-INF/xposed/
│       ├── java_init.list           入口类声明
│       ├── module.prop              minApiVersion=102
│       └── scope.list               com.heytap.health
├── build_apk.sh                     命令行构建脚本
├── build.gradle / settings.gradle
└── README.md
```

## 十、免责声明

本项目仅供**个人设备上的学习研究**。

- 修改 system_server / hook 系统应用可能影响设备稳定性
- 修改 `Build` 相关值可能影响其他应用，请仅在必要时使用
- 请自行承担使用风险

---

## License

MIT

---

## 适配记录：OPPO 健康 6.10.15（v2.20.1）

本构建基于 **v2.20.0** 源码（main HEAD `eacbbd0`），只改两个文件，
其余功能（勿扰写入、开关修复通道、更新检查、通知、日志/历史等）原样保留。
**已在 HyperOS 真机验证通过**，适配目标为 OPPO 健康 **6.10.15**（构建号 `9e2a81e`）。

### 改动一：Main.java —— 三个采集点

| 采集点 | v2.20.0 原目标 | 6.10.15 实际位置 |
|---|---|---|
| 手表睡眠模式 | `device.sleep.SleepModeManager#onReceiveDeviceSleepModeData` | **`device.sleep.n#c(SleepModelSettings)`**（类 `n` 的 TAG 字段仍是 "SleepModeManager"） |
| 生理睡眠 | `...sporthealth.repository.SleepStateRepository$Companion#reportSleepState` | 该包整体删除 → 解析报文 **cid=0x20**（`FitnessProto$SleepStateChangeNotify`：f1=startTime、f3=state） |
| 全量报文 | `...btclient.BTClient#onMessageReceived` | **`xs.e#onMessageReceived`**（线程池 tag 仍是 "BTClient"，`(sid,cid)=(-1,-1)` 通配注册）+ 兜底 `LM3Message$mMessageCallback$1#onMessageEvent` |

### 改动二：SwitchFixer.java —— 开关可见性

| 项 | v2.20.0 | 6.10.15 |
|---|---|---|
| ROM 版本 | `SystemUtils#getRomVersionCode()` | `com.heytap.health.base.app.v#g()I` |
| 可见性判据 | （无） | `SleepDataServiceImpl#D0()Z` = `k0.b() && SDK>=30 && v.g()>0` |

`D0()` 全 APK 只有 1 个 UI 调用点，比 `v.g()`（12 处调用）精准，因此优先 hook `D0()`，
同时保留 `v.g()` 兼容。两者都受运行时开关 `State.fixSwitchEnabled()` 控制。

### 兼容性

采集点改为「候选类名 + 参数类型特征匹配 + 报文实例去重」，
旧版本 OPPO 健康仍然可用（自动回退旧类名）。

### 构建与签名

```bash
export JAVA_HOME=<JDK21>
export ANDROID_JAR=<android-35>/android.jar
export API_JAR=<libxposed api 102>/classes.jar
export BUILD_TOOLS=<build-tools>
./build_apk.sh release     # → build/watchtap-release.apk
```

签名沿用仓库统一的 `watchtap.jks`（证书 SHA-256 `e9f5acff766040b1…`），与 v2.20.0
及历史版本一致，**可直接覆盖安装**，无需卸载。

---

## 手机闹钟联动（v2.20.3 新增）

### 背景：原版只认 ColorOS 闹钟

```
ColorOS 闹钟 App
  └─ 广播 com.coloros.platformalarmclock.platform.awaken_clock_action
       └─ PlatformClockBroadcastReceiver
            └─ j5.c（PlatformClockManager）bindService → com.coloros.alarmclock
                 └─ AIDL IClockUpdateAidlInterface#alarmClockRing(PlatformClockInfo)
                      └─ com.heytap.wearable.watch.clock.manager.d#alarmClockRing  ← 下发手表
```
HyperOS 上没有 `com.coloros.alarmclock`，第一步就断，整条链路失效。

### 本模块的做法（AlarmLink）

1. `AlarmManager.getNextAlarmClock()` 读出手机真实闹钟（宿主 App 有 SCHEDULE_EXACT_ALARM）
2. 用 `setAlarmClock()` 在同一时刻排一个「代理闹钟」（精确、无窗口）
3. 代理闹钟到点 → 构造 `PlatformClockInfo`（时间 / 标签 / `mIsGarbAlarm=false` / `snoozeTime=5`）
   → 反射调用 App 自己的 `d#alarmClockRing()`，由 App 用它自己的加密通道发给手表
4. 手表回传动作（实测报文）：
   - `sid=10 cid=24` = 关闭 → 取消代理闹钟 + 发通知提醒手动关手机闹钟
   - `sid=10 cid=25` = 稍后提醒（固定 5 分钟）→ 重排代理闹钟

> 为什么必须注入而不是用接口：ColorOS 的 AIDL 服务在 HyperOS 不存在；广播被签名级权限
> `com.coloros.alarmclock.permission.ACCESS_CLOCK_RECEIVER_PLATFORM` 挡住；
> App 的闹钟组件未导出；OPPO linkservice SDK 没有闹钟 API，且 `MessageEvent`
> 的 `mEncryptOption` 说明报文是加密的，第三方无法自行构造。

### 调试

```bash
# 手动触发一次「手机闹钟」（不响铃）
adb shell am broadcast -a dev.watchtap.DEBUG_ALARM --el t $(date +%s%3N) -p com.heytap.health
# 诊断日志（宿主 App 自己目录，不需要 root）
adb shell su -c 'cat /sdcard/Android/data/com.heytap.health/files/alarmlink.log'
```

开关：`Prefs.K_ALARM_LINK`（`alarmLink`，默认开启）。

### 稍后提醒（v2.20.5）

手表点「稍后提醒」时，手机闹钟会**真正推后 5 分钟**：

1. 若响铃界面存在 → 直接调它的 `onSnooze()`（与用户手点一致）
2. 界面不存在（解锁状态只出通知）→ 用时钟自己的 `HandleSetAlarmActivity` + `SKIP_UI`
   排一个 5 分钟后的闹钟，然后关掉当前响铃

实测：08:10 响铃 → 手表稍后 → `scheduled +5min alarm at 8:15` → `stopAlarmKlaxon ok`
→ AlarmManager 出现 `triggerTime=...08:15:00`，到点确实再次响铃。

### 已知限制

- 关闭/稍后由**系统时钟进程**内完成（模块注入 `com.android.deskclock`）：
  取 `AlarmService.onStartCommand` 里 `intent.extra.alarm_raw` 解析出的 Alarm，
  调 `AlarmHelper.stopAlarmKlaxon()` + `clearAlarmAlertNotification()` +
  `tryDeleteOneshotAlarm()` + `stopService()`，实测闹钟服务计数 3 → 0，铃声停止
- 为什么不用小米运动健康：其 console 通道虽能关闹钟，但装上后会接管响铃（手机不再本机响），
  且时钟里 `HEALTH_PKG_NAME` 写死 `com.mi.health`，OPPO 健康无法顶替
- 新增作用域 `com.android.deskclock`，**升级后需重启一次设备**才会生效
