import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The core of a macro app: screen capture, vision, OCR, taps, the macro runner, the floating
// buttons, the phone connector, the UI, the updater. A game's app module depends on it and adds
// its profile (game.yaml), its look and its game plugin (docs/game-profile.md).
//
// Gradle properties the game's build sets (gradle.properties of its root project):
//   macroforge.game  the game profile, relative to the root project (e.g. ../game.yaml)
//   macroforge.app   the game's app module (e.g. :app)
val gameYaml: File = rootProject.file(providers.gradleProperty("macroforge.game").getOrElse("../game.yaml"))
val appPath: String = providers.gradleProperty("macroforge.app").getOrElse(":app")
fun gameKey(key: String): String = Regex("""(?m)^$key:\s*(\S+)""").find(gameYaml.readText())?.groupValues?.get(1) ?: ""

// The logic pack (see Logic.kt): the generic `macroforge.logic` package and the game plugin's
// package also ship apart from the app, so a change to them reaches the phone without an install.
// A pack only loads into an app with the same "shell": every other source file of this module and
// of the app module, their build files and game.yaml. SHELL_ID fingerprints them.
val pluginPackage = gameKey("plugin").substringBeforeLast('.', "")
val shellId: String = MessageDigest.getInstance("SHA-256").let { md ->
    val modules = listOf(project, rootProject.project(appPath))
    val files = modules.flatMap { m ->
        fileTree(m.file("src/main")) { exclude("java/macroforge/logic/**"); if (pluginPackage.isNotEmpty()) exclude("java/" + pluginPackage.replace('.', '/') + "/**") }.files +
            listOf(m.file("build.gradle.kts"))
    } + listOf(rootProject.file("build.gradle.kts"), gameYaml)
    for (f in files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }) {
        md.update(f.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
        md.update(f.readBytes())
    }
    md.digest().joinToString("") { "%02x".format(it) }.take(16)
}

// releases: VERSION_NAME from the tag (v1.2.3 -> 1.2.3), versionCode derived from it (1.2.3 -> 10203);
// CI sets VERSION_CODE to the minutes since 2025-01-01, so every build is an upgrade of the one before
val versionName: String = System.getenv("VERSION_NAME") ?: "0.0.0-dev"
val versionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: Regex("""^(\d+)\.(\d+)\.(\d+)""").find(versionName)?.destructured
        ?.let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }?.coerceAtLeast(1) ?: 1
extra["macroforge.versionName"] = versionName
extra["macroforge.versionCode"] = versionCode

android {
    namespace = "macroforge"
    compileSdk = 34

    defaultConfig {
        // GestureDescription continued strokes and the accessibility overlay need 26
        minSdk = 26
        buildConfigField("String", "VERSION_NAME", "\"$versionName\"")
        buildConfigField("int", "VERSION_CODE", "$versionCode")
        // the branch CI built this from: a dev build installs that branch's newer CI builds by itself
        buildConfigField("String", "BRANCH", "\"" + (System.getenv("BUILD_BRANCH") ?: "") + "\"")
        // the commit CI built this from, and the shell fingerprint a logic pack must match (Logic.kt)
        buildConfigField("String", "COMMIT", "\"" + (System.getenv("GITHUB_SHA") ?: "") + "\"")
        buildConfigField("String", "SHELL_ID", "\"$shellId\"")
        // the game this app plays (game.yaml, GameProfile.kt), baked in whole
        buildConfigField("String", "GAME_YAML", "\"" + gameYaml.readText().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"")
        // the game's package for the manifest's <queries> (to see and open the game)
        manifestPlaceholders["gamePackage"] = gameKey("package").ifEmpty { throw GradleException("game.yaml has no package:") }
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
        // one module name for every build type: Kotlin puts it in the names of `internal` members,
        // which the logic pack calls
        freeCompilerArgs += listOf("-module-name", "macroforge")
    }
}

dependencies {
    api("org.opencv:opencv:4.10.0")
    api("org.snakeyaml:snakeyaml-engine:2.7")
    // on-device text recognition (bundled model, no Google Play services needed)
    api("com.google.mlkit:text-recognition:16.0.1")
    // the phone connector: WebSocket to the relay (OkHttp), MCP JSON-RPC (Jackson)
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.19.4")
    testImplementation("junit:junit:4.13.2")
}

// --- the logic pack: logic.zip = classes.dex of the logic packages + logic.json ---------------------
// CI attaches it to each branch's dev build; `gradle logicPack` builds it (after the app's compile).
val logicDex = tasks.register<Exec>("logicDex") {
    dependsOn("compileDebugKotlin", "$appPath:compileDebugKotlin")
    val out = layout.buildDirectory.dir("logic/dex").get().asFile
    outputs.dir(out)
    doFirst {
        out.deleteRecursively(); out.mkdirs()
        val sdk = android.sdkDirectory
        val d8 = File(sdk, "build-tools/${android.buildToolsVersion}/d8")
        val dirs = listOf(layout.buildDirectory.dir("tmp/kotlin-classes/debug/macroforge/logic").get().asFile) +
            if (pluginPackage.isEmpty()) emptyList() else listOf(rootProject.project(appPath).layout.buildDirectory
                .dir("tmp/kotlin-classes/debug/" + pluginPackage.replace('.', '/')).get().asFile)
        val classes = dirs.flatMap { d -> fileTree(d).matching { include("**/*.class") }.files }.map { it.path }.sorted()
        if (classes.isEmpty()) throw GradleException("no compiled logic classes in $dirs")
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
        out.writeText("{\"shell\": ${q(shellId)}, \"code\": $versionCode, \"name\": ${q(versionName)}, " +
            "\"branch\": ${q(System.getenv("BUILD_BRANCH"))}, \"commit\": ${q(System.getenv("GITHUB_SHA"))}}\n")
    }
}
tasks.register<Zip>("logicPack") {
    dependsOn(logicDex, logicJson)
    from(layout.buildDirectory.dir("logic/dex")) { include("classes.dex") }
    from(layout.buildDirectory.dir("logic/json"))
    archiveFileName.set("logic.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("outputs/logic"))
}
tasks.register("shellId") { doLast { println(shellId) } }
