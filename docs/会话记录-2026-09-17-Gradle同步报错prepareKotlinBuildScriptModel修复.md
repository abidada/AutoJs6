# 会话记录 - 2026-09-17 - Gradle 同步报错 prepareKotlinBuildScriptModel 修复

## 一、用户提出的问题

> 项目编译报错：
>
> ```
> Caused by: com.intellij.openapi.externalSystem.model.ExternalSystemException:
> Task 'prepareKotlinBuildScriptModel' not found in project ':app'.
> ```
>
> 报错截图信息：Build/Sync 失败（app: failed，At 2026-09-17 0:51 with 2 errors），错误：
> `com.intellij.openapi.externalSystem.model.ExternalSystemException: Task 'prepareKotlinBuildScriptModel' not found in project ':app'.`
> `Task 'prepareKotlinBuildScriptModel' not found in project ':app'`
>
> （用户补充：修复完由用户自己手动在 Android Studio 中进行编译测试）

## 二、问题分析

### 1. 环境信息

| 项目 | 值 |
| --- | --- |
| Gradle Wrapper | 9.4.0（`gradle/wrapper/gradle-wrapper.properties`） |
| Kotlin Gradle 插件 | 由 `settings.gradle.kts` 自动匹配（`gradle/data/gradle-kotlin-compat.properties` 中 `9.4.0=2.3.0`），即 Kotlin 2.3.0 |
| AGP | 自动匹配（`version.properties` 中 `OVERRIDDEN_ANDROID_GRADLE_PLUGIN_VERSION=NONE`） |
| IDE | Android Studio（Build/Sync 触发） |

### 2. 根因

- `prepareKotlinBuildScriptModel` 是 IDE 在同步 Kotlin DSL 脚本模型（`KotlinDslScriptsModel`）之前需要执行的前置任务。
  Gradle 官方文档对该模型的说明："Requires the `prepareKotlinBuildScriptModel` task to be executed before building the model."（参见 Gradle API 文档 `org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptsModel`）
- 该任务通常由 Kotlin Gradle 插件（KGP）自动注册，但当前自动匹配的 Kotlin 2.3.0 并未在含 `.gradle.kts` 脚本的子项目（如 `:app`）中注册该任务，项目自身的构建脚本（根 `build.gradle.kts` / `app/build.gradle.kts`）中也均未注册（全局搜索 `prepareKotlinBuildScriptModel` 无结果）。
- 因此 Android Studio 同步时对该任务发起执行请求，Gradle 找不到任务，抛出 `Task 'prepareKotlinBuildScriptModel' not found in project ':app'`，导致整个 Sync 失败。

### 3. 方案选择

- ✅ 采用：在根 `build.gradle.kts` 的 `allprojects` 中为所有项目注册同名空任务（最小侵入，无副作用，仅在 IDE 同步时被调用；KGP 内部使用 `maybeCreate` 注册同名任务，先注册不会产生冲突）。
- 备选（未采用）：通过 `version.properties` 的 `OVERRIDDEN_KOTLIN_GRADLE_PLUGIN_VERSION` 强制降级 Kotlin 版本——影响面大，且与 Gradle 9.4.0 的兼容表不匹配。

## 三、修改内容

修改文件：`build.gradle.kts`（项目根目录）

在 `allprojects` 代码块末尾新增空任务注册：

```kotlin
allprojects {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/jcenter")
        maven("https://maven.aliyun.com/repository/public")
    }

    // @Fix on Sep 17, 2026.
    //  ! IDE (Android Studio / IntelliJ) 在同步 Kotlin DSL 脚本模型 (KotlinDslScriptsModel) 前会对
    //  ! 含 .gradle.kts 脚本的项目 (如 :app) 执行任务 "prepareKotlinBuildScriptModel".
    //  ! 当前 Kotlin Gradle 插件 (自动匹配为 2.3.0) 未在子项目中自动注册该任务, 导致同步报错:
    //  ! "Task 'prepareKotlinBuildScriptModel' not found in project ':app'".
    //  ! 此处为所有项目注册同名的空任务以修复同步失败 (该任务无任何操作, 不影响正常构建).
    //  ! 注: Kotlin Gradle 插件内部使用 maybeCreate 注册同名任务, 此处先注册不会产生冲突.
    tasks.register("prepareKotlinBuildScriptModel") { }
}
```

