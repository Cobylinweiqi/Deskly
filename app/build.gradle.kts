plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.cobylinweiqi.deskly"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.cobylinweiqi.deskly"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.14.1"
    }

    // libxposed 的模块描述文件必须原样进 APK 根目录，不能被 shrink/merge 规则丢弃
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    // release 用 debug keystore 签名：目的是让用户能覆盖升级已装的 debug 包，
    // 而不是发布到任何商店。同一份 keystore 意味着签名一致，adb install -r 才不会被拒。
    signingConfigs {
        create("local") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // debug 保持不压缩：迭代快，堆栈里是原始行号。
            // 但要注意 AGP 在未开 R8 时会把 dex 拆成 6 个（每个 Gradle 产物一个），
            // 体积反而比 release 大——所以对外安装用 release。
            isMinifyEnabled = false
        }
        release {
            // R8 在这里干三件事：把 6 个 dex 合并成 1 个（省掉重复的索引池）、
            // 删掉未引用的代码、配合 shrinkResources 删掉未引用的资源。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("local")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 仅在编译期可见：运行时由 LSPosed 框架注入
    compileOnly("io.github.libxposed:api:101.0.0")
}
