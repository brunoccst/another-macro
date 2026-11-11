import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The logic pack (see Logic.kt): the `logic` package also ships apart from the app, so a change to it
// reaches the phone without an install. It only loads into an app with the same "shell": every other
// file of the app. SHELL_ID fingerprints them; change any of them and the phone needs the new app.
val logicDir = "src/main/java/io/github/brunoccst/anothermacro/logic"
val shellId: String = MessageDigest.getInstance("SHA-256").let { md ->
    val files = fileTree("src/main") { exclude("java/io/github/brunoccst/anothermacro/logic/**") }.files +
        listOf(file("build.gradle.kts"), rootProject.file("build.gradle.kts"))
    for (f in files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }) {
        md.update(f.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
        md.update(f.readBytes())
    }
    md.digest().joinToString("") { "%02x".format(it) }.take(16)
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
        // the commit CI built this from, and the shell fingerprint a logic pack must match (Logic.kt)
        buildConfigField("String", "COMMIT", "\"" + (System.getenv("GITHUB_SHA") ?: "") + "\"")
        buildConfigField("String", "SHELL_ID", "\"$shellId\"")
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
        // one module name for every build type: Kotlin puts it in the names of `internal` members,
        // which the logic pack calls in the app
        freeCompilerArgs += listOf("-module-name", "another_macro")
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

// --- the logic pack: logic.zip = classes.dex of the `logic` package + logic.json ------------------
// CI attaches it to each branch's dev build (android.yml); `gradle -p android logicPack` builds it.
val logicClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug/io/github/brunoccst/anothermacro/logic")
val logicDex = tasks.register<Exec>("logicDex") {
    dependsOn("compileDebugKotlin")
    val out = layout.buildDirectory.dir("logic/dex").get().asFile
    outputs.dir(out)
    doFirst {
        out.deleteRecursively(); out.mkdirs()
        val sdk = android.sdkDirectory
        val d8 = File(sdk, "build-tools/${android.buildToolsVersion}/d8")
        val classes = fileTree(logicClasses).matching { include("**/*.class") }.files.map { it.path }.sorted()
        if (classes.isEmpty()) throw GradleException("no compiled logic classes in ${logicClasses.get()}")
        commandLine(listOf(d8.path, "--release", "--min-api", "26", "--lib", File(sdk, "platforms/${android.compileSdkVersion}/android.jar").path,
            "--output", out.path) + classes)
    }
}
val logicJson = tasks.register("logicJson") {
    val out = layout.buildDirectory.file("logic/json/logic.json").get().asFile
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        out.parentFile.mkdirs()
        fun q(v: String?) = "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val code = System.getenv("VERSION_CODE") ?: "0"
        out.writeText("{\"shell\": ${q(shellId)}, \"code\": $code, \"name\": ${q(System.getenv("VERSION_NAME") ?: "0.0.0-dev")}, " +
            "\"branch\": ${q(System.getenv("BUILD_BRANCH"))}, \"commit\": ${q(System.getenv("GITHUB_SHA"))}}\n")
    }
}
tasks.register<Zip>("logicPack") {
    dependsOn(logicDex, logicJson)
    from(layout.buildDirectory.dir("logic/dex")) { include("classes.dex") }
    from(layout.buildDirectory.dir("logic/json"))
    archiveFileName.set("logic.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/logic"))
}
tasks.register("shellId") { doLast { println(shellId) } }
