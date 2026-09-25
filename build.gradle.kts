import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.compose") version "1.11.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
}

group = "dev.lyricsfloat"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.components:components-resources:1.11.0")
    implementation("org.jetbrains.compose.material3:material3:1.9.0-alpha04")

    // MPRIS (song detection) and the StatusNotifierItem tray both run over the session bus.
    implementation("com.github.hypfvieh:dbus-java-core:5.2.1")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.1")

    // LRCLIB client.
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Provides Dispatchers.Main (EDT) on desktop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // JNA talks to Xlib so the WM can run undecorated-window drags natively
    // (_NET_WM_MOVERESIZE); setLocation dragging jitters under XWayland.
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    // Offline Japanese tokenization for romaji lyrics.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    testImplementation(kotlin("test"))
}

compose {
    resources {
        packageOfResClass = "dev.lyricsfloat.resources"
    }
}

compose.desktop {
    application {
        mainClass = "dev.lyricsfloat.MainKt"

        nativeDistributions {
            modules("jdk.security.auth")
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "lyrics-float"
            packageVersion = "0.1.0"
            description = "Floating synced lyrics for any MPRIS player on Linux"
            copyright = "Copyright © 2026 Uzuki-P"

            linux {
                iconFile.set(project.file("src/main/resources/icons/lyrics-float-v2.png"))
            }
        }
    }
}

// Compose's packageAppImage task creates a jpackage app directory. Wrap that
// directory with appimagetool to produce the portable .AppImage download.
tasks.register("packageAppImageFile") {
    dependsOn("packageAppImage")
    val appBundle = layout.buildDirectory.dir("compose/binaries/main/app/lyrics-float")
    val appDir = layout.buildDirectory.dir("compose/binaries/main/appimage/lyrics-float.AppDir")
    val outputDir = layout.buildDirectory.dir("compose/binaries/main/appimage")
    val toolFile = File(System.getProperty("user.home"), ".cache/lyrics-float/tools/appimagetool-x86_64.AppImage")
    val iconFile = layout.projectDirectory.file("src/main/resources/icons/lyrics-float-v2.png")
    val appVersion = project.version.toString()
    val outputFile = outputDir.get().file("lyrics-float-$appVersion.AppImage")

    // Track the jpackage bundle so repeat runs (just install/update) skip the
    // appimagetool repackage when nothing was rebuilt.
    inputs.dir(appBundle)
    outputs.file(outputFile)

    doLast {
        if (!toolFile.exists()) {
            toolFile.parentFile.mkdirs()
            URI("https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage")
                .toURL().openStream().use { input -> toolFile.outputStream().use(input::copyTo) }
            toolFile.setExecutable(true)
        }

        val directory = appDir.get().asFile
        if (directory.exists()) check(directory.deleteRecursively()) { "Could not clear $directory" }
        val usr = File(directory, "usr")
        check(usr.mkdirs()) { "Could not create $usr" }
        val copy = ProcessBuilder("cp", "-a", "${appBundle.get().asFile.absolutePath}/.", usr.absolutePath)
            .inheritIO().start()
        check(copy.waitFor() == 0) { "Could not copy the app bundle into $usr" }

        File(directory, "AppRun").apply {
            writeText("#!/bin/sh\nHERE=\"\$(dirname \"\$(readlink -f \"\$0\")\")\"\nexec \"\$HERE/usr/bin/lyrics-float\" \"\$@\"\n")
            setExecutable(true)
        }
        File(directory, "lyrics-float.desktop").writeText(
            "[Desktop Entry]\nType=Application\nName=Lyrics Float\nComment=Floating synced lyrics\nExec=lyrics-float\nIcon=lyrics-float\nCategories=AudioVideo;Audio;\nTerminal=false\n",
        )
        iconFile.asFile.copyTo(File(directory, "lyrics-float.png"), overwrite = true)

        val output = outputFile.asFile
        output.parentFile.mkdirs()
        if (output.exists()) check(output.delete()) { "Could not replace $output" }
        val packageProcess = ProcessBuilder(
            toolFile.absolutePath,
            "--appimage-extract-and-run",
            "--no-appstream",
            directory.absolutePath,
            output.absolutePath,
        ).apply {
            environment()["ARCH"] = "x86_64"
            inheritIO()
        }.start()
        check(packageProcess.waitFor() == 0) { "appimagetool failed" }
        println("AppImage written to ${output.absolutePath}")
    }
}

// Keep the build output in build/ and stage a dated copy for sharing.
tasks.register("stageAppImage") {
    dependsOn("packageAppImageFile")
    val source = layout.buildDirectory.file("compose/binaries/main/appimage/lyrics-float-${project.version}.AppImage")
    val stagedDir = layout.projectDirectory.dir("_apk")
    doLast {
        val artifact = source.get().asFile
        check(artifact.isFile) { "Missing AppImage: $artifact" }
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("dd-MMM_HH-mm", Locale.ROOT)).lowercase(Locale.ROOT)
        val staged = stagedDir.file("${artifact.nameWithoutExtension}_$timestamp.AppImage").asFile
        staged.parentFile.mkdirs()
        artifact.copyTo(staged, overwrite = true)
        staged.setExecutable(artifact.canExecute())
        println("Staged artifact: ${staged.absolutePath}")
    }
}
