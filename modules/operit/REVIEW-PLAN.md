# Operit → AutoJs6 移植：全面复查方案（待评估）

> 状态：**仅方案，未改任何代码**。等小雨确认后再执行。
> 生成时间：2026-10-08
> 账本：`modules/operit/SYNC.md`（移植记录）｜本文件（复查计划）

---

## 0.0 当前代码基线（本方案的事实依据，复查时以此为准）

> 本轮所有结论均基于**当前工作区代码**复核（非旧记忆），并附真机日志证据。

| 项 | 现状 |
|---|---|
| 模块清单（13） | `operit`、`operit-quickjs`、**`operit-terminal`**（真实终端栈，非 stub）、`bibi`、`apk-parser`、`apk-signer`、`color-picker`、`expandable-*`、`jiebao-analysis`、`material-*`、`recyclerview-flexibledivider` |
| `operit-terminal` 特性 | namespace `com.ai.assistance.operit.terminal`；**`abiFilters = arm64-v8a` only**（proot/bash/busybox prebuilt）；`jniLibs` + rootfs 资产（约 62MB） |
| 宿主 flavor | `app`（applicationId `com.xiaoyu.ai`）｜ `inrt`（`com.xiaoyu.ai.inrt`，同机可共存设计） |
| 后端契约（4） | 无障碍 `HostAccessibilityBackend`(10 方法)｜截屏 `HostScreenCaptureBackend`(3)｜语音 `HostSpeechBackend`(8)｜bibi `ScriptHost` |
| 真机 | DNP-AN00 / Android 16 (API 36) / arm64-v8a |
| 已验证 | debug 冷启动、抽屉入口 → Operit MainActivity、Operit 主界面渲染、MCP 12 单测 |
| 未验证 | **release/R8**、无障碍逐工具、截屏桥、语音桥、bibi 全链、AutoJs6 主功能、`inrt` flavor、双 flavor 共存 |

---

## 0. 复查目标与口径

| 维度 | 口径 |
|---|---|
| 正确性 | 库形态下**所有**上游代码路径可运行，无 `ClassCastException` / 反射失效 / 硬编码身份错误 |
| 稳定性 | 宿主冷启动、任意 UI 入口、任意服务启动都不带崩 AutoJs6 |
| 完整性 | 4 个后端契约（无障碍 / 截屏 / 语音 / 脚本）语义对齐，无槽位覆盖、无静默降级掩盖真 bug |
| 可发布 | **release/R8 构建**可用（当前仅验证过 debug） |
| 性能 | 未使用 Operit 的 AutoJs6 用户不应承担 Operit 的全量启动与常驻成本 |
| 卫生 | 权限面 / provider authorities / flavor 兼容 / APK 体积 |

---

## 1. 已确认缺陷（真机实证，按严重度排序）

### 🔴 P0-1　`(application as OperitApplication)` 仍有 2 处未修 → 服务启动即崩

| 位置 | 上下文 | 后果 |
|---|---|---|
| `api/chat/AIForegroundService.kt:925` | `onCreate()` | 打开 Operit 聊天 → 启动 AI 前台服务 → **ClassCastException**，聊天链路断 |
| `services/FloatingChatService.kt:215` | `onCreate()` | 悬浮聊天窗启动即崩 |

- 已修：`ui/main/MainActivity.kt:193`（P4.4 时改掉）。
- 漏修原因：P4.4 只处理了 Activity，未系统性扫描 Service。
- **修复方向**：两处统一改为 `OperitLibrary.ensureMainApplicationInitialized()`。
- 与 P0-2 联动：修好 P0-2 后，这两个调用点才真正有效。

### 🔴 P0-2　`OperitLibrary` 反射取 `instance` 失效 → 重复 bootstrap + 幂等失效

```kotlin
// OperitLibrary.kt:174
private fun isOperitApplicationReady(): Boolean = runCatching {
    Class.forName(OPERIT_APPLICATION_CLASS).getField("instance")  // ← 恒抛 NoSuchFieldException
    ...
}.getOrDefault(false)   // ← 恒 false
```

真机日志实证：

```
E/OperitLibrary: ensureMainApplicationInitialized failed (UI continues)
java.lang.NoSuchFieldException: instance
    at OperitLibrary.ensureMainApplicationInitialized(OperitLibrary.kt:106)
    at com.ai.assistance.operit.ui.main.MainActivity.onCreate(MainActivity.kt:193)
```

根因：`OperitApplication.instance` 是 **Kotlin companion 的 `lateinit var`（`private set`）**。Kotlin **不生成 public 静态字段**，只生成：
- `OperitApplication$Companion` 内的实例字段
- 外层类的静态桥接方法 `getInstance()`（`setInstance` 因 `private set` 为 private）

