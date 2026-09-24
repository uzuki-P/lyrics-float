package dev.lyricsfloat.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Registers the installed launcher or AppImage for XDG session autostart. */
object Autostart {
    private val entry: File by lazy {
        val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)
            ?: System.getProperty("user.home") + "/.config"
        File(configHome, "autostart/lyrics-float.desktop")
    }

    fun isEnabled(): Boolean = entry.isFile

    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (enabled) {
            val executable = executablePath() ?: return false
            writeEntry(executable)
            true
        } else {
            !entry.exists() || entry.delete()
        }
    }.getOrDefault(false)

    fun refresh() {
        if (isEnabled()) executablePath()?.let { runCatching { writeEntry(it) } }
    }

    private fun executablePath(): String? =
        (System.getenv("APPIMAGE")?.takeIf(String::isNotBlank)
            ?: System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank))
            ?.let { File(it).absolutePath }

    private fun writeEntry(executable: String) {
        check(entry.parentFile.isDirectory || entry.parentFile.mkdirs())
        // Desktop Entry quoting uses double quotes and backslash escapes for Exec arguments.
        val quoted = executable.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("$", "\\$").replace("`", "\\`")
        val temporary = File.createTempFile("lyrics-float-", ".tmp", entry.parentFile)
        try {
            temporary.writeText("""[Desktop Entry]
Type=Application
Name=Lyrics Float
Exec="$quoted"
Terminal=false
Categories=AudioVideo;Audio;
X-GNOME-Autostart-enabled=true
""")
            try {
                Files.move(temporary.toPath(), entry.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), entry.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
