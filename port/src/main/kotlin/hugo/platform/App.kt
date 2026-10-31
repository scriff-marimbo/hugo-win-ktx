package hugo.platform

import hugo.PortInfo
import hugo.data.Resources
import hugo.data.Str
import hugo.engine.Engine
import hugo.engine.Host
import hugo.engine.Parser.VK
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.GridLayout
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.ImageIcon
import javax.swing.JCheckBox
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.border.BevelBorder
import javax.swing.event.MenuEvent
import javax.swing.event.MenuListener
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The desktop front end: a Swing window with the game view, a menu bar, the original's toolbar
 * and a status bar, Swing dialogs for the original's modal boxes, OpenAL effects and Java MIDI
 * music. The engine runs on its own thread; the UI lives on the Swing event thread.
 */
class App(private val gameDir: File, private val saveDir: File, private val options: Engine.Options) {
    private lateinit var engine: Engine
    private val settings = PortSettings(File(saveDir, "hugo-port.properties")).load()
    private lateinit var frame: JFrame
    private lateinit var view: GameView
    private lateinit var toolbar: ToolBar
    private val promptLabel = JLabel(" ")
    private val scoreLabel = JLabel(" ")
    private val effects = Effects()
    private var music: Music? = null
    private lateinit var title: String
    private var gameThread: Thread? = null
    @Volatile private var gameEnded = false
    private val done = CountDownLatch(1)
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    private var openDialogs = 0           // EDT only
    private var fullscreen = false
    private var windowedBounds: Rectangle? = null
    private val keysDown = HashSet<Int>()
    private var enterHeld = false
    private val menuChecks = ArrayList<Pair<JCheckBoxMenuItem, () -> Boolean>>()

    // =================================================================== host
    private inner class UiHost : Host {
        override fun box(type: Int, text: String): String? = when (type) {
            2 -> ask(null) { Dialogs.prompt(frame, title, text) }
            3 -> ask(null) { if (Dialogs.yesNo(frame, title, text)) "yes" else null }
            1 -> ask("ok", always = true) { Dialogs.message(frame, engine.str(Str.WARNING_TITLE), text); "ok" }
            else -> ask("ok", always = true) { Dialogs.message(frame, title, text); "ok" }
        }

        override fun message(title: String, text: String, okCancel: Boolean): Boolean =
            if (okCancel) ask(false) { Dialogs.okCancel(frame, title, text) }
            else ask(true, always = true) { Dialogs.message(frame, title, text); true }

        override fun warn(text: String) { message(engine.str(Str.WARNING_TITLE), text) }

        override fun frameReady() { view.submit { engine.composeView(it) } }
        override fun statusChanged() { SwingUtilities.invokeLater { updateStatus() } }

        override fun playSound(pcm: ByteArray, volumePercent: Int) = effects.play(pcm, volumePercent)
        override fun isSoundPlaying() = effects.isPlaying()
        override fun stopSound() = effects.stop()
        override fun playMusic(midi: ByteArray, volumePercent: Int) { music?.play(midi, volumePercent) }
        override fun stopMusic() { music?.stop() }
        override fun setMusicVolume(volumePercent: Int) { music?.setVolume(volumePercent) }
        override fun beep() { if (engine.config.soundOn) effects.beep(engine.config.soundVol) }
        override fun about() { ask(Unit, always = true) { aboutDialog() } }
        override fun quit() { gameEnded = true }
    }

    /** Run a dialog on the Swing thread from the game thread and wait for its answer. */
    private fun <T> ask(default: T, always: Boolean = false, f: () -> T): T {
        if (gameEnded && !always) return default
        val fut = CompletableFuture<T>()
        pending += fut
        SwingUtilities.invokeLater {
            if (fut.isDone) return@invokeLater
            fut.complete(try { f() } catch (t: Throwable) { t.printStackTrace(); default })
        }
        return try { fut.get() } catch (e: Exception) { default } finally { pending -= fut }
    }

