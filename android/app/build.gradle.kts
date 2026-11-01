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
        versionCode = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(name)?.destructured
            ?.let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }?.coerceAtLeast(1) ?: 1
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
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/macroAssets"))
}

// The templates and screen libraries are shared with the Python tool: copy them
// from the repo into the APK's assets at build time instead of duplicating them.
val copyMacroAssets by tasks.registering(Copy::class) {
    val repo = rootProject.projectDir.parentFile
    from(File(repo, "src/another_macro/assets/templates")) { into("templates/common") }
    from(File(repo, "src/another_macro/scenarios")) {
        include("*/templates/**", "*/riddles.yaml")
        eachFile { path = "templates/" + path.replace("/templates/", "/") }
        includeEmptyDirs = false
    }
    from(File(repo, "macros")) { into("macros") }
    into(layout.buildDirectory.dir("generated/macroAssets"))
}
tasks.named("preBuild") { dependsOn(copyMacroAssets) }

dependencies {
    implementation("org.opencv:opencv:4.10.0")
    implementation("org.snakeyaml:snakeyaml-engine:2.7")
}
