// Gradle 只管 Android；Java 侧（core/server）仍然是 Maven，:pbcore 直接编译 ../core 的同一份源码。
pluginManagement {
    repositories {
        // 全局 ~/.gradle/init.gradle 只改写 project 级仓库，够不到这里，所以镜像必须写死。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "PushBox"
include(":app")
include(":pbcore")