    // =================================================================== run
    fun run() {
        engine = Engine(gameDir, UiHost(), saveDir, options)
        title = engine.str(Str.TITLE)
        engine.config.read(engine.configFile())       // for the window size; start() reads it again

        SwingUtilities.invokeAndWait { buildUi() }

        effects.open()
        music = Music(settings.soundFont.takeIf { it.isNotBlank() }?.let { File(it) }) {
            engine.post { engine.sound.playNextTune() }
        }

        gameThread = Thread(null, {
            try { engine.run() }
            catch (t: Throwable) {
                t.printStackTrace()
                ask(Unit, always = true) { Dialogs.message(frame, "HugoWin Error", "An error has occurred.\n\n${t.message ?: t.javaClass.simpleName}") }
            } finally {
                gameEnded = true
                SwingUtilities.invokeLater { shutdown() }
            }
        }, "hugo-game", 256L shl 20).apply { isDaemon = true; start() }

        System.getProperty("hugo.testResize")?.split("x")?.let { (tw, th) ->   // test hook
            Thread { Thread.sleep(8000); SwingUtilities.invokeLater { frame.setSize(tw.toInt(), th.toInt()) } }.apply { isDaemon = true }.start()
        }
        done.await()
    }

    private fun requestClose() {
        if (gameEnded) return
        engine.post { engine.confirmClose() }
    }

    private fun shutdown() {
        for (f in pending) (f as CompletableFuture<Any?>).complete(null)
        settings.save()
        music?.close()
        effects.close()
        frame.dispose()
        done.countDown()
    }