所以 `getField("instance")` 永远失败。连锁后果：

1. `isOperitApplicationReady()` 恒 `false`
2. → `bootstrapOperitApplication` 的幂等守卫失效（第 150 行 `if (isOperitApplicationReady()) return` 永不触发）
3. → **每次调用都 new 一个 OperitApplication 并重跑 `onCreate` + `initializeMainApplication`**
   （`mainApplicationInitialized` 是**实例字段**，新实例 = false → 整套重跑）
4. → `ensureMainApplicationInitialized` 第 106 行抛异常 → 被 catch → "UI continues" **掩盖**了问题
5. → 重复副作用：调度器重复创建、Room/ObjectBox 重复打开、ToolPkg 重复扫描、`GlobalExceptionHandler` 重复安装

- **修复方向**：改用 `getMethod("getInstance").invoke(null)`（或反射 Companion 实例字段）。
- **同时**：`catch (e: Throwable) { "UI continues" }` 属**过度宽容**——把真 bug 降级为静默日志。建议区分「可降级」与「不可降级」，后者 `AppLogger.e` 后仍应显式上报。

### 🔴 P0-3　（已修，需回归）WorkManager 未初始化 → 宿主冷启动必崩　→ D-5

模块 manifest 依上游 `tools:node="remove"` 掉 `WorkManagerInitializer`，上游靠自己的 `OperitApplication : Configuration.Provider` 补；库形态下真正的 Application 是宿主 `App` → WorkManager 永不初始化 → `App.onCreate → TimedTaskScheduler.init` 崩。
已改为宿主 `App : MultiDexApplication(), WorkConfiguration.Provider`。**需回归确认**。

### 🔴 P0-4　（已修，需回归）Operit `GlobalExceptionHandler` 劫持进程级异常处理 → 崩溃循环

`OperitApplication.onCreate` 里 `Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(this))` **覆盖了宿主更完善的 `CrashHandler`**。上游 handler 的特征：不写 logcat、对**任意线程**无条件 `exitProcess(1)`、无崩溃循环防护 → 一个后台任务失败 = 宿主进程崩溃循环。
已改为库形态下不安装。**需回归确认**。

### 🟠 P1-1　语音后端 `setListener` 单槽位 vs 双消费者 → 互相覆盖

```kotlin
interface HostSpeechBackend { fun setListener(listener: Listener) }   // 单槽
```
```kotlin
// BibiSpeechBridge.kt
BibiSttBridge.attachListener → backend.setListener(this)   // :178
BibiTtsBridge.attachListener → backend.setListener(this)   // :324  ← 覆盖前者
```
`AutoJsSpeechHostBridge` 侧同样是单一 `private var listener`（:42）。**后注册者赢**，另一方永久收不到回调（识别结果流 或 播报状态流 之一静默失效）。
- **修复方向**：改为双槽（`setSttListener` / `setTtsListener`）或按事件类型分发到监听器集合。

### 🟠 P1-2　模块 manifest 硬编码宿主体标识 → 双 flavor 冲突

| 项 | 现状 | 问题 |
|---|---|---|
| documents authorities | 字面量 `com.xiaoyu.ai.operit.documents.{data,memory,workspace}` | 宿主 `inrt` flavor 的 applicationId 是 `com.xiaoyu.ai.inrt` → 两者安装同机时 **`INSTALL_FAILED_CONFLICTING_PROVIDER`**；单独装 inrt 时 provider 归属错误 |
| `buildConfigField APPLICATION_ID` | 字面量 `"com.xiaoyu.ai"` | inrt flavor 下模块内 `BuildConfig.APPLICATION_ID` 指向错包（`DebuggerFileSystemTools`、`StandardSystemOperationTools` 会去查错包） |

- 对照：`${applicationId}.operit.fileprovider` / `${applicationId}.shizuku` / `${applicationId}.androidx-startup` 用的是占位符（正确）。
- **修复方向**：documents authorities 改 `${applicationId}`；`APPLICATION_ID` 改为随 flavor 注入。

### 🟠 P1-3　`AnrMonitor` 硬编码上游包名

`util/AnrMonitor.kt:349` → `val targetPackage = "com.ai.assistance.operit"`。库形态下自身包名是 `com.xiaoyu.ai`，ANR 监测永远匹配不到自己（功能静默失效）。`MainActivity` 里有 `anrMonitor.start()`。

### 🟠 P1-4　模块零 consumer ProGuard 规则 + release/R8 完全未验证

