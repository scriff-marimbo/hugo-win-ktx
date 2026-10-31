package hugo

import hugo.engine.Engine
import hugo.engine.HeadlessHost
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Scripted run without a window, for testing:
 *   java -cp ... hugo.HeadlessKt <gameDir> <script> [outDir]
 * Script lines:  tick N | type TEXT | key VK | click X Y | rclick X Y | move X Y |
 *                answer TEXT | shot NAME | inv | status | log | save SLOT DESC | load SLOT | new
 */
fun main(args: Array<String>) {
    val gameDir = File(args[0])
    val script = File(args[1]).readLines()
    val out = File(args.getOrElse(2) { "." }).apply { mkdirs() }
    val saveDir = File(out, "saves").apply { mkdirs() }
    // Seed the save dir with the shipped HHH00.SAV so the test uses the original new-game state
    gameDir.listFiles()?.firstOrNull { it.name.equals("HHH00.SAV", true) }?.copyTo(File(saveDir, "HHH00.SAV"), true)
    val host = HeadlessHost()
    val e = Engine(gameDir, host, saveDir)
    e.start()
    var printed = 0
    fun flushLog() { while (printed < host.log.size) println("  | " + host.log[printed++].replace("\n", "\\n")) }
    val view = IntArray(320 * 184)
    for (raw in script) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val parts = line.split(" ", limit = 2)
        val arg = parts.getOrElse(1) { "" }
        println("> $line")
        when (parts[0]) {
            "tick" -> repeat(arg.toInt()) { e.tick() }
            "type" -> { arg.forEach { e.parser.charTyped(it.code) }; e.parser.charTyped(13) }
            "key" -> e.parser.keyDown(arg.toInt(16), false)
            "move", "click", "rclick" -> {
                val (x, y) = arg.split(" ").map { it.toInt() }
                e.mouse.x = x; e.mouse.y = y
                if (parts[0] == "click") e.leftClick = true
                if (parts[0] == "rclick") e.rightClick = true
            }
            "answer" -> host.answers.addLast(arg)
            "inv" -> e.showInventory()
            "save" -> { val (s, d) = arg.split(" ", limit = 2); e.save(s.toInt(), d) }
            "load" -> e.restore(arg.toInt())
            "new" -> e.newGame()
            "status" -> println("  screen=${e.screen} state=${e.machineState} tick=${e.tick} ${e.statusScore} hero=(${e.hero.x},${e.hero.y}) prompt='${e.statusPrompt}'")
            "shot" -> {
                e.composeView(view)
                val img = BufferedImage(320, 184, BufferedImage.TYPE_INT_RGB)
                img.setRGB(0, 0, 320, 184, view, 0, 320)
                val big = BufferedImage(640, 368, BufferedImage.TYPE_INT_RGB)
                big.createGraphics().apply { drawImage(img, 0, 0, 640, 368, null); dispose() }
                ImageIO.write(big, "png", File(out, "$arg.png"))
            }
            else -> println("  ?? unknown")
        }
        flushLog()
    }
}