    // =================================================================== window
    private fun buildUi() {
        // Never drop a menu-closing click through to the game
        UIManager.put("PopupMenu.consumeEventOnClose", true)
        Dialogs.onModal = { open ->
            openDialogs += if (open) 1 else -1
            if (open) { engine.mouse.x = -1; engine.mouse.y = -1; keysDown.clear() }
            else if (openDialogs == 0) view.requestFocusInWindow()        // keys back to the game
            for (i in 0 until frame.jMenuBar.menuCount) frame.jMenuBar.getMenu(i)?.isEnabled = openDialogs == 0
            updatePause()
        }

        frame = JFrame(title)
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        view = GameView(settings.scale, settings.smooth)
        toolbar = ToolBar(loadResourceImages(), tools)
        frame.jMenuBar = buildMenuBar()

        val status = JPanel(BorderLayout(4, 0))
        status.border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        fun pane(l: JLabel) = l.apply {
            border = BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED),
                BorderFactory.createEmptyBorder(1, 5, 1, 5))
        }
        status.add(pane(promptLabel), BorderLayout.CENTER)
        status.add(pane(scoreLabel), BorderLayout.EAST)

        val content = JPanel(BorderLayout())
        content.add(toolbar, BorderLayout.NORTH)
        content.add(view, BorderLayout.CENTER)
        content.add(status, BorderLayout.SOUTH)
        frame.contentPane = content
        installKeys()
        installMouse()

        // Initial size: CONFIG.DAT's client size is the view; the bars come on top
        val cfg = engine.config
        val vw = if (cfg.viewWidth in 160..8000) cfg.viewWidth else 640
        view.preferredSize = Dimension(vw, (vw * 184 + 160) / 320)
        updateStatus()
        frame.pack()
        frame.minimumSize = Dimension(frame.width - view.width + 320, frame.height - view.height + 184)
        if (cfg.winX > -10000 && cfg.winY > -10000 && cfg.winX != -1) frame.setLocation(cfg.winX, cfg.winY)
        else frame.setLocationRelativeTo(null)
        lastView = view.size

        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = requestClose()
        })
        frame.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) { if (engine.config.bgSound == 0) music?.pause(false) }
            override fun windowLostFocus(e: WindowEvent) {
                keysDown.clear()
                // Losing focus to one of our own dialogs is not "inactive"
                if (engine.config.bgSound == 0 && e.oppositeWindow?.owner !== frame) music?.pause(true)
            }
        })
        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) {
                if (!fullscreen && frame.isShowing) { engine.config.winX = frame.x; engine.config.winY = frame.y }
            }
            override fun componentResized(e: ComponentEvent) { aspectTimer.restart() }
        })
        MenuSelectionManager.defaultManager().addChangeListener { updatePause() }

        frame.isVisible = true
        view.requestFocusInWindow()
        if (settings.fullscreen) setFullscreen(true)

        // Toolbar check states and the status bar follow the engine
        Timer(100) { toolbar.repaint(); updateStatus() }.start()
    }

    private fun updatePause() {
        val menuOpen = MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()
        engine.paused = menuOpen || openDialogs > 0
    }

    private fun updateStatus() {
        promptLabel.text = engine.statusPrompt.ifEmpty { " " }
        scoreLabel.text = engine.statusScore.ifEmpty { " " }
    }

    private fun bitmap(img: Resources.Image): BufferedImage =
        BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, img.width, img.height, img.argb, 0, img.width) }

    /** Toolbar bitmap, window icon and the Box dialog's check / X pictures, all from the EXE. */
    private fun loadResourceImages(): List<BufferedImage> {
        val res = try { Resources(engine.exeFile.readBytes()) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<BufferedImage>()
        runCatching {
            res.toolbar()?.let { strip ->
                val b = bitmap(strip)
                for (i in 0 until strip.width / 16) out += b.getSubimage(i * 16, 0, 16, strip.height)
            }
            res.icon(6)?.let { Dialogs.checkIcon = ImageIcon(bitmap(it)) }
            res.icon(4)?.let { Dialogs.crossIcon = ImageIcon(bitmap(it)) }
            res.icon(1)?.let { frame.iconImage = bitmap(it) }
        }.onFailure { System.err.println("resources: ${it.message}") }
        return out
    }

    private val tools by lazy {
        val c = engine.config
        listOf(
            ToolBar.Tool(0, "Help contents", false, { false }) { cmdHelp() },
            ToolBar.Tool(1, "Music on/off", true, { c.musicOn }) { cmdMusic() },
            ToolBar.Tool(2, "Sound on/off", false, { c.soundOn }) { cmdSound() },
            ToolBar.Tool(3, "Save game", true, { false }) { cmdSave() },
            ToolBar.Tool(4, "Load game", false, { false }) { cmdLoad() },
            ToolBar.Tool(5, "Recall last command", true, { false }) { cmdRecall() },
            ToolBar.Tool(6, "Turbo on/off", false, { c.turbo }) { cmdTurbo() },
            ToolBar.Tool(7, "Look around", true, { false }) { cmdLook() },
            ToolBar.Tool(8, "Inventory", false, { false }) { cmdInventory() },
        )
    }

    // =================================================================== menus
    private fun buildMenuBar(): JMenuBar {
        val bar = JMenuBar()
        val cfg = engine.config
        fun key(code: Int, mods: Int = 0): KeyStroke = KeyStroke.getKeyStroke(code, mods)
        val ctrl = InputEvent.CTRL_DOWN_MASK
        fun JMenu.item(label: String, accel: KeyStroke? = null, action: () -> Unit) =
            add(JMenuItem(label).apply { accelerator = accel; addActionListener { if (openDialogs == 0) action() } })
        fun JMenu.check(label: String, accel: KeyStroke?, state: () -> Boolean, action: () -> Unit) {
            val it = JCheckBoxMenuItem(label)
            it.accelerator = accel
            it.addActionListener { if (openDialogs == 0) action() }
            menuChecks += it to state
            add(it)
        }
        fun menu(name: String, mnemonic: Int, body: JMenu.() -> Unit) = bar.add(JMenu(name).apply {
            setMnemonic(mnemonic)
            addMenuListener(object : MenuListener {
                override fun menuSelected(e: MenuEvent) { for ((m, s) in menuChecks) m.isSelected = s() }
                override fun menuDeselected(e: MenuEvent) {}
                override fun menuCanceled(e: MenuEvent) {}
            })
            body()
        })

        menu("Game", KeyEvent.VK_G) {
            item("New", key(KeyEvent.VK_N, ctrl)) { cmdNew() }
            item("Load...", key(KeyEvent.VK_L, ctrl)) { cmdLoad() }
            item("Save...", key(KeyEvent.VK_S, ctrl)) { cmdSave() }
            addSeparator()
            item("Recall Cmd", key(KeyEvent.VK_R, ctrl)) { cmdRecall() }
            addSeparator()
            item("Inventory", key(KeyEvent.VK_F6)) { cmdInventory() }
            item("Look Around", key(KeyEvent.VK_F7)) { cmdLook() }
            addSeparator()
            item("Exit") { requestClose() }
        }
        menu("Options", KeyEvent.VK_O) {
            check("Music", key(KeyEvent.VK_F2), { cfg.musicOn }) { cmdMusic() }
            check("Sound", key(KeyEvent.VK_F3), { cfg.soundOn }) { cmdSound() }
            item("Volume...") { volumeDialog() }
            addSeparator()
            item("Tunes...") { tunesDialog() }
            addSeparator()
            check("Turbo", key(KeyEvent.VK_F8), { cfg.turbo }) { cmdTurbo() }
            addSeparator()
            item("Display...") { displayDialog() }
            check("Full screen", key(KeyEvent.VK_F11), { fullscreen }) { setFullscreen(!fullscreen) }
        }
        menu("Window", KeyEvent.VK_W) {
            for (n in 1..3) item("Size x $n") { sizeWindow(n) }
        }
        menu("Help", KeyEvent.VK_H) {
            item("Contents", key(KeyEvent.VK_F1)) { cmdHelp() }
            addSeparator()
            item("License...") { Dialogs.message(frame, "License", engine.exitText.ifBlank { engine.str(Str.LICENSE) }) }
            item("Order more games...") { Dialogs.message(frame, title, engine.str(Str.ORDER)) }
            item("About Hugowin...") { aboutDialog() }
        }
        return bar
    }

    // =================================================================== dialogs
    private fun aboutDialog() {
        val e = engine
        val lines = listOf(
            title, "", e.str(Str.VERSION), e.str(Str.COPYRIGHT), e.str(Str.CREATED_BY),
            if (e.distributor.isNotBlank()) Engine.cFormat(e.str(Str.DISTRIBUTED), e.distributor) else "",
            "", "Kotlin/JVM port v${PortInfo.VERSION}, by scriff-marimbo"
        ).joinToString("\n")
        Dialogs.message(frame, "About Hugo", lines)
    }

    private fun titled(t: String) = BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(t), BorderFactory.createEmptyBorder(2, 6, 6, 6))

    private fun gap(h: Int) = JPanel().apply { isOpaque = false; preferredSize = Dimension(1, h); maximumSize = Dimension(Int.MAX_VALUE, h) }

    private fun column(vararg parts: JComponent) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        for (p in parts) { p.alignmentX = 0f; add(p) }
    }

    private fun volumeDialog() {
        val cfg = engine.config
        val d = ModalDialog(frame, "Volume")
        fun slider(label: String, value: Int, set: (Int) -> Unit): Array<JComponent> {
            val l = JLabel("$label: $value%")
            val s = JSlider(0, 100, value).apply { majorTickSpacing = 25; minorTickSpacing = 5; snapToTicks = true; paintTicks = true }
            s.addChangeListener { l.text = "$label: ${s.value}%"; set(s.value) }
            return arrayOf(l, s)
        }
        val bg = JCheckBox("Keep playing when the window is inactive", cfg.bgSound != 0)
        bg.addActionListener { cfg.bgSound = if (bg.isSelected) 1 else 0 }
        val m = slider("Music volume", cfg.musicVol) { v -> engine.post { engine.sound.setMusicVolume(v) } }
        val s = slider("Sound volume", cfg.soundVol) { v -> engine.post { engine.sound.setSoundVolume(v) } }
        d.body.add(column(*m, gap(8), *s, gap(8), bg), BorderLayout.CENTER)
        d.body.preferredSize = Dimension(340, d.body.preferredSize.height)
        d.button("OK") { d.dispose() }
        d.open()
    }

    private fun tunesDialog() {
        val cfg = engine.config
        val d = ModalDialog(frame, "Tunes")
        val boxes = TUNES.mapIndexed { i, name -> JCheckBox(name, cfg.playlist.getOrElse(i) { false }) }
        d.body.add(JLabel("Play these tunes in turn:"), BorderLayout.NORTH)
        d.body.add(JPanel(GridLayout(0, 1)).apply { boxes.forEach { add(it) } }, BorderLayout.CENTER)
        d.button("OK") {
            val copy = BooleanArray(cfg.playlist.size) { boxes.getOrNull(it)?.isSelected ?: false }
            engine.post {
                val was = cfg.playlist.copyOf()
                engine.sound.setPlaylist(copy)
                if (cfg.musicOn && !copy[engine.sound.currentTune] && !was.contentEquals(copy)) engine.sound.playNextTune()
            }
            d.dispose()
        }
        d.button("Cancel") { d.dispose() }
        d.button("Default") {
            boxes.forEach { it.isSelected = false }
            engine.tables.defaultTunes.forEach { boxes.getOrNull(it)?.isSelected = true }
        }
        d.open()
    }

    private fun displayDialog() {
        val d = ModalDialog(frame, "Display and music")
        val group = ButtonGroup()
        val radios = PortSettings.Scale.entries.map { s ->
            JRadioButton(s.label, settings.scale == s).apply {
                group.add(this)
                addActionListener { settings.scale = s; view.scale = s; view.repaint() }
            }
        }
        val smooth = JCheckBox("Smooth (blurry) scaling", settings.smooth)
        smooth.addActionListener { settings.smooth = smooth.isSelected; view.smooth = smooth.isSelected; view.repaint() }
        var sf = settings.soundFont
        val sfLabel = JLabel()
        val sfButton = javax.swing.JButton()
        fun refresh() {
            sfLabel.text = if (sf.isEmpty()) "(Java built-in sounds)" else File(sf).name
            sfLabel.toolTipText = sf.ifEmpty { null }
            sfButton.text = if (sf.isEmpty()) "Browse..." else "Clear"
        }
        sfButton.addActionListener {
            if (sf.isEmpty()) {
                val fc = JFileChooser().apply {
                    dialogTitle = "Choose a SoundFont"
                    fileFilter = FileNameExtensionFilter("SoundFont files (*.sf2)", "sf2")
                }
                if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) sf = fc.selectedFile.path
            } else sf = ""
            refresh()
        }
        refresh()
        val sfRow = JPanel(BorderLayout(8, 0)).apply { add(sfLabel, BorderLayout.CENTER); add(sfButton, BorderLayout.EAST) }
        val scaling = column(*radios.toTypedArray(), smooth).apply { border = titled("Scaling") }
        val sound = column(JLabel("SoundFont (.sf2) for music, applied on restart:"), gap(4), sfRow)
            .apply { border = titled("Music") }
        sfRow.maximumSize = Dimension(Int.MAX_VALUE, sfRow.preferredSize.height)
        d.body.add(scaling, BorderLayout.CENTER)
        d.body.add(sound, BorderLayout.SOUTH)
        d.button("OK") { settings.soundFont = sf; settings.save(); d.dispose() }
        d.open()
    }

    private fun slotList(labels: List<String>, selected: Int) = JList(labels.toTypedArray()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = labels.size
        selectedIndex = selected
    }

    private fun saveDialog() {
        val start = engine.saveSlot.coerceIn(0, 7)
        val d = ModalDialog(frame, "Save Game")
        val desc = Dialogs.asciiField(31, engine.saves.description(start) ?: "")
        val labels = (0 until 8).map { i -> "$i: " + (engine.saves.description(i) ?: Engine.cFormat(engine.str(Str.UNUSED_SLOT).trimEnd('¤'), i)) }
        val list = slotList(labels, start)
        list.addListSelectionListener { if (!it.valueIsAdjusting && list.selectedIndex >= 0) desc.text = engine.saves.description(list.selectedIndex) ?: "" }
        fun save() {
            val s = list.selectedIndex.coerceAtLeast(0); val text = desc.text
            engine.post { engine.save(s, text) }
            d.dispose()
        }
        desc.addActionListener { save() }
        d.body.add(column(JLabel("Description:"), gap(4), desc), BorderLayout.NORTH)
        d.body.add(JScrollPane(list), BorderLayout.CENTER)
        d.body.preferredSize = Dimension(380, d.body.preferredSize.height)
        d.button("Save") { save() }
        d.button("Cancel") { d.dispose() }
        desc.selectAll()
        d.initialFocus = desc
        d.open()
    }

    private fun loadDialog() {
        val slots = (0 until 8).filter { engine.saves.description(it) != null }
        if (slots.isEmpty()) { Dialogs.message(frame, title, "There are no saved games to restore from!"); return }
        val start = slots.indexOf(engine.saveSlot).coerceAtLeast(0)
        val d = ModalDialog(frame, "Load Game")
        val list = slotList(slots.map { "$it: ${engine.saves.description(it)}" }, start)
        fun load() {
            val s = slots[list.selectedIndex.coerceAtLeast(0)]
            engine.post { engine.restore(s) }
            d.dispose()
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2 && list.locationToIndex(e.point) >= 0) load() }
        })
        d.body.add(JLabel("Choose a saved game:"), BorderLayout.NORTH)
        d.body.add(JScrollPane(list), BorderLayout.CENTER)
        d.body.preferredSize = Dimension(380, d.body.preferredSize.height)
        d.button("Load") { load() }
        d.button("Cancel") { d.dispose() }
        d.initialFocus = list
        d.open()
    }

    // =================================================================== commands
    private fun cmdNew() = engine.post { engine.newGame() }
    private fun cmdLoad() = loadDialog()
    private fun cmdSave() {
        if (engine.gameOverFl != 0) engine.post { engine.gameOverMsg() } else saveDialog()
    }
    private fun cmdRecall() = engine.post { engine.recall() }
    private fun cmdInventory() = engine.post { engine.showInventory() }
    private fun cmdLook() = engine.post { engine.look() }
    private fun cmdMusic() = engine.post { engine.sound.toggleMusic() }
    private fun cmdSound() = engine.post { engine.sound.toggleSound() }
    private fun cmdTurbo() { engine.config.turbo = !engine.config.turbo; toolbar.repaint() }
    private fun cmdHelp() = Dialogs.message(frame, title,
        "Welcome to the HELP file.\n\n" +
        "Type commands such as \"look\", \"open door\" or \"take key\".\nLeft click: walk / look.  Right click: take / use.\n" +
        "F6 shows what you carry; click an item, then right click\nwhere you want to use it.")

    // =================================================================== sizing
    private var lastView = Dimension(0, 0)
    private var adjusting = false

    private fun sizeWindow(n: Int) {
        if (fullscreen) setFullscreen(false)
        frame.extendedState = Frame.NORMAL
        resizeViewTo(320 * n, 184 * n)
    }

    private fun resizeViewTo(w: Int, h: Int) {
        adjusting = true
        frame.setSize(frame.width + (w - view.width), frame.height + (h - view.height))
        frame.validate()
        lastView = Dimension(w, h)
        adjusting = false
    }

    /**
     * Keep the game view at 320:184 while the user resizes: follow whichever edge moved more and
     * set the other one. Runs once the drag settles, so it doesn't fight the window manager.
     */
    private val aspectTimer = Timer(150) {
        if (fullscreen || adjusting || frame.extendedState and Frame.MAXIMIZED_BOTH != 0) return@Timer
        val w = view.width; val h = view.height
        if (w <= 0 || h <= 0) return@Timer
        val widthMoved = kotlin.math.abs(w - lastView.width) >= kotlin.math.abs(h - lastView.height)
        val (nw, nh) = if (widthMoved) w to (w * 184 + 160) / 320 else (h * 320 + 92) / 184 to h
        lastView = Dimension(w, h)
        if (kotlin.math.abs(nw - w) > 1 || kotlin.math.abs(nh - h) > 1) resizeViewTo(nw, nh)
        engine.config.viewWidth = view.width; engine.config.viewHeight = view.height
    }.apply { isRepeats = false }

    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        val gd = frame.graphicsConfiguration.device
        if (on) {
            windowedBounds = frame.bounds
            fullscreen = true
            frame.dispose()
            frame.isUndecorated = true
            if (gd.isFullScreenSupported) gd.fullScreenWindow = frame
            else { frame.bounds = frame.graphicsConfiguration.bounds; frame.isVisible = true }
        } else {
            if (gd.fullScreenWindow === frame) gd.fullScreenWindow = null
            frame.dispose()
            frame.isUndecorated = false
            windowedBounds?.let { frame.bounds = it }
            frame.isVisible = true
            fullscreen = false
            lastView = view.size
        }
        frame.isVisible = true
        view.requestFocusInWindow()
        settings.fullscreen = on
    }

    // =================================================================== input
    private fun installKeys() {
        // Alternative accelerators from the original's table and port extras (the menus carry the main ones)
        val root = frame.rootPane
        fun bind(ks: KeyStroke, name: String, f: () -> Unit) {
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name)
            root.actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) { if (openDialogs == 0) f() } })
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "load") { cmdLoad() }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), "save") { cmdSave() }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "recall") { cmdRecall() }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), "fullscreen") { setFullscreen(!fullscreen) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F4, InputEvent.ALT_DOWN_MASK), "exit") { requestClose() }

        // Game keys: the parser line and walking. Everything else falls through to the menus.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(KeyEventDispatcher { e ->
            if (KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow !== frame) return@KeyEventDispatcher false
            if (MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()) return@KeyEventDispatcher false
            // Enter held down from the command that opened a box must not close it by auto-repeat
            val enter = e.keyCode == KeyEvent.VK_ENTER || e.keyChar == '\n'
            val freshEnter = e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ENTER && !enterHeld
            if (e.keyCode == KeyEvent.VK_ENTER) enterHeld = e.id == KeyEvent.KEY_PRESSED
            Dialogs.top?.let { top ->
                val plain = e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK) == 0
                // Enter / Escape work on the open dialog wherever the keyboard focus happens to be
                if (enter && plain) { if (freshEnter) top.pressEnter(); return@KeyEventDispatcher true }
                if (e.keyCode == KeyEvent.VK_ESCAPE && plain) { if (e.id == KeyEvent.KEY_PRESSED) top.pressEscape(); return@KeyEventDispatcher true }
                // Other keys go to the dialog; anything aimed elsewhere is redirected there
                if (e.component != null && SwingUtilities.isDescendingFrom(e.component, top)) return@KeyEventDispatcher false
                top.focusInside()
                return@KeyEventDispatcher true
            }
            when (e.id) {
                KeyEvent.KEY_PRESSED -> onKeyPressed(e)
                KeyEvent.KEY_RELEASED -> { keysDown -= e.keyCode; false }
                KeyEvent.KEY_TYPED -> {
                    val c = e.keyChar.code
                    // Ctrl or Alt alone means a shortcut; both together is AltGr on Windows
                    val shortcut = e.isControlDown != e.isAltDown || e.isMetaDown
                    if (c in 0x20..0x7E && !shortcut) { engine.post { engine.parser.charTyped(c) }; true } else false
                }
                else -> false
            }
        })
    }

    private fun onKeyPressed(e: KeyEvent): Boolean {
        if (e.isControlDown || e.isAltDown || e.isMetaDown) return false
        val repeat = !keysDown.add(e.keyCode)
        val vk = when (e.keyCode) {
            KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> VK.UP
            KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> VK.DOWN
            KeyEvent.VK_LEFT, KeyEvent.VK_KP_LEFT -> VK.LEFT
            KeyEvent.VK_RIGHT, KeyEvent.VK_KP_RIGHT -> VK.RIGHT
            KeyEvent.VK_HOME -> VK.HOME
            KeyEvent.VK_END -> VK.END
            KeyEvent.VK_PAGE_UP -> VK.PRIOR
            KeyEvent.VK_PAGE_DOWN -> VK.NEXT
            KeyEvent.VK_ESCAPE -> VK.ESCAPE
            else -> 0
        }
        if (vk != 0) { engine.post { engine.parser.keyDown(vk, repeat) }; return true }
        return when (e.keyCode) {
            KeyEvent.VK_ENTER -> { engine.post { engine.parser.charTyped(13) }; true }
            KeyEvent.VK_BACK_SPACE -> { engine.post { engine.parser.charTyped(8) }; true }
            else -> false
        }
    }

    private fun installMouse() {
        val m = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = track(e)
            override fun mouseDragged(e: MouseEvent) = track(e)
            override fun mouseExited(e: MouseEvent) { engine.mouse.x = -1; engine.mouse.y = -1 }
            override fun mousePressed(e: MouseEvent) {
                view.requestFocusInWindow()
                track(e)
                if (engine.mouse.x < 0 || openDialogs > 0) return
                when {
                    SwingUtilities.isLeftMouseButton(e) -> engine.post { engine.leftClick = true }
                    SwingUtilities.isRightMouseButton(e) -> engine.post { engine.rightClick = true }
                }
            }
        }
        view.addMouseListener(m)
        view.addMouseMotionListener(m)
    }

    /** Game coordinates of the cursor (MouseHandler wants x 0..319, y 8..191; -1 outside). */
    private fun track(e: MouseEvent) {
        val r = view.viewRect()
        val mo = engine.mouse
        if (!r.contains(e.point) || openDialogs > 0) { mo.x = -1; mo.y = -1; return }
        mo.x = ((e.x - r.x) * 320 / r.width).coerceIn(0, 319)
        mo.y = ((e.y - r.y) * 184 / r.height).coerceIn(0, 183) + 8
    }

    companion object {
        /** Tune names from the Tunes dialog (dialog 137, IDC_TUNE1..9). */
        val TUNES = listOf("Hugo's Song", "Who goes there?", "Hugo first!", "Penelope's Song", "Searching for clues",
            "Trouble brewing", "Jungle Jingle", "Hot Steel Samba", "Voodoo Groove")
    }
}
