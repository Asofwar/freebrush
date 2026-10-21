import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: the jaw mesh generator, its baked file format and the camera/picking math.
// The mesh is generated here at build time and shipped to the app as a ready-made asset.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

val bakedJaw = layout.buildDirectory.file("baked/jaw.bin")

/** Generates the jaw mesh on the build machine and writes it in the compact baked format. */
tasks.register<JavaExec>("bakeJaw") {
    description = "Bakes the 3D jaw mesh into build/baked/jaw.bin"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.asofwar.freebrush.jaw.JawCodecKt")
    args(bakedJaw.get().asFile.absolutePath)
    inputs.files(sourceSets["main"].runtimeClasspath)
    outputs.file(bakedJaw)
}
