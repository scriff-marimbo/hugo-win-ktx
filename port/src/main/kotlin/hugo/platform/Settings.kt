package hugo.platform

import java.io.File
import java.util.Properties

/** The port's own options (not in CONFIG.DAT): stored as hugo-port.properties next to the saves. */
class PortSettings(private val file: File) {
    enum class Scale(val label: String) { STRETCH("Stretch to window (original)"), ASPECT("Keep aspect ratio"), INTEGER("Whole multiples only") }

    var scale = Scale.ASPECT
    var smooth = false
    var fullscreen = false
    var soundFont = ""

    fun load(): PortSettings {
        if (!file.isFile) return this
        val p = Properties()
        try { file.inputStream().use { p.load(it) } } catch (_: Exception) { return this }
        scale = runCatching { Scale.valueOf(p.getProperty("scale", scale.name)) }.getOrDefault(scale)
        smooth = p.getProperty("smooth", "false").toBoolean()
        fullscreen = p.getProperty("fullscreen", "false").toBoolean()
        soundFont = p.getProperty("soundfont", "")
        return this
    }

    fun save() {
        val p = Properties()
        p.setProperty("scale", scale.name)
        p.setProperty("smooth", smooth.toString())
        p.setProperty("fullscreen", fullscreen.toString())
        p.setProperty("soundfont", soundFont)
        try { file.outputStream().use { p.store(it, "Hugo's Horrific Adventure - Settings") } } catch (_: Exception) {}
    }
}
