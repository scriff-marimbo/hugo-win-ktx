package hugo.engine

import hugo.data.DatFiles
import hugo.data.Exe
import hugo.data.GameTables
import hugo.data.Str
import hugo.engine.Display.Companion.VIEW_H
import hugo.engine.Display.Companion.VIEW_Y
import hugo.engine.Display.Companion.XPIX
import hugo.engine.Display.Companion.YPIX
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Game state and the main loop (MainLoopTick @1010:6d6e). Everything here runs on one game
 * thread; the platform talks to it through [post] and the [Host] callbacks.
 */
class Engine(val gameDir: File, val host: Host, val saveDir: File = gameDir, val options: Options = Options()) {
    class Options(val demo: Boolean = false, val regenerateSave: Boolean = false)

    val exeFile: File = DatFiles.findFile(gameDir, "HUGOWIN1.EXE")
    val exe: Exe = Exe.load(exeFile)
    val tables = GameTables(exe)
    val dat = DatFiles(gameDir)
    val config = Config()

    val objects: Array<GameObject> = Array(GameTables.NUM_OBJECTS) { i ->
        GameObject(i).also { it.load(tables.initialObjectRecord(i)) }
    }
    val hero: GameObject get() = objects[0]

    val display = Display(this)
    val objs = Objects(this)
    val scheduler = Scheduler(this)
    val parser = Parser(this)
    val mouse = Mouse(this)
    val inventory = Inventory(this)
    val route = Route(this)
    val sound = Sound(this)
    val saves = SaveGame(this)
    val maze = Maze()

    // ---------------------------------------------------------------- status (DGROUP)
    /** *screenPtr: the hero's screen. */
    var screen: Int
        get() = hero.screen
        set(v) { hero.screen = v }
    var tick = 0L                      // 68b4 scheduler ticks
    var lastSaveTick = 0L              // 68b8
    var machineState = 0               // 68bc
    var inventoryState = Inventory.OFF // 68be
    var objectInHand = -1              // 68c4
    var routeIndex = -1                // 68c2
    var heroImage = 0                  // 371c
    var score = 0                      // 3720-ish
    var maxScore = 0                   // 371e
    var storyModeFl = 0
    var jumpExitFl = 0
    var gameOverFl = 0
    var godMode = false
    var recallFl = false               // 68a6
    var lookFl = false                 // 68a4
    var demoFl = options.demo
    var saveSlot = 0
    val screenStates = IntArray(17)
    val pointsScored = BooleanArray(tables.numPoints)
    var registered = false
    var exitText = ""
    var distributor = ""
    @Volatile var leftClick = false
    @Volatile var rightClick = false

    val ticksPerSecond: Int get() = if (config.turbo) 16 else 9

    /** Status bar contents (CMainFrame::SetStatusText / UpdateStatusBar). */
    @Volatile var statusPrompt = ""
    @Volatile var statusScore = ""

    // ---------------------------------------------------------------- helpers
    fun str(addr: Int) = exe.str(addr)
    fun fmt(addr: Int, vararg args: Any) = cFormat(exe.str(addr), *args)
    fun box(type: Int, text: String): String? = host.box(type, text)

    // ---------------------------------------------------------------- start-up
    /** InitInstance -> InitConfig(0), InitMachine; OnCreate -> InitGame; InitConfig(2). */
    fun start() {
        // InitConfig(0)
        tables.defaultTunes.forEach { if (it in 0 until 16) config.playlist[it] = true }
        val boot = dat.bootInfo()
        registered = boot.registered
        exitText = boot.exitText
        distributor = boot.distributor
        config.read(configFile())
        // InitMachine
        sound.init()
        scheduler.initEventQueue()
        display.resetPal()
        calcMaxScore()
        Crt.srand(42)
        // InitGame
        readBackground(16)
        display.back.copyInto(display.front)
        objs.readObjectImages()
        loadIcons()
        host.frameReady()
        host.about()
        // InitConfig(2)
        for (i in 0 until 16) if (config.playlist[i]) { sound.playMusic(i); break }
        saves.initSaveState(options.regenerateSave)
    }

    fun configFile() = File(saveDir, "CONFIG.DAT")

    private fun calcMaxScore() {
        maxScore = objects.sumOf { it.value } + (0 until tables.numPoints).sumOf { tables.pointScores[it] }
    }

    private fun loadIcons() {
        val p = dat.icons()
        blit(p, display.gui)
    }

    private fun blit(p: hugo.data.Pcx, dst: ByteArray) {
        val w = minOf(p.width, XPIX)
        for (y in 0 until minOf(p.lines, YPIX)) System.arraycopy(p.pixels, y * p.width, dst, y * XPIX, w)
    }

    /** ReadBackground @1010:2eb2 (into the clean background buffer). */
    fun readBackground(screen: Int) = blit(dat.background(screen), display.back)

    /** ReadScreenFiles @1010:3e70. */
    fun readScreenFiles(screen: Int) {
        readBackground(screen)
        display.back.copyInto(display.front)
        dat.overlay(screen, 0).copyInto(objs.boundary)
        dat.overlay(screen, 1).copyInto(objs.overlay)
        dat.overlay(screen, 2).copyInto(objs.ovlBase)
    }