## 四、第一次修复后的反馈与二次修复（2026-09-17 1:01）

### 1. 用户反馈的新报错

初次修复（无条件 `tasks.register`）后重新 Sync，出现新错误：

```
build.gradle.kts :27
Cannot add task 'prepareKotlinBuildScriptModel' as a task with that name already exists.
```

### 2. 原因

- 根项目中该任务**已存在**（由 Gradle 自身的 Kotlin DSL provider 在根项目注册），而 `:app` 等子项目缺失——这与最初"not found in project ':app'"的报错互相印证。
- `TaskContainer.register(name)` 遇到已存在的任务名会直接抛出 `Cannot add task ... already exists` 异常（不同于 KGP 内部的 `maybeCreate` 幂等语义）。
- 因此 `allprojects` 循环执行到根项目时注册失败。

### 3. 二次修复

改为先按名称判断任务是否已存在（使用 `tasks.names` 判断不会触发任务实例化），仅对缺失该任务的项目补注册：

```kotlin
// @Fix on Sep 17, 2026.
//  ! IDE (Android Studio / IntelliJ) 在同步 Kotlin DSL 脚本模型 (KotlinDslScriptsModel) 前会对
//  ! 含 .gradle.kts 脚本的项目 (如 :app) 执行任务 "prepareKotlinBuildScriptModel".
//  ! 该任务通常仅由 Gradle 的 Kotlin DSL provider 注册在根项目, 子项目 (如 :app) 中缺失,
//  ! 导致同步报错: "Task 'prepareKotlinBuildScriptModel' not found in project ':app'".
//  ! 此处为缺失该任务的项目补注册空任务 (该任务无任何操作, 不影响正常构建).
//  ! 注: 根项目中任务已存在, 必须先按名称判断, 否则 register 会因重名抛出异常.
if ("prepareKotlinBuildScriptModel" !in tasks.names) {
    tasks.register("prepareKotlinBuildScriptModel") { }
}
```

## 五、验证方式（由用户手动执行）

1. 在 Android Studio 中点击 `File | Sync Project with Gradle Files`（或工具栏大象图标 Sync）重新同步。
2. 预期：不再出现 `Task 'prepareKotlinBuildScriptModel' not found in project ':app'` 错误，也不再出现 `Cannot add task 'prepareKotlinBuildScriptModel' ... already exists` 错误，Sync 成功。
3. 若同步成功后正常编译即可（用户手动测试）。
4. 测试通过后由用户自行提交 git（按项目规则，助手不做备份提交）。

## 六、第二次反馈：编译期资源链接错误（2026-09-17 1:22）

### 1. 用户反馈的报错

Sync 通过后进入编译，出现：

```
Android resource linking failed
layout/bottom_sheet_log.xml:81: error: resource color/console_debug (aka org.autojs.autojs6:color/console_debug) not found.
layout/bottom_sheet_log.xml:81: error: resource color/console_verbose (aka org.autojs.autojs6:color/console_verbose) not found.
error: failed linking file resources.
```

### 2. 原因

