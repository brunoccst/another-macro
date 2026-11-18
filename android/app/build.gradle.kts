plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Another Eden app: the macro core (core/android/core) with this game's profile (../game.yaml),
// look (res/) and game plugin (anothermacro.logic, shipped in the logic pack with the core's logic).
val gameYaml: File = rootProject.file(providers.gradleProperty("macroforge.game").get())
fun gameKey(key: String): String = Regex("""(?m)^$key:\s*(\S+)""").find(gameYaml.readText())?.groupValues?.get(1) ?: ""
val core = project(":core")
evaluationDependsOn(":core")

android {
    namespace = "anothermacro"
    compileSdk = 34

    defaultConfig {
        applicationId = gameKey("application_id").ifEmpty { throw GradleException("game.yaml has no application_id:") }
        minSdk = 26
        targetSdk = 34
        versionName = core.extra["macroforge.versionName"] as String
        versionCode = core.extra["macroforge.versionCode"] as Int
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
    packaging {
        // Jackson / OkHttp / Kotlin jars all carry these
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin")
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-module-name", "anothermacro")
    }
}

// Macros, templates and their data are not built in: the app downloads them from the GitHub
// project (the "macros-<branch>" packs CI publishes, see MacroLibrary.kt and macros.yml).

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