- `modules/operit` 与 `modules/operit-terminal` 下**都没有** `proguard-rules.pro` / `consumerProguardFiles`。
- 模块 `release { isMinifyEnabled = false }` 对 AAR 无意义——**R8 由宿主 release 决定**（宿主 `isMinifyEnabled` + `app/proguard-rules.pro`）。
- 大量反射/约定依赖 R8 保号，当前**无规则**：
  - `OperitLibrary`（`Class.forName` OperitApplication / getInstance）
  - `MaterialIconNameResolver`（`Class.forName("androidx.compose.material.icons.filled.${name}Kt")` + `getMethod("get${Name}")`）
  - `JsJavaBridgeDelegates`（`Class.forName` 动态解析）
  - `JLatexMathCompatibility`（反射 `TeXParser.pos`）
  - `FloatingWindowManager`（反射 `privateFlags`）
  - ObjectBox / Room / kotlinx.serialization / ToolPkg 插件加载
- **结论**：**release 构建当前大概率不可用**。这是最大的未验证风险面。

### 🟡 P2-1　`RawSnapshotBackupManager` 快照包名前缀

`data/backup/RawSnapshotBackupManager.kt:32` → `SNAPSHOT_PACKAGE_NAME_PREFIX = "com.ai.assistance.operit"`。需确认备份/恢复快照的包名归属是否影响导入导出。

### 🟡 P2-2　Operit 全量启动 + 永久后台成本强加给宿主

真机日志证据（**用户从未打开 Operit** 的情况下）：

```
ToolPkg: PKG: loadAvailablePackages start
ToolPkg: scan candidate finish ... file=12306.js
WorkflowSchedulerInit: Workflow scheduler initialized.
MemoryAutoSaveScheduler: 长期记忆自动保存轮询器已启动
... 35s 后 ...
MemoryAutoSaveScheduler: 候选总条数不足，继续累计并重置下次执行时间
ChatHistoryManager: 数据库预加载完成，现有聊天数：0
```

- `OperitLibrary.init(this)` 无条件下挂在宿主 `App.onCreate`。
- `initializeMainApplicationLocked` 同步段实测仅 62–76ms，但**异步段**（ToolPkg 扫描、Room 预加载、Coil、调度器）与**永久轮询**（记忆自动保存、工作流）会常驻宿主进程。
- **优化方向**：改为「首次打开 Operit UI 时懒初始化」（`ensureMainApplicationInitialized` 已经是天然入口），冷启动只做最小接线。
- 附带：APK 体积（记录为 universal 431MB / arm64 约 290MB，含 terminal rootfs 约 62MB）值得评估可裁剪项。

---

## 2. 尚未验证的高风险面（需系统性审查）

| # | 面 | 风险 | 验证手段 |
|---|---|---|---|
| R1 | **release/R8 构建** | 反射/序列化/ToolPkg 被裁 → 运行期崩溃 | `assembleAppRelease` + 真机冒烟 |
| R2 | 无障碍逐工具 | 6 工具（getPageInfo/click/tap/swipe/setInputText/pressKey）+ 截图 | 真机 + Operit toolbox → `uidebugger`/`tooltester` |
| R3 | SQL 查看器 | Room 真机读写 | 真机 + Operit toolbox → `sqlviewer` |
| R4 | 截屏桥 | 反射 `ScreenCapturer.mMediaProjection` 字段名/可用性 | 真机 + 有活跃脚本会话时 |
| R5 | 语音桥 | STT 往返 + TTS 播报（含 P1-1 修复后） | 真机 + Operit toolbox → `speechtotext`/`texttospeech` |
| R6 | bibi 全链回归 | 悬浮球/识别/播报未被 Operit 影响 | 真机 |
| R7 | AutoJs6 主功能冒烟 | 脚本运行/文件列表/定时任务未被宿主改动影响 | 真机 |
| R8 | 资源合并 | 同名 res / `day_night_full` 等主题色引用 | 构建告警 + 真机各主题 |
| R9 | 权限面膨胀 | 模块带入 SMS/电话/`MANAGE_EXTERNAL_STORAGE`/`QUERY_ALL_PACKAGES` 等 | 对比移植前后宿主合并 manifest |
| R10 | 进程面 | `:background` / `:crash_report` 下 Operit 单例是否真的不初始化 | 真机 + 日志 |
| R11 | 裁剪功能降级 | 终端/虚拟屏/唤醒等 stub 的 UI 是否优雅降级 | 真机遍历相关入口 |
| R12 | 双 flavor | `app` 与 `inrt` 能否同机共存 | 构建 inrt + 安装 |
| R13 | **`operit-terminal` 新模块** | ABI 仅 arm64-v8a（x86/armeabi 设备无终端）；proot/busybox so 与宿主 so 冲突；rootfs 资产打包；R8 对 terminal 反射/JNI 的影响 | 构建各 ABI + 真机 arm64 终端会话 |

