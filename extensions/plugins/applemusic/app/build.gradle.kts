import java.util.Properties
import java.io.File
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val credentials = Properties().apply {
    val path = rootProject.file("../../../keystore.properties")
    if (path.exists()) path.inputStream().use { load(it) }
}
android {
    namespace = "com.music.streamx.extensions.applemusic"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.music.streamx.extensions.applemusic"
        minSdk = 26
        targetSdk = 36
        versionCode = 24 * 10000 + (providers.gradleProperty("extensionBuildNumber").orNull?.toInt() ?: 0)
        versionName = "0.10.0"
    }
    packaging { jniLibs {
        useLegacyPackaging = true
        // Stripping these patchelf-rewritten files corrupts Android's runtime ELF metadata.
        keepDebugSymbols += setOf("**/*_exec.so", "**/libsxq_*.so")
    } }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false } }
    signingConfigs {
        val store = credentials.getProperty("storeFile")?.let { if (File(it).isAbsolute) File(it) else rootProject.file("../../../$it") }
        if (store != null && store.exists()) create("release") {
            storeFile = store; storePassword = credentials.getProperty("storePassword")
            keyAlias = credentials.getProperty("keyAlias"); keyPassword = credentials.getProperty("keyPassword")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { signingConfig = signingConfigs.findByName("release"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
}
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation(project(":embedded-terminal"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val buildEmbeddedAudioTools by tasks.registering(Exec::class) {
    val workspace = rootProject.projectDir
    inputs.files(fileTree(workspace.resolve("native/audio-tools")) { include("**/*.go", "go.mod", "go.sum"); exclude(".git/**") })
    inputs.file(workspace.resolve("tools/build-apple-embedded-tools.sh"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    workingDir(workspace)
    commandLine("bash", "tools/build-apple-embedded-tools.sh")
}
tasks.named("preBuild").configure { dependsOn(buildEmbeddedAudioTools) }

val buildEmbeddedQemu by tasks.registering(Exec::class) {
    val workspace=rootProject.projectDir
    inputs.files(fileTree(workspace.resolve("tools/apple-qemu")))
    inputs.file(workspace.resolve("tools/build-apple-qemu.sh"))
    outputs.file(layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libqemu_exec.so"))
    outputs.file(layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libwrapper_exec.so"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/qemu-firmware"))
    workingDir(workspace)
    commandLine("bash","tools/build-apple-qemu.sh")
    mustRunAfter(buildEmbeddedAudioTools)
}
tasks.named("preBuild").configure {dependsOn(buildEmbeddedQemu)}
