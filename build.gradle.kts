import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Pins the Kotlin Gradle plugin used by AGP's built-in Kotlin support and by :jawmodel.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose)
}

// Version: version.properties holds MAJOR.MINOR.PATCH; versionCode = MAJOR * 1_000_000 + MINOR * 1_000 + PATCH,
// so it grows with every release. The Release workflow builds with RELEASE_TAG=vX.Y.Z and gets the plain name;
// every other build is X.Y.Z-dev+<commit>, which the updater treats as older than release X.Y.Z.
val versionProperties = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val semver: String = versionProperties.getProperty("versionName", "").trim()
val semverParts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(semver)?.groupValues?.drop(1)?.map { it.toInt() }
    ?: throw GradleException("version.properties: versionName must be MAJOR.MINOR.PATCH, got '$semver'")
if (semverParts[1] > 999 || semverParts[2] > 999) throw GradleException("version.properties: MINOR and PATCH must be <= 999")
val releaseTag: String? = providers.environmentVariable("RELEASE_TAG").orNull?.trim()?.ifEmpty { null }
if (releaseTag != null && releaseTag != "v$semver") {
    throw GradleException("RELEASE_TAG $releaseTag does not match version.properties (v$semver)")
}
val gitCommit: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("")

// Debug builds take adb commands only with this token (local.properties, not in git); empty disables them.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val debugToken: String = localProperties.getProperty("freebrush.debugToken", "").trim()

// Release key: keystore.properties (not in git) on the release machine, written from secrets by the Release
// workflow. Updates install over the app only when signed with the same key, so a release refuses to build
// without it. Where the key is present, debug builds are signed with it too: a test build then installs over the
// release app and back without losing its data. Forks and the plain CI build use the debug key.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseKeystore: File? = keystoreProperties.getProperty("storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }
if (releaseTag != null && releaseKeystore == null) throw GradleException("A release build needs the release key in keystore.properties")

android {
    namespace = "dev.asofwar.freebrush"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.asofwar.freebrush"
        minSdk = 31
        targetSdk = 36
        versionCode = semverParts[0] * 1_000_000 + semverParts[1] * 1_000 + semverParts[2]
        versionName = if (releaseTag != null) semver else "$semver-dev" + (if (gitCommit.isNotEmpty()) "+$gitCommit" else "")

        // Where the in-app updater looks for releases.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"Asofwar/freebrush\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        val signing = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        getByName("debug") {
            signingConfig = signing
            buildConfigField("String", "DEBUG_TOKEN", "\"$debugToken\"")
        }
        getByName("release") {
            // R8 and resource shrinking: the APK drops from about 24 MB to a few, and Compose runs faster.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
            buildConfigField("String", "DEBUG_TOKEN", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.failOnNoDiscoveredTests = false
        }
    }
}

/** Copies the jaw mesh baked by :jawmodel into a generated assets folder of the app. */
abstract class BakedJawAssets : DefaultTask() {
    @get:InputFile
    abstract val baked: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        baked.get().asFile.copyTo(File(dir, "jaw.bin"), overwrite = true)
    }
}

val bakedJawAssets = tasks.register<BakedJawAssets>("bakedJawAssets") {
    dependsOn(":jawmodel:bakeJaw")
    baked.set(layout.projectDirectory.file("jawmodel/build/baked/jaw.bin"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bakedJawAssets, BakedJawAssets::outputDir)
    }
}

dependencies {
    implementation(project(":jawmodel"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The real org.json: android.jar only has stubs that throw in unit tests.
    testImplementation(libs.json)
}
