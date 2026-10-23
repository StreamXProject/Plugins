import java.util.Properties
import java.io.File
plugins { id("com.android.application") }
val credentials = Properties().apply {
    val path = rootProject.file("../../../keystore.properties")
    if (path.exists()) path.inputStream().use { load(it) }
}
android {
    namespace = "com.music.streamx.extensions.spotify"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.music.streamx.extensions.spotify"
        minSdk = 26
        targetSdk = 36
        versionCode = 44 * 10000 + (providers.gradleProperty("extensionBuildNumber").orNull?.toInt() ?: 0)
        versionName = "0.5.23"
    }
    signingConfigs {
        val store = credentials.getProperty("storeFile")?.let { if (File(it).isAbsolute) File(it) else rootProject.file("../../../$it") }
        if (store != null && store.exists()) create("release") {
            storeFile = store
            storePassword = credentials.getProperty("storePassword")
            keyAlias = credentials.getProperty("keyAlias")
            keyPassword = credentials.getProperty("keyPassword")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64"); isUniversalApk = false } }
    packaging { jniLibs.useLegacyPackaging = true }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Native audio remains inside the extension. The host consumes transport/status IPC only.
val nativeAudioEnabled = providers.gradleProperty("spotifyNative").map(String::toBoolean).getOrElse(true)
val nativeAudioAbis = providers.gradleProperty("spotifyNativeAbis").getOrElse("arm64-v8a,armeabi-v7a,x86_64")
val nativeAudioOutput = layout.buildDirectory.dir("generated/nativeAudio")
if (nativeAudioEnabled) {
    android.ndkVersion = "27.0.12077973"
    android.sourceSets.getByName("main").jniLibs.srcDir(nativeAudioOutput)
    val compileNativeAudio = tasks.register<Exec>("compileNativeAudio") {
        inputs.file(rootProject.file("tools/build-native.sh"))
        inputs.dir(rootProject.file("native/src"))
        inputs.dir(rootProject.file("native/vendor"))
        inputs.file(rootProject.file("native/Cargo.toml"))
        inputs.file(rootProject.file("native/Cargo.lock"))
        inputs.property("abis", nativeAudioAbis)
        outputs.dir(nativeAudioOutput)
        commandLine("bash", rootProject.file("tools/build-native.sh").absolutePath,
            androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("ndk/27.0.12077973").absolutePath,
            nativeAudioOutput.get().asFile.absolutePath, nativeAudioAbis)
    }
    tasks.named("preBuild").configure { dependsOn(compileNativeAudio) }
}
