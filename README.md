# 跳绳计数（JumpRopeCounter）

一个原生 Android 跳绳计数 App（Kotlin + Material 3，minSdk 26 / targetSdk 34）。

## 功能

### 两种模式
| 模式 | 说明 | 播报逻辑 |
| --- | --- | --- |
| 计时模式 | 固定时长（分/秒可设），统计跳绳个数 | 每跳 10 个报数一次；最后 10 秒报“还剩十秒”；到时播报总个数 |
| 计数模式 | 固定目标个数，统计用时 | 每跳 10 个报数一次；还剩 10 个时播报已用时间；达标播报总用时 |

- 全程每跳一下都会立即播放一次短促提示音（ToneGenerator，低延迟，不走 TTS）
- 支持 **暂停 / 继续 / 提前结束 / 再来一次**
- 计数期间屏幕常亮

### 计数方式
1. **摄像头自动计数**（默认开启）：CameraX 前置摄像头 + ML Kit 姿态识别（离线模型）。
   手机架好对准全身，App 追踪双肩/双髋关键点，肩部上移幅度超过躯干长度的一定比例记为一次跳跃，
   阈值随人体在画面中的大小自适应，无需手动校准
2. **手动 +1**：关闭自动计数开关后，点“跳一下 +1”大按钮计数

## 构建运行

用 **Android Studio**（Hedgehog 2023.1.1 或更新版本）打开本目录：

1. `File → Open` 选择 `JumpRopeCounter` 文件夹
2. 等待 Gradle Sync 完成（首次会下载 Gradle 8.4 与依赖）
3. 连接安卓手机（开启 USB 调试）或创建模拟器，点击 **Run ▶**

也可以命令行构建（需要本机装好 Android SDK 与 JDK 17）：

```bash
gradlew assembleDebug
# 产物在 app/build/outputs/apk/debug/app-debug.apk
```

> 仓库未包含 `gradle-wrapper.jar`，Android Studio 首次同步时会自动生成；
> 命令行使用请先执行一次 `gradle wrapper` 或直接用 Android Studio。

## 代码结构

```
app/src/main/java/com/cc/jumprope/
├── MainActivity.kt    # 首页：模式选择
└── CountActivity.kt   # 计数页：计时/计数逻辑、语音播报、传感器跳跃检测
```

## 灵敏度调优

如果自动计数不准，调整 `CountActivity.kt` 顶部两个常量：

- `AMP_MIN_RATIO = 0.08f` — 最小有效振幅（相对躯干长度），漏记就调小（如 0.05），误记就调大（如 0.12）
- `JUMP_REFRACTORY_MS = 300L` — 两次跳跃最小间隔，跳得快（>200 个/分钟）可调小到 250
