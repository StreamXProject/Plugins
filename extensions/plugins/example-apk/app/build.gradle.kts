plugins { id("com.android.application") }
android {
    namespace = "com.music.streamx.extensions.example"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.music.streamx.extensions.example"
        minSdk = 26
        targetSdk = 37
        versionCode = 1 * 10000 + (providers.gradleProperty("extensionBuildNumber").orNull?.toInt() ?: 0)
        versionName = "1.0"
    }
}
