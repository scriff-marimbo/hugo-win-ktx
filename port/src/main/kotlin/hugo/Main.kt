package hugo

import hugo.data.DatFiles
import hugo.engine.Engine
import hugo.platform.App
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * Hugo's Horrific Adventure (Hugo for Windows v1.5) on modern desktops.
 * Usage: hugo [-a<game dir> | <game dir>] [-d] [-i] [--version] [--help]
 *   -a / first argument: folder with HUGOWIN1.EXE and the .DAT files (asked for if not found)
 *   -d demo mode (the game ends with the story), -i regenerate HHH00.SAV from the EXE
 */
fun main(args: Array<String>) {
    if (args.any { it == "--version" || it == "-v" }) {
        println("${PortInfo.TITLE} ${PortInfo.VERSION}")
        return
    }
    if (args.any { it == "--help" || it == "-h" }) {
        printHelp()
        return
    }

    // Smooth UI text on Linux too, and the platform's own look (override with -Dswing.defaultlaf=...)
    if (System.getProperty("awt.useSystemAAFontSettings") == null) System.setProperty("awt.useSystemAAFontSettings", "on")
    SwingUtilities.invokeAndWait {
        if (System.getProperty("swing.defaultlaf") == null)
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }
    var dir: File? = null
    var demo = false; var regen = false
    for (a in args) when {
        a.startsWith("-a") && a.length > 2 -> dir = File(a.substring(2))
        a == "-d" -> demo = true
        a == "-i" -> regen = true
        !a.startsWith("-") -> dir = File(a)
    }
    val prefs = File(userDataDir(), "gamedir.txt")
    fun hasGame(d: File?) = d != null && d.isDirectory && runCatching { DatFiles.findFile(d, "HUGOWIN1.EXE") }.isSuccess
    val candidates = listOfNotNull(dir, File("."), File(".", "HWIN_1"), prefs.takeIf { it.isFile }?.readText()?.trim()?.let { File(it) })
    var gameDir = candidates.firstOrNull { hasGame(it) }
    if (gameDir == null) {
        var picked: File? = null
        SwingUtilities.invokeAndWait {
            val fc = JFileChooser().apply {
                dialogTitle = "Where is Hugo's Horrific Adventure (HUGOWIN1.EXE)?"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            }
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picked = fc.selectedFile
        }
        gameDir = picked
        if (!hasGame(gameDir)) {
            showError("HUGOWIN1.EXE and its .DAT files were not found.")
            exitProcess(1)
        }
    }
    gameDir = gameDir!!.absoluteFile
    runCatching { prefs.parentFile.mkdirs(); prefs.writeText(gameDir.path) }

    // Saves and CONFIG.DAT go next to the game, as in the original, unless that folder is read-only
    val saveDir = if (gameDir.canWrite()) gameDir else File(userDataDir(), "saves").apply { mkdirs() }
    if (saveDir != gameDir) {
        val src = runCatching { DatFiles.findFile(gameDir, "HHH00.SAV") }.getOrNull()
        val dst = File(saveDir, "HHH00.SAV")
        if (src != null && !dst.exists()) src.copyTo(dst)
    }
    try {
        App(gameDir, saveDir, Engine.Options(demo = demo, regenerateSave = regen)).run()
    } catch (t: Throwable) {
        t.printStackTrace()
        showError(t.message ?: t.toString())
        exitProcess(1)
    }
    exitProcess(0)
}

private fun printHelp() {
    println(
        """
        ${PortInfo.TITLE} ${PortInfo.VERSION}
        Runs the original Hugo for Windows v1.5 game data unchanged.

        Usage: java -jar hugo-port-${PortInfo.VERSION}-all.jar [game folder] [options]

          game folder       folder containing HUGOWIN1.EXE and the .DAT files
                            (a folder picker asks for it if it isn't found)
          -a<game folder>   the same, in the original's switch style
          -d                demo mode (the game ends with the story)
          -i                regenerate HHH00.SAV from the EXE tables
          -v, --version     print the port version and exit
          -h, --help        show this help and exit

        Requires Java 17 or newer. Saves are byte-compatible with the original.
        """.trimIndent()
    )
}

private fun showError(text: String) = runCatching {
    SwingUtilities.invokeAndWait { JOptionPane.showMessageDialog(null, text, "Hugo", JOptionPane.ERROR_MESSAGE) }
}

fun userDataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    return when {
        os.contains("win") -> File(System.getenv("APPDATA") ?: home, "HugoWin")
        os.contains("mac") -> File(home, "Library/Application Support/HugoWin")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "hugowin")
    }
}
