import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// §11.2 release 只认 CI 用环境变量注入的那把钥匙（GitHub Secrets → 四个 PB_* 同名变量），
// 这里把 keystore 解到 build/ 下的临时文件，仓库里不留任何密钥。
// 少任何一个就没有 release 签名配置，assembleRelease 直接失败：静默产出一个 debug 签名的包
// 更糟——用户换版时签名对不上，只能卸载重装，本地历史全丢。
val releaseSigningEnv = listOf(
    "ANDROID_KEYSTORE_BASE64",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)
val hasReleaseSigning = releaseSigningEnv.all { !System.getenv(it).isNullOrBlank() }

// 本地开发 fallback：apps/android/dev/pushbox-release.jks 配 apps/android/local.properties 里的口令。
// 口令绝不写进本文件：这是一份会公开、会被复制进 CI 日志与新 gradle 文件的清单，而历史上它
// 确实随每一个提交走了一遍。local.properties 与 dev/ 目录都在 .gitignore 里；四项环境变量与
// dev keystore 两条路都不成立时，下面的 buildTypes.release 直接 error()，不出未签名的"正式包"。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localSigningProp(key: String): String? = localProps.getProperty(key)?.takeIf { it.isNotBlank() }

// CI 的 keystore 由 ANDROID_KEYSTORE_BASE64 提供，要的是"一整行标准 base64 字母表"。
// 直接让 Base64.getDecoder() 抛的话，日志里只剩 `Illegal base64 character 2d` 一句，看不出是
// 粘错了格式（Windows 的 certutil -encode 会带 -----BEGIN/END----- 包头）还是 secret 被改坏，
// 而这一条红掉就等于发不出包。所以把"看到了什么形状"说清楚再失败——注意这一串本身就是密钥
// 材料，只能报形状与长度，绝不能把内容或片段打进日志。
fun decodeReleaseKeystore(raw: String): ByteArray {
    val s = raw.trim()
    try {
        return Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
        val shape = listOfNotNull(
            "长度 ${s.length}",
            if (s.contains("-----")) "含 PEM 包头/包尾（-----BEGIN / -----END）" else null,
            if (s.any { it == '-' || it == '_' }) "含 base64url 的 - 或 _" else null,
            if (s.any { it.isWhitespace() }) "含换行或空格" else null,
            if (s.none { it == '+' || it == '/' }) "不含标准字母表特有的 + 或 /" else null,
        ).joinToString("，")
        error(
            "ANDROID_KEYSTORE_BASE64 不是标准 base64（$shape）。这里要的是下面命令的**一整行**输出：\n" +
                "  Linux/macOS： base64 -w0 apps/android/dev/pushbox-release.jks\n" +
                "  PowerShell ： [Convert]::ToBase64String([IO.File]::ReadAllBytes(\"apps\\android\\dev\\pushbox-release.jks\"))\n" +
                "`certutil -encode` 那种带 PEM 包头的产物不能用（它加 -----BEGIN/END----- 与换行）。"
        )
    }
}

val localKeystoreFile = rootProject.file("dev/pushbox-release.jks")
val hasLocalSigning = localKeystoreFile.exists() &&
    listOf("pushbox.signing.storePassword", "pushbox.signing.keyPassword").all { localSigningProp(it) != null }

android {
    namespace = "cn.iamplus.pushbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.iamplus.pushbox"
        // §7.3 的会话通知依赖 NotificationChannel，而它是 API 26 才有的（文档 §10.1 写"minSdk 24
        // 且 NotificationChannel 全可用"是错的）；留 24 只会在 Android 7/7.1 上首帧即崩。
        minSdk = 26
        targetSdk = 36
        versionCode = 10103
        versionName = "1.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 优先级：CI 环境变量 > 本地 dev/keystore
        if (hasReleaseSigning) {
            create("release") {
                val file = layout.buildDirectory.file("pb-release.keystore").get().asFile
                file.parentFile?.mkdirs()
                file.writeBytes(decodeReleaseKeystore(System.getenv("ANDROID_KEYSTORE_BASE64")))
                storeFile = file
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        } else if (hasLocalSigning) {
            create("release") {
                storeFile = localKeystoreFile
                storePassword = localSigningProp("pushbox.signing.storePassword")
                keyAlias = localSigningProp("pushbox.signing.keyAlias") ?: "pushbox"
                keyPassword = localSigningProp("pushbox.signing.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI 环境变量或本地 dev keystore 任一存在即启用签名
            if (hasReleaseSigning || hasLocalSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // pbcore 是 Java 17 源码（record + java.util.Base64 + String.isBlank），minSdk 24 上
        // 这三样都不存在：record 与 Base64 由 D8 脱糖，isBlank 也在脱糖库覆盖范围内。
        // 关掉它就会在 API 24–33 上直接 NoClassDefFoundError，而不是编译期报错。
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // bcprov 是签名过的 jar，不打散这三个后缀会在打包阶段直接报"Duplicate files"
            excludes += setOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

ksp {
    arg("room.incremental", "true")
}

// 没配签名就不许出 release 包：AGP 会安静地产出一个 app-release-unsigned.apk，
// 而那种包一旦进了 Releases，用户装第二次时签名对不上，只能卸载重装、历史全丢。

if (!hasReleaseSigning && !hasLocalSigning) {
    // 消息写成字面量，不引用上面那个 releaseSigningEnv：doLast 一旦捕获脚本顶层的 val，
    // 闭包就要连带序列化 build script 对象本身，而 gradle.properties 里 configuration-cache=true
    // （CI 与本机都开），配置阶段就会以"cannot serialize Gradle script object references"判红——
    // 那时失败原因从"缺签名"变成"这条保护自己写错了"，等于把闸门换成了炸弹。
    val guard = tasks.register("requireReleaseSigning") {
        doLast {
            error("release 未签名：需要 ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, "
                + "ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD 四个环境变量都非空，"
                + "或本地同时有 apps/android/dev/pushbox-release.jks 与 apps/android/local.properties 里的 "
                + "pushbox.signing.storePassword / pushbox.signing.keyPassword")
        }
    }
    tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(guard) }
}

dependencies {
    implementation(project(":pbcore"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    // §8.4 D30 生物识别把 MainActivity 改成 FragmentActivity；老 androidx.fragment(1.2.x，由 biometric
    // 带入)的 ActivityResultRegistry 与 activity 1.10 的 requestCode 方案冲突(报"lower 16 bits")，
    // 显式抬到与 activity-compose 1.10 兼容的版本。
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    implementation(libs.okhttp)

    // §7.5 恢复码二维码：core 负责编码出 BitMatrix，embedded 提供扫码用的相机 Activity
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
