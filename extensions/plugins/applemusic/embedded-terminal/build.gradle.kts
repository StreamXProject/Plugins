plugins { id("com.android.library") }
val upstream = file("upstream")
val terminalSources = layout.buildDirectory.dir("generated/terminalJava")
val prepareTerminalSources by tasks.registering {
    inputs.dir(upstream.resolve("terminal-view/src/main/java"))
    inputs.dir(upstream.resolve("terminal-emulator/src/main/java"))
    outputs.dir(terminalSources)
    doLast {
        val output = terminalSources.get().asFile
        project.copy { from(upstream.resolve("terminal-view/src/main/java"), upstream.resolve("terminal-emulator/src/main/java")); into(output) }
        val session = output.resolve("com/termux/terminal/TerminalSession.java")
        var source = session.readText()
        val start = source.indexOf("    private static FileDescriptor wrapFileDescriptor(")
        val end = source.indexOf("    class MainThreadHandler", start)
        check(start >= 0 && end > start) { "Review Termux TerminalSession compatibility patch after upstream update" }
        source = source.substring(0, start) + """
    private android.os.ParcelFileDescriptor ptyOwner;
    private FileDescriptor wrapFileDescriptor(int fd, TerminalSessionClient client) {
        ptyOwner = android.os.ParcelFileDescriptor.adoptFd(fd);
        return ptyOwner.getFileDescriptor();
    }

""" + source.substring(end)
        source = source.replace("new FileInputStream(terminalFileDescriptorWrapped)", "new android.os.ParcelFileDescriptor.AutoCloseInputStream(android.os.ParcelFileDescriptor.dup(terminalFileDescriptorWrapped))")
            .replace("new FileOutputStream(terminalFileDescriptorWrapped)", "new android.os.ParcelFileDescriptor.AutoCloseOutputStream(android.os.ParcelFileDescriptor.dup(terminalFileDescriptorWrapped))")
            .replace("JNI.close(mTerminalFileDescriptor);", "try { if (ptyOwner != null) ptyOwner.close(); } catch (IOException ignored) {}")
        session.writeText(source)
    }
}
tasks.named("preBuild").configure { dependsOn(prepareTerminalSources) }
android {
    namespace = "com.termux.view"
    compileSdk = 37
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { ndkBuild { arguments += "APP_LDFLAGS=-Wl,-z,max-page-size=16384" } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    sourceSets["main"].apply {
        java.srcDir(terminalSources)
        res.srcDirs(upstream.resolve("terminal-view/src/main/res"))
    }
    externalNativeBuild { ndkBuild { path = upstream.resolve("terminal-emulator/src/main/jni/Android.mk") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("androidx.annotation:annotation:1.9.0") }