    /** SetCarriedScreen @1010:51b6. */
    fun setCarriedScreen(s: Int) {
        screen = s
        for (i in 1 until objects.size) if (objects[i].carried != 0) objects[i].screen = s
    }

    /** InitStatus @1010:3c24. */
    fun initStatus() {
        tick = 0; lastSaveTick = 0
        machineState = 0; inventoryState = Inventory.OFF
        inventory.offset = 0
        objectInHand = -1; routeIndex = -1
    }

    // ---------------------------------------------------------------- main loop
    /** MainLoopTick @1010:6d6e: one tick (the caller does the 9/16 per second limiting). */
    fun tick() {
        if (gameOverFl != 0) return
        when (machineState) {
            0 -> { saves.restoreGame(-1); machineState = 1 }      // IntroInit
            1 -> machineState = 2                                  // IntroText
            2 -> { scheduler.newScreen(0); machineState = 3 }      // IntroPlay
            3 -> {
                parser.charHandler()
                objs.moveObjects()
                scheduler.runScheduler()
                display.displayRestore()
                objs.updateImages()
                mouse.mouseHandler()
                display.displayFlush()
            }
            4 -> inventory.runInventory()
            5 -> { machineState = 0; quitting = true; host.quit() }
        }
    }

    private val inbox = LinkedBlockingQueue<() -> Unit>()
    @Volatile var quitting = false
    /** Pause ticking (window inactive / a platform dialog is open). Modal Boxes block anyway. */
    @Volatile var paused = false

    /** Run [f] on the game thread before the next tick. */
    fun post(f: () -> Unit) { inbox.add(f) }

    /** The game thread body. */
    fun run() {
        start()
        var next = System.nanoTime()
        while (!quitting) {
            while (true) { val f = inbox.poll() ?: break; f() }
            if (quitting) break
            val period = 1_000_000_000L / ticksPerSecond
            val now = System.nanoTime()
            if (paused) { next = now; Thread.sleep(5); continue }
            if (now - next >= 0) {
                tick()
                next += period
                if (now - next > 4 * period) next = now + period
            } else {
                val wait = (next - now) / 1_000_000L
                inbox.poll(maxOf(1L, minOf(wait, 20L)), java.util.concurrent.TimeUnit.MILLISECONDS)?.let { it() }
            }
        }
        sound.shutdown()
        config.write(configFile())
    }

    // ---------------------------------------------------------------- frame output
    /** Compose the visible view (rows 8..191 of the front buffer) as 0xRRGGBB pixels. */
    fun composeView(out: IntArray) {
        val f = display.front; val r = display.remap; val pal = tables.palette
        var o = 0
        for (y in VIEW_Y until VIEW_Y + VIEW_H) {
            val row = y * XPIX
            for (x in 0 until XPIX) out[o++] = pal[r[f[row + x].toInt() and 0xFF] and 15]
        }
    }

    // ---------------------------------------------------------------- status bar
    /** UpdateStatus: the prompt line and the score pane. */
    fun updateStatus() {
        val p = cFormat(str(Str.PROMPT), parser.typed, parser.cursorChar)
        val s = fmt(Str.SCORE, score, maxScore)
        if (p != statusPrompt || s != statusScore) {
            statusPrompt = p; statusScore = s
            host.statusChanged()
        }
    }

    // ---------------------------------------------------------------- maze
    /** ProcessMaze @1010:46f0: walking off a maze screen edge moves to the neighbour. */
    fun processMaze() {
        val h = hero
        val x1 = h.img.x1 + h.x; val x2 = h.img.x2 + h.x
        val y1 = h.img.y1 + h.y; val y2 = h.img.y2 + h.y
        val m = maze
        when {
            x1 < m.x1 -> {
                exe.setW(MAZE_SCREEN, screen - 1)
                exe.setW(MAZE_X, x1 - x2 + m.x2 - 8)
                exe.setW(MAZE_Y, h.y)
            }
            x2 > m.x2 -> {
                exe.setW(MAZE_SCREEN, screen + 1)
                exe.setW(MAZE_X, m.x1 + 8)
                exe.setW(MAZE_Y, h.y)
            }
            y1 < m.y1 - 8 -> {
                exe.setW(MAZE_SCREEN, screen - m.size)
                exe.setW(MAZE_X, m.x3)
                exe.setW(MAZE_Y, y1 - y2 + m.y2 - 8)
            }
            y2 > m.y2 - 4 -> {
                exe.setW(MAZE_SCREEN, screen + m.size)
                exe.setW(MAZE_X, m.x4)
                exe.setW(MAZE_Y, m.y1 + 8)
            }
            else -> return
        }
        routeIndex = -1
        for (a in MAZE_ACTIONS) scheduler.insertAction(a)
    }

