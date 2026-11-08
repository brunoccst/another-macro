plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.brunoccst.anothermacro"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.brunoccst.anothermacro"
        // GestureDescription continued strokes and the accessibility overlay need 26
        minSdk = 26
        targetSdk = 34
        // releases: VERSION_NAME from the tag (v1.2.3 -> 1.2.3) and versionCode derived
        // from it (1.2.3 -> 10203) so every release is an upgrade; dev builds are 0.0.0-dev
        val name = System.getenv("VERSION_NAME") ?: "0.0.0-dev"
        versionName = name
        // CI sets VERSION_CODE to the minutes since 2025-01-01, so every build (dev or release) is
        // an upgrade of the one before: the app can update itself from CI builds
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
            ?: Regex("""^(\d+)\.(\d+)\.(\d+)""").find(name)?.destructured
                ?.let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }?.coerceAtLeast(1) ?: 1
        // the branch CI built this from: a dev build installs that branch's newer CI builds by itself
        buildConfigField("String", "BRANCH", "\"" + (System.getenv("BUILD_BRANCH") ?: "") + "\"")
        // OpenCV ships native code for every ABI (~135 MB); phones that run the game are arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One permanent signing key, or Android refuses updates (and auto-update can't work).
    // CI decodes it from the ANDROID_KEYSTORE_B64 / ANDROID_KEYSTORE_PASSWORD secrets;
    // without them the build falls back to the throwaway debug key.
    val keystore = System.getenv("ANDROID_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("permanent") {
                storeFile = keystore
                storeType = "PKCS12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = "release"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (keystore != null) signingConfig = signingConfigs.getByName("permanent")
        }
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("permanent")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        // Jackson / OkHttp / Kotlin jars all carry these
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin")
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Macros, templates and their data are not built in: the app downloads them from the GitHub
// project (the "macros-<branch>" packs CI publishes, see MacroLibrary.kt and macros.yml).

dependencies {
    implementation("org.opencv:opencv:4.10.0")
    implementation("org.snakeyaml:snakeyaml-engine:2.7")
    // on-device text recognition (bundled model, no Google Play services needed): cat clues, map labels
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // the phone connector: WebSocket to the relay (OkHttp), MCP JSON-RPC (Jackson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.4")
    testImplementation("junit:junit:4.13.2")
}