- 报错来自此前提交 "feat: 添加底部日志面板功能"（49ea44ec）新增的 [bottom_sheet_log.xml](../../app/src/main/res/layout/bottom_sheet_log.xml)，其中 `ConsoleView` 通过自定义属性 `app:color_debug` / `app:color_verbose`（定义于 `values/attrs.xml:89-90`）引用了不存在的颜色资源 `@color/console_debug` / `@color/console_verbose`。
- 项目中已有语义完全对应的现有资源：`values/colors.xml` 中的 `console_view_debug`（#111214）/ `console_view_verbose`（#BDBDBD），且 `values-night/colors.xml` 提供了暗色主题覆盖（`console_view_debug`=#DFE0E0E0、`console_view_verbose`=#7F7F80）。参考同类布局 `activity_main_inrt.xml` 中 ConsoleView 使用的即为此组色值。

### 3. 修复

修改 `app/src/main/res/layout/bottom_sheet_log.xml` 第 80-81 行，改为引用现有颜色资源（同时自动适配暗色主题）：

```xml
app:color_debug="@color/console_view_debug"
app:color_verbose="@color/console_view_verbose"/>
```

### 4. 验证方式（由用户手动执行）

- 在 Android Studio 中重新 Build / Run 调试启动应用，预期资源链接通过，不再出现 `resource color/console_* not found` 错误。

## 七、第三次反馈：Kotlin 编译错误（2026-09-17 1:24）

### 1. 用户反馈的报错

```
> Task :app:compileAppDebugKotlin FAILED
e: LogBottomSheet.kt:82:29 Unresolved reference 'getInstance'.
e: LogBottomSheet.kt:84:47 Unresolved reference 'globalConsole'.
e: LogBottomSheet.kt:101:20 Unresolved reference 'getInstance'.
```

### 2. 原因

- "添加底部日志面板功能" 提交新增的 [LogBottomSheet.kt](../../app/src/main/java/org/autojs/autojs/ui/log/LogBottomSheet.kt) 使用了不存在的 API：`AutoJs.getInstance()`。
- 本项目实际惯例为 `AutoJs.instance`（`AutoJs.kt` companion object 中的 `lateinit var instance: AutoJs`），全局控制台通过 `AutoJs.instance.globalConsole` 获取（`AbstractAutoJs` 中定义，项目中 `inrt/LogActivity.kt`、`JsConsoleView.kt` 等均如此使用）。
- 82 行 `AutoJs.getInstance()` 解析失败后，`autoJs` 变量为错误类型，连带导致 84 行 `globalConsole` unresolved。

### 3. 已确认的关联 API（均存在，无需修改）

- `ConsoleView.setConsole(ConsoleImpl)`、`setEnableStackFrameLinks(boolean)`、`setOnStackFrameClickListener(...)`（`core/console/ConsoleView.java`）
- `GlobalConsole extends ConsoleImpl`，`clear()` 可用（`core/console/ConsoleImpl.kt`）
- `R.id.input_container` 定义于 `layout/console_view_legacy.xml`，且代码使用 `?.` 安全调用

### 4. 修复

修改 `LogBottomSheet.kt` 的 `setupViews()`：

```kotlin
// Setup ConsoleView with global console
binding.console.setConsole(AutoJs.instance.globalConsole)
// ...(中间逻辑不变, 仅去掉对 autoJs 局部变量与非空判断的包裹)

// Clear button
binding.btnClear.setOnClickListener {
    AutoJs.instance.globalConsole.clear()
}
```

即：`AutoJs.getInstance()` → `AutoJs.instance`；`autoJs.globalConsole` → `AutoJs.instance.globalConsole`；`AutoJs.getInstance()?.globalConsole?.clear()` → `AutoJs.instance.globalConsole.clear()`。

### 5. 验证方式（由用户手动执行）

- 在 Android Studio 中重新 Build / Run 调试启动应用，预期 `:app:compileAppDebugKotlin` 通过。

## 八、备注

- 本次同步失败发生在同步流程后段（耗时约 4 分 18 秒），说明依赖解析等前序步骤已通过，该任务缺失是本次 Sync 失败的直接原因。
- 如果后续升级 IDE / Gradle / Kotlin 后再次出现同类报错，可优先检查 Kotlin Gradle 插件版本与 IDE 版本的兼容性。
