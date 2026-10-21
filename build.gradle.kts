plugins {
    alias(libs.plugins.android.application)
    // Pins the Kotlin Gradle plugin used by AGP's built-in Kotlin support and by :jawmodel.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.asofwar.freebrush"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.asofwar.freebrush"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Personal sideloaded build: signed with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
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
}
