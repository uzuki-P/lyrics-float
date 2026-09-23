import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
                iconFile.set(project.file("src/main/resources/icons/lyrics-float.png"))
            }
        }
    }
}
