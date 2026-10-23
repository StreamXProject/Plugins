import java.util.Properties
import java.io.File
plugins { id("com.android.application") }
val credentials = Properties().apply {
    val path = rootProject.file("../../../keystore.properties")
    if (path.exists()) path.inputStream().use { load(it) }
}
android {
    namespace = "com.music.streamx.cloudstream"
    compileSdk = 37
    defaultConfig { applicationId = "com.music.streamx.cloudstream"; minSdk = 26; targetSdk = 36; versionCode = 7 * 10000 + (providers.gradleProperty("extensionBuildNumber").orNull?.toInt() ?: 0); versionName = "0.2.3"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    signingConfigs {
        val store = credentials.getProperty("storeFile")?.let { if (File(it).isAbsolute) File(it) else rootProject.file("../../../$it") }
        if (store != null && store.exists()) create("release") {
            storeFile = store; storePassword = credentials.getProperty("storePassword")
            keyAlias = credentials.getProperty("keyAlias"); keyPassword = credentials.getProperty("keyPassword")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false } }
    buildTypes { release { signingConfig = signingConfigs.findByName("release"); isMinifyEnabled = false } }
    packaging { jniLibs.useLegacyPackaging = true; resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/native-image/**") }
}
dependencies {
    implementation("io.github.tdlibx:td-android:1.8.56-RC5")
    implementation("org.mongodb:mongodb-driver-sync:5.5.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
