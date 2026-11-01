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
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"
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
        include("*/templates/**")
        eachFile { path = "templates/" + path.replace("/templates/", "/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/macroAssets"))
}
tasks.named("preBuild") { dependsOn(copyMacroAssets) }

dependencies {
    implementation("org.opencv:opencv:4.10.0")
    implementation("org.snakeyaml:snakeyaml-engine:2.7")
}
