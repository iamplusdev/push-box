// Gradle 只管 Android；Java 侧（packages/core 与 server）仍然是 Maven，:pbcore 直接编译 ../packages/core 的同一份源码。
//
// 仓库口径：默认只走官方源。aliyun 镜像只对墙内的开发机有意义 —— 2026-10-20 实测 GitHub runner
// 打 `maven.aliyun.com` 的三个仓库整片返 502（那次 `build-apk.yml` 红的日志），而镜像当时排在
// `pluginManagement` 最前面，于是 KSP 的插件 marker 被判成 "was not found in any of the following
// sources"，尽管 `repo.maven.apache.org` 上同一份 pom 是 200。本机在墙内看不到那个 502，所以本地一直绿。
//
// 需要镜像加速的开发机：在 `android/local.properties`（已被 .gitignore 忽略，不入库）加一行
//     pb.maven.cnMirror=true
// 全局 `~/.gradle/init.gradle` 只改写 project 级仓库，够不到 `pluginManagement`，所以开关只能放在这里。
// 另外 `pluginManagement` 是单独一段先编译的，看不见脚本里其它位置的声明，所以下面两处各自读一次文件。
pluginManagement {
    repositories {
        val cnMirror = java.util.Properties().apply {
            val f = File(settingsDir, "local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }.getProperty("pb.maven.cnMirror") == "true"
        if (cnMirror) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val cnMirror = java.util.Properties().apply {
    val f = File(settingsDir, "local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("pb.maven.cnMirror") == "true"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (cnMirror) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "PushBox"
include(":app")
include(":pbcore")
