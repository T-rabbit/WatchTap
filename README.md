# WatchTap

一个 LSPosed 模块：拦截 **OPPO 健康（com.heytap.health）** 与 **OPPO Watch** 之间的全部蓝牙消息，并在 HyperOS 上打通「手表睡眠模式 → 手机免打扰」联动。

基于 **libxposed API 102**，适配 Android 17 / HyperOS 4.0。

---

## 功能

| 功能 | 说明 |
|---|---|
| **消息抓包** | 双向记录所有手表消息（sid / cid / 长度 / payload hex） |
| **睡眠联动** | 手表进入睡眠模式 → 手机自动开启免打扰 |
| **信封改写** | 可修改发送给手表的能力清单（含一个已实现的示例） |

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

## 二、睡眠联动

### 为什么需要它

ColorOS 上，OPPO 健康能把手表的睡眠模式映射到手机免打扰。这套逻辑在 HyperOS 上完全失效，原因有三层：

| 依赖 | ColorOS | HyperOS |
|---|---|---|
| `SystemUtils.getRomVersionCode()` | 反射 `OplusBuild` 拿到版本号 | 两个私有类都不存在 → 返回 0 |
| `Settings.Global."zen_mode_ext_info"` | 非空 | `null` |
| `OplusNotificationManager` | 存在 | 类不存在 |

这三个缺失导致：

```
getRomVersionCode() = 0
  → isPhoneSupportZenModeLinkage() = false
  → 设置里不显示「睡眠模式联动免打扰」开关
  → 用户无法开启，联动永久不可用
```

即使强行打开开关，还有第二道闸门：

```
isPhoneSupportDND() = (zen_mode_ext_info != null) = false
  → SleepModeManager 跳过 linkagePhoneZenMode()
```

### 三个 hook，缺一不可

#### 1. 让联动开关出现

```java
SystemUtils.getRomVersionCode() → 15
```

`isPhoneSupportZenModeLinkage()` 的条件是 `SDK_INT >= 30 && getRomVersionCode() > 0`。
伪造非 0 后，设置页就会显示那个开关。

> ⚠️ **但用户仍需手动打开它**。因为总开关是 `SleepModelSettings.mStateSync`，
> 存在 `UserPreference{SLEEP_MODEL_SETTINGS}` 里，默认值 0。

#### 2. 让 DND 能力检测通过

```java
Settings.Global.getString(cr, "zen_mode_ext_info")  // 为 null 时返回合成值
→ "时间戳ms,allowCalls,allowMsgs,allowEvents,status"
```

`isPhoneSupportDND()` 只判断这个字段是否非空。伪造后返回 true，联动才会继续往下走。

`status` 取真实的 `zen_mode` 值，保证读写一致。

#### 3. 拦截 ColorOS 私有写入

一旦前两个生效，App 会走 ColorOS 分支，然后：

```java
// OPlusNotificationUtils.setZenMode
new android.app.OplusNotificationManager()   // ← 类不存在，崩溃
```

所以必须提前拦截这两个入口，改走标准 Android API：

```java
// OPlusNotificationUtils.setZenMode(int, String)
// NotificationManagerNative.setZenMode(Context, int, Uri, String)
→ NotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_NONE / ALL)
```

### 完整链路

```
手表进入睡眠模式
  ↓
onReceiveDeviceSleepModeData  startNow=true, isSyncSleepMode=true ✓
  ↓
isPhoneSupportDND = true                              ← hook 2
  ↓
linkagePhoneZenMode(enable=true)
  ↓
changePhoneDND(true)
  ↓
NotificationManager.setInterruptionFilter(NONE)       ← hook 3
  ↓
zen_mode: 0 → 2                                       ✓ 免打扰已开启
```

### 实测验证

```
基线 zen_mode = 0
手表进入睡眠模式后 zen_mode = 2
```

日志证据：

```
CHAIN onReceiveDeviceSleepModeData startNow=true isSyncSleepMode=true ts=1793207121
GATE isPhoneSupportDND = true
GATE linkagePhoneZenMode called: enable=true time=1793207121
```

---

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

## 七、分步启用（排查用）

`Main.java` 里有 `STEP` 常量：

```java
private static final int STEP = 2;
```

| STEP | 启用的 hook |
|---|---|
| 0 | 只抓包，不做任何伪装 |
| 1 | + `getRomVersionCode` → 15（让开关出现） |
| 2 | + `zen_mode_ext_info` 伪造 + DND 写入拦截（完整联动） |

排查时建议从 0 开始，每次 +1，并**连续启动 3 次**确认不崩溃。

> 经验：单次启动成功不代表稳定。早期版本曾因
> `OPlusDeviceUtils.isFromColorOS14()` 里的 `sget` 无 try/catch 而导致
> 启动即崩，单次测试没能发现。

---

## 八、已知限制

1. **手表不发状态变更事件** —— 从手表侧切换睡眠模式时，手机收不到通知。联动依赖 App 主动读取（连接建立 / 设置变更时触发）。

2. **`android.app.OplusNotificationManager` 无法伪造** —— 它位于 `android.app` 包，被 boot classloader 优先解析，往应用 ClassLoader 注入同名类无效。只能靠 hook 上层方法绕开。

3. **状态可能滞后** —— App 重启后读到的是数据库中的历史值，直到下次与手表通信才会刷新。

4. **仅测试于 OPPO Watch X2 + HyperOS 4.0（Android 17）+ LSPosed 2.2.1**。

---

## 九、项目结构

```
watchtap/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/dev/watchtap/
│   │   ├── Main.java            入口 + 消息抓包
│   │   ├── LinkageHook.java     联动启用（版本号 / zen_mode_ext_info / 诊断）
│   │   └── DndInterceptor.java  DND 写入拦截
│   ├── res/values/strings.xml
│   └── resources/META-INF/xposed/
│       ├── java_init.list       入口类声明
│       ├── module.prop          minApiVersion=102
│       └── scope.list           com.heytap.health
├── build_apk.sh                 命令行构建脚本
├── build.gradle / settings.gradle
└── README.md
```

---

## 十、免责声明

本项目仅供**个人设备上的学习研究**。

- 修改 system_server / hook 系统应用可能影响设备稳定性
- 修改 `Build` 相关值可能影响其他应用，请仅在必要时使用
- 请自行承担使用风险

---

## License

MIT
