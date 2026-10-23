buildscript { repositories { google(); mavenCentral() }; dependencies { classpath("com.android.tools:r8:9.1.56") } }
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}
