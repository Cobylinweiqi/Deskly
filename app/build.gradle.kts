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
        // 0.15.x 是 realme UI / ColorOS（Launcher3 系）方言识别的本地构建版；
        // 0.16.0 起不再分机型出包：同一个 APK 同时支持小米 / HyperOS 与真我 / ColorOS，
        // 方言由 LauncherProfile 按桌面库结构自动判定，小米分支仍是 0.14.1 的恒等变换。
        // 同时扩充功能分类词典（中文应用名 / 包名段 / PM 应用名 / ApplicationInfo.category），
        // 并在确认框里报出「未识别」图标数。
        // 0.16.1：按真机截图补点名/词典，修「store/market」把应用商店判成购物；
        // 给私有 itemType 的图标行加通用兜底，避免它们被判成非应用留在原屏；
        // 确认框与状态行报出「库里 X 行 / Y 个参与整理 / Z 个原地保留」便于定位。
        // 0.16.2：修「预览说 2、3 屏、桌面却全在第一屏」。文件夹占位从桌面库坐标
        // 探测（HyperOS 是 1 格 / 一屏 24 个，旧版一律按 2×2 / 一屏 6 个），
        // 排屏容量、写回 span、预览文案统一用同一份值。
        // 0.16.3：修真我「方案 2 屏、桌面最终只有 1 屏」（小米正常）。真机诊断显示
        // 布局表是私有名、旧探测认不出屏表、且 screenBase 靠 container=-100 推断时
        // 查不到行而错退回 1，写回的 screen 值桌面根本不认。改为按屏表 id 映射屏号、
        // 按数据反查私有屏表、用模板行补屏；诊断给出全表清单 / 屏表行 / screen 分布。
        // 0.16.3 起正式发布：主仓库与 LSPosed 官方模块库双仓库同步发版。
        versionCode = 32
        versionName = "0.16.3"
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

    // 跨 ROM 验证用：Robolectric 在 JVM 上跑真实 SQLiteDatabase + 合成 launcher.db，
    // 不需要模拟器、不需要真机、不需要 root。见 app/src/test/.../CrossRom*。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

// Robolectric 会在测试 JVM 里自己去 Maven 下载 android-all 镜像，而 Gradle 的 -D 只作用于
// 构建进程、不会传给 fork 出来的测试进程。这里从环境变量取代理（本机是 HTTP_PROXY/HTTPS_PROXY），
// 注进测试 JVM；没有代理环境时是空操作。
tasks.withType<Test>().configureEach {
    val raw = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
        .firstNotNullOfOrNull { System.getenv(it) }
    if (!raw.isNullOrBlank()) {
        // 形如 http://127.0.0.1:53090 —— 手动切，不用 java.net.URI：
        // 在 .kts 里 `java` 指向 Gradle 的 java 扩展，`java.net.*` 会解析不到。
        val hostPort = raw.substringAfter("://", raw).substringBefore("/")
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":", "")
        if (host.isNotEmpty() && port.toIntOrNull() != null) {
            systemProperty("https.proxyHost", host)
            systemProperty("https.proxyPort", port)
            systemProperty("http.proxyHost", host)
            systemProperty("http.proxyPort", port)
        }
    }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