    // ---------------------------------------------------------------- objects
    /** FindBackgroundVerb @1010:4eaa: first non-look background verb for this noun here. */
    private fun findBackgroundVerb(nounStr: Int): String? {
        val list = tables.screenBackground.getOrNull(screen) ?: return null
        for (b in list) {
            if (exe.w(b.nounAddr) == nounStr && b.verb.addr != GameTables.VERB_LOOK &&
                (b.roomState == -1 || b.roomState == screenStates[screen])) return b.verb.first
        }
        return null
    }

    private fun firstCmdVerb(o: GameObject): String =
        tables.cmds(o.cmdIndex).firstOrNull()?.verb?.first ?: ""

    /** UseObject @1010:4f12 (right click). */
    fun useObject(objId: Int) {
        val o = objects[objId]
        val noun = tables.words(o.nounPtr).first
        if (objectInHand == -1) {
            val verb = when {
                (o.genericCmd and Generic.TAKE) != 0 || o.value != 0 -> tables.take.first
                o.cmdIndex != 0 -> firstCmdVerb(o)
                else -> findBackgroundVerb(exe.w(o.nounPtr)) ?: return
            }
            parser.line = "$verb $noun"
        } else {
            val inHand = objects[objectInHand]
            val handNoun = tables.words(inHand.nounPtr).first
            parser.line = "${firstCmdVerb(inHand)} $handNoun $noun"
            for (u in tables.uses) {
                if (u.obj != objectInHand) continue
                var found = false
                for ((tn, tv) in u.targets) {
                    if (exe.w(tn.addr) == exe.w(o.nounPtr)) { found = true; parser.line = "${tv.first} $handNoun $noun" }
                }
                if (!found) {
                    if (inventoryState != Inventory.ACTIVE) objectInHand = -1
                    box(0, u.textNoTarget ?: "")
                    return
                }
            }
        }
        if (inventoryState == Inventory.ACTIVE) inventoryState = Inventory.UP
        objectInHand = -1
        parser.lineHandler()
    }

    /** LookObject @1010:5092 (left click). */
    fun lookObject(o0: GameObject) {
        val o = if (o0 === hero) objects[heroImage] else o0
        parser.command("${tables.look.first} ${tables.words(o.nounPtr).first}")
    }

    // ---------------------------------------------------------------- end of game
    /** GameOver @1010:537c. */
    fun endGame() {
        if (!registered) box(0, str(Str.ORDER))
        box(0, fmt(Str.TWO_LINES, str(Str.TITLE), str(Str.COPYRIGHT)))
        machineState = 5
    }

    /** GameOverMsg @1010:6372. */
    fun gameOverMsg() { host.message(str(Str.CAREFUL_TITLE), str(Str.GAME_OVER)) }

    // ---------------------------------------------------------------- menu commands (game thread)
    fun newGame() { saves.restoreGame(-1); machineState = 1 }

    /** DoRestore @1010:12fe. */
    fun restore(slot: Int) {
        saveSlot = slot
        saves.restoreGame(slot)
        scheduler.restoreScreen(screen)
        machineState = 3
        updateStatus()
    }

    /** DoSave @1010:1322 (OnFileSave refuses after game over). */
    fun save(slot: Int, description: String) {
        if (gameOverFl != 0) { gameOverMsg(); return }
        saveSlot = slot
        saves.saveGame(slot, description)
        lastSaveTick = tick
    }

    fun recall() { recallFl = true }
    fun look() { lookFl = true }

    /** ShowInventory @1010:1050. */
    fun showInventory() {
        if (gameOverFl != 0) { gameOverMsg(); return }
        if (inventoryState == Inventory.OFF) {
            if (objects.none { it.carried != 0 }) box(0, str(Str.BAG_EMPTY))
            else { inventoryState = Inventory.DOWN; machineState = 4 }
        } else if (inventoryState == Inventory.ACTIVE) {
            inventoryState = Inventory.UP; machineState = 4
        }
    }

    /** CMainFrame::OnClose. Returns true when the program should end. */
    fun confirmClose(): Boolean {
        if (tick - lastSaveTick > 30L * ticksPerSecond &&
            !host.message(str(Str.TITLE), str(Str.NOT_SAVED), okCancel = true)) return false
        quitting = true
        return true
    }

    companion object {
        const val MAZE_SCREEN = 0x3754
        const val MAZE_X = 0x375c
        const val MAZE_Y = 0x375e
        val MAZE_ACTIONS = intArrayOf(0x3756, 0x3760, 0x376a, 0x3750)

        /** Minimal C printf for the game's own format strings (%s %d %c). */
        fun cFormat(f: String, vararg args: Any): String {
            val sb = StringBuilder()
            var a = 0
            var i = 0
            while (i < f.length) {
                val c = f[i]
                if (c == '%' && i + 1 < f.length) {
                    when (val k = f[i + 1]) {
                        's', 'd', 'c' -> { sb.append(args.getOrNull(a++)?.toString() ?: ""); i += 2; continue }
                        '%' -> { sb.append('%'); i += 2; continue }
                        else -> { sb.append(c); sb.append(k); i += 2; continue }
                    }
                }
                sb.append(c); i++
            }
            return sb.toString()
        }
    }
}
