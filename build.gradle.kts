// Top-level build file where you can add configuration options common to all sub-projects/modules.

// @Hint by SuperMonster003 on Aug 16, 2023.
//  ! Blocks "buildscript" and "plugins" have been moved to "settings.gradle.kts".
//  ! zh-CN: 代码块 "buildscript" 以及 "plugins" 已迁移至 "settings.gradle.kts".

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
    //  ! 该任务通常仅由 Gradle 的 Kotlin DSL provider 注册在根项目, 子项目 (如 :app) 中缺失,
    //  ! 导致同步报错: "Task 'prepareKotlinBuildScriptModel' not found in project ':app'".
    //  ! 此处为缺失该任务的项目补注册空任务 (该任务无任何操作, 不影响正常构建).
    //  ! 注: 根项目中任务已存在, 必须先按名称判断, 否则 register 会因重名抛出异常.
    if ("prepareKotlinBuildScriptModel" !in tasks.names) {
        tasks.register("prepareKotlinBuildScriptModel") { }
    }
}

tasks {
    register<Delete>("clean").configure {
        // @Legacy delete(rootProject.buildDir)
        delete(rootProject.layout.buildDirectory)
    }
}
