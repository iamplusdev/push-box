// §7.1/§7.4：Android 与服务端共用同一份加密与协议实现，做法不是发布 artifact，
// 而是直接把 ../../packages/core 的源码目录挂进这个 JVM 库模块——两端字节级一致这件事必须由构建保证。
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    // core 的编译下限是 17（服务端跑 21），Android 侧也只能用 17 才不被 D8 拒收
    options.release = 17
    options.encoding = "UTF-8"
}

sourceSets {
    main {
        // 相对路径按 apps/android/ 这个 Gradle 根解析：apps/android/ + ../../packages/core
        java.setSrcDirs(listOf(rootProject.file("../../packages/core/src/main/java")))
        // 测试仍只在 Maven 侧跑（JUnit 5 + vectors.json），这里不复制一套测试依赖；
        // 资源目录同理置空，避免 ../../packages/core/src/main/resources 不存在时报警。
        resources.setSrcDirs(emptyList<String>())
    }
}

dependencies {
    // 帧与 DTO 全是 Jackson 注解的 record，类型会出现在本模块公开 API 上，所以是 api
    api(libs.jackson.databind)
    implementation(libs.bouncycastle)
}
