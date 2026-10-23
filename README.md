# 跳绳计数（JumpRopeCounter）

一个原生 Android 跳绳计数 App（Kotlin + Material 3，minSdk 26 / targetSdk 34）。

## 📲 下载安装

**[点击下载最新版 APK](https://github.com/doublechenchen/JumpRopeCounter/releases/latest/download/JumpRopeCounter.apk)**

或前往 [Releases 页面](https://github.com/doublechenchen/JumpRopeCounter/releases) 查看所有版本。

安装方法：在安卓手机上下载后用文件管理器打开安装；提示「未知来源」属正常现象，选择「仍然安装」即可。覆盖旧版安装即升级，成绩记录保留。

## 功能

### 两种模式
| 模式 | 说明 | 播报逻辑 |
| --- | --- | --- |
| 计时模式 | 固定时长（分/秒可设），统计跳绳个数 | 每跳 10 个报数一次；最后 10 秒报“还剩十秒”；到时播报总个数 |
| 计数模式 | 固定目标个数，统计用时 | 每跳 10 个报数一次；还剩 10 个时播报已用时间；达标播报总用时 |

- 全程每跳一下都会立即播放一次短促提示音（ToneGenerator，低延迟，不走 TTS）
- 支持 **暂停 / 继续 / 提前结束 / 再来一次**
- 计数期间屏幕常亮

### 语音准备开赛
点「开始」后 App 提示「请说我准备好了」，运动员大声回应（如“我准备好了”）即自动 3→2→1 倒数开赛；
优先使用系统语音识别匹配关键词，无识别引擎的设备自动降级为麦克风人声能量检测（说完停顿半秒触发）。
也可点「直接开始」跳过语音环节。

### 计数方式
**摄像头自动计数**：CameraX 前置摄像头 + ML Kit 姿态识别（离线模型），计数时预览全屏显示。
手机架好对准全身，App 追踪双肩/双髋关键点，四点融合特征上移幅度超过躯干长度的一定比例记为一次跳跃，
阈值随人体在画面中的大小自适应，无需手动校准

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
├── MainActivity.kt    # 首页：运动员名字、模式入口、记录入口
├── CountActivity.kt   # 计数页：语音准备、3-2-1 倒计时、姿态计数、语音播报、成绩保存
├── RecordsActivity.kt # 成绩记录页：各档最佳 + 全部记录
└── ScoreStore.kt      # 成绩存储（SharedPreferences + JSON，带防损坏保护）
```

## 灵敏度调优

如果自动计数不准，调整 `CountActivity.kt` 顶部两个常量：

- `AMP_MIN_RATIO = 0.05f` — 最小有效振幅（相对躯干长度），漏记就调小，误记就调大
- `JUMP_REFRACTORY_MS = 300L` — 两次跳跃最小间隔，跳得快（>200 个/分钟）可调小到 250