---

## 3. 审查方法论（拟分 5 轮）

### 第 1 轮　静态一致性审计（不跑真机，最快见效）
用**规则化检索**扫全模块，找同类缺陷（而非逐个碰运气）：
1. `(application as X)` / `getApplication() as` / `applicationContext as` —— Application 身份假设（已发现 3 处，2 处未修 → 需扫"还有没有"）
2. `Class.forName` / `getField` / `getDeclaredField` / `getMethod` —— 反射有效性（已发现 1 处失效 → 逐个复核）
3. 字面量包名 / `BuildConfig.APPLICATION_ID` / `packageName ==` —— 硬编码身份
4. manifest `authorities` / `permission` / `android:process` —— 占位符 vs 字面量
5. `OPERIT_APPLICATION` / `OperitApplication.instance` 的 23 个使用点 —— lateinit 时序安全

**产出**：规则化缺陷清单（文件:行 + 严重度 + 修复建议）

### 第 2 轮　契约审计（模块侧 ↔ 宿主侧语义对齐）
针对 4 个后端逐个做「接口—实现—调用方」三方对齐表：
- `HostAccessibilityBackend`（10 方法）/ `HostScreenCaptureBackend`（3）/ `HostSpeechBackend`（8）/ bibi `ScriptHost`
- 检查：槽位是否可重入（P1-1 类）、线程模型（回调线程 vs StateFlow 写入）、生命周期（谁释放）、**降级是否掩盖真 bug**
- **产出**：契约矩阵 + 差异表

### 第 3 轮　构建面审计
- 写 **consumer ProGuard 规则**（覆盖第 1 轮反射清单 + ObjectBox/Room/serialization/ToolPkg）
- 验证 `assembleAppRelease` 通过 + R8 mapping 中关键类未被裁
- 资源合并告警清零复核
- flavor 占位符统一（P1-2）

### 第 4 轮　真机功能验收（P5 + P6 剩余项）
按 §2 的 R2–R7、R10–R12 逐项跑，用 Operit 自带 toolbox 页面做测试面：
`uidebugger`（无障碍/UI 树）、`tooltester`（工具）、`sqlviewer`（DB）、`speechtotext`/`texttospeech`（语音）

### 第 5 轮　性能与卫生
- 懒初始化改造评估（P2-2）
- 后台轮询常驻（记忆自动保存 / 工作流）是否该 gating
- APK 体积裁剪项清单

---

## 4. 交付物

1. **复查报告**（本文件的展开版）：缺陷清单 + 严重度 + 证据（日志/代码行）+ 修复建议 + 影响面
2. **修复清单**（按轮次，含回归验证点）
3. **SYNC.md 增补**：新增 D 类/B 类条目（D-6…、B 类补记）
4. **回归矩阵**：修复后「冷启动 / 抽屉入口 / 聊天 / 无障碍 / 截屏 / 语音 / bibi / 脚本运行 / release」全绿证据

---

## 5. 需要小雨决策的点

| # | 决策 | 选项 |
|---|---|---|
| Q1 | **release/R8 是否在本次范围内？** | A. 必须可用（要写 proguard + release 验收）　B. 暂只保 debug |
| Q2 | **Operit 是否改懒初始化？** | A. 改（宿主冷启动不再承担 Operit 成本）　B. 保持现状（功能优先） |
| Q3 | **是否需支持 `inrt` flavor 同机共存？** | A. 需支持（改占位符）　B. 只保 `app` flavor |
| Q4 | **权限面是否精简？** | A. 精简（去掉宿主不需要的 SMS/电话等）　B. 保持上游全集（功能优先） |
| Q5 | **修复批次** | A. 先修 P0（4 项）再评估　B. P0+P1 一批修完再验收 |

---

## 6. 建议的执行顺序（最小风险优先）

```
第 1 步  修 P0-1 + P0-2（同一根因族，改动小、收益大、可立即真机验证）
第 2 步  修 P1-1 语音槽位（契约缺陷，影响功能正确性）
第 3 步  第 1 轮静态审计（把同类缺陷一次扫净，避免反复返工）
第 4 步  第 3 轮构建面（P1-4 proguard + release 验收，P1-2 flavor）
第 5 步  第 4 轮真机验收（无障碍/截屏/语音/bibi/主功能）
第 6 步  第 5 轮性能卫生（按 Q2 决策）
```

**每步结束都跑一次**：`:app:assembleAppDebug` + 冷启动 + 抽屉入口 + Operit 主界面，作为最小回归门槛。
