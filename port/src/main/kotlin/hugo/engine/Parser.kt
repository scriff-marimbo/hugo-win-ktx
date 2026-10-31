package hugo.engine

import hugo.data.GameTables.WordList
import hugo.data.Str

/** Keyboard handling and the text parser (segment 1010:7308..8100). */
class Parser(private val e: Engine) {
    /** The command being parsed (DGROUP:3720). */
    var line = ""
    /** The command line being typed (DGROUP:61d4). */
    var typed = ""
    var cursorChar = '_'
    private val ring = IntArray(32)
    private var putIdx = 0
    private var getIdx = 0
    private var blinkTick = 0L
    private var oldWalkDir = 0

    private val t get() = e.tables
    private val hero get() = e.hero

    // ------------------------------------------------------------------ keyboard
    /** Virtual-key codes used by the original. */
    object VK {
        const val ESCAPE = 0x1B; const val PRIOR = 0x21; const val NEXT = 0x22; const val END = 0x23; const val HOME = 0x24
        const val LEFT = 0x25; const val UP = 0x26; const val RIGHT = 0x27; const val DOWN = 0x28
    }

    /** CHugowinView::OnKeyDown -> KeyHandler @1010:7340 for movement / escape keys. */
    fun keyDown(vk: Int, repeat: Boolean) {
        when {
            vk == VK.ESCAPE -> {
                if (e.inventoryState == Inventory.ACTIVE) e.inventoryState = Inventory.UP
                e.objectInHand = -1
            }
            vk in VK.PRIOR..VK.DOWN -> if (!repeat) { e.routeIndex = -1; moveHeroByKey(vk) }
        }
    }

    /** Character from the keyboard (ToAscii result): queued unless story mode is on. */
    fun charTyped(c: Int) {
        if (e.storyModeFl != 0) return
        val nx = (putIdx + 1) % ring.size
        if (nx != getIdx) { ring[putIdx] = c; putIdx = nx }
    }

    /** SetHeroDirectionImage @1010:73c6. */
    fun setDirection(vk: Int) {
        val h = hero
        when (vk) {
            VK.PRIOR, VK.NEXT, VK.RIGHT -> h.curr = h.seqList[0].seq
            VK.END, VK.HOME, VK.LEFT -> h.curr = h.seqList[1].seq
            VK.UP -> h.curr = h.seqList[3].seq
            VK.DOWN -> h.curr = h.seqList[2].seq
        }
    }

    /** MoveHeroByKey @1010:7422: same key twice stops the hero. */
    fun moveHeroByKey(vk: Int) {
        val h = hero
        if (e.storyModeFl != 0 || h.pathType != PathType.USER) return
        if (h.vx == 0 && h.vy == 0) oldWalkDir = 0
        if (oldWalkDir == vk) {
            h.vy = 0; h.vx = 0; oldWalkDir = 0; h.cycling = Cycle.NOT_CYCLING
            return
        }
        setDirection(vk)
        h.vy = 0; h.vx = 0
        when (vk) {
            VK.PRIOR -> { h.vx = 5; h.vy = -2 }
            VK.NEXT -> { h.vx = 5; h.vy = 2 }
            VK.END -> { h.vx = -5; h.vy = 2 }
            VK.HOME -> { h.vx = -5; h.vy = -2 }
            VK.LEFT -> h.vx = -5
            VK.UP -> h.vy = -4
            VK.RIGHT -> h.vx = 5
            VK.DOWN -> h.vy = 4
        }
        oldWalkDir = vk
        h.cycling = Cycle.FORWARD
    }

    /** CharHandler @1010:7518 (runs every tick). */
    fun charHandler() {
        var changed = getIdx != putIdx
        while (getIdx != putIdx) {
            val c = ring[getIdx]
            getIdx = (getIdx + 1) % ring.size
            when (c) {
                8 -> if (typed.isNotEmpty()) typed = typed.dropLast(1)
                13 -> if (typed.isNotEmpty() && hero.pathType != PathType.QUIET) {
                    if (e.inventoryState == Inventory.ACTIVE) e.inventoryState = Inventory.UP
                    command(typed)
                    typed = ""
                }
                else -> if (typed.length >= 38) e.host.beep() else if (c in 0x20..0x7E) typed += c.toChar()
            }
        }
        val half = e.ticksPerSecond / 2
        if (blinkTick++ % half == 0L) { cursorChar = if (cursorChar == '_') ' ' else '_'; changed = true }
        if (e.recallFl) { e.recallFl = false; typed = line; changed = true }
        if (changed) e.host.statusChanged()
        e.updateStatus()
        if (e.lookFl) { command("look around"); e.lookFl = false }
    }

    /** CommandF @1010:76a8. */
    fun command(s: String) { line = s; lineHandler() }

    // ------------------------------------------------------------------ word matching
    /** IsWordPresent @1010:7d28: any word of the list appears in the line (substring match). */
    fun isWordPresent(w: WordList): Boolean = w.words.any { line.contains(it) }

    private fun findWord(lists: List<WordList>): String? {
        for (l in lists) for (w in l.words) if (line.contains(w)) return l.first
        return null
    }
    fun findNoun() = findWord(t.nouns)
    fun findVerb() = findWord(t.verbs)

    // ------------------------------------------------------------------ line handler
    /** LineHandler @1010:76c4. */
    fun lineHandler() {
        if (line.startsWith("PPG")) {
            e.sound.playSound(14, 0, 2)
            e.godMode = !e.godMode
            return
        }
        line = line.lowercase()
        if (e.godMode) {
            if (line.contains("goto")) {
                for (i in 0 until 17) if (t.screenNames[i].lowercase() == line.drop(5)) { e.scheduler.newScreen(i); return }
            }
            if (line.contains("fetch all")) {
                for (o in e.objects) if (o.genericCmd and Generic.TAKE != 0) takeObject(o)
                return
            }
            if (line.contains("fetch")) {
                for (o in e.objects) if (t.words(o.nounPtr).first.lowercase() == line.drop(6)) { takeObject(o); return }
            }
            if (line.contains("find")) {
                for (o in e.objects) if (t.words(o.nounPtr).first.lowercase() == line.drop(5)) { e.scheduler.newScreen(o.screen); return }
            }
        }
        if (line == "exit" || line.contains("quit")) { e.box(0, e.str(Str.USE_ALT_F4)); return }
        if (line == "save" && e.machineState == 3) { e.saves.saveGame(e.saveSlot, e.str(Str.CURRENT_GAME)); return }
        if ((line == "restore" && e.machineState == 3) || e.machineState == 0) {
            e.saves.restoreGame(e.saveSlot)
            e.scheduler.restoreScreen(e.screen)
            e.machineState = 3
            return
        }
        if (line.isEmpty() || line.all { it == ' ' }) return
        if (e.gameOverFl != 0) { e.gameOverMsg(); return }

        val farComment = StringBuilder()
        for (o in e.objects) {
            if (isWordPresent(t.words(o.nounPtr))) {
                if (isObjectVerb(o, farComment)) return
                if (isGenericVerb(o, farComment)) return
            }
        }
        val ctxComment = StringBuilder()
        for (o in e.objects) {
            if (o.verbOnly != 0) {
                if (isObjectVerb(o, ctxComment)) return
                if (isGenericVerb(o, ctxComment)) return
            }
        }
        val bg = t.screenBackground.getOrElse(e.screen) { emptyList() }
        if (isBackgroundWord(bg)) return
        if (isCatchallVerb(bg)) return
        if (isBackgroundWord(t.catchall)) return
        if (isCatchallVerb(t.catchall)) return
        if (farComment.isNotEmpty()) { e.box(0, farComment.toString()); return }

        val verb = findVerb()
        val noun = findNoun()
        if (verb == t.look.first && e.maze.enabled != 0) {
            e.box(0, e.str(Str.MAZE)); showTakeables(); return
        }
        e.box(0, e.str(when {
            verb != null && noun != null -> Str.NO_POINT
            noun != null -> Str.NOT_FULLY
            verb != null -> Str.NOT_QUITE
            else -> Str.EH
        }))
    }

    /** IsBackgroundWord @1010:7ad0. */
    private fun isBackgroundWord(list: List<hugo.data.GameTables.Background>): Boolean {
        for (b in list) {
            if (isWordPresent(b.verb) && isWordPresent(b.noun) && (b.roomState == -1 || b.roomState == e.screenStates[e.screen])) {
                e.box(0, e.dat.string(b.string))
                e.scheduler.processBonus(b.bonus)
                return true
            }
        }
        return false
    }

    /** IsCatchallVerb @1010:7b56. */
    private fun isCatchallVerb(list: List<hugo.data.GameTables.Background>): Boolean {
        for (b in list) {
            if (isWordPresent(b.verb) && t.isNullList(b.nounAddr) && (b.matchFl == 0 || findNoun() == null) &&
                (b.roomState == -1 || b.roomState == e.screenStates[e.screen])) {
                e.box(0, e.dat.string(b.string))
                e.scheduler.processBonus(b.bonus)
                if (b.verb.first == t.look.first) showTakeables()
                return true
            }
        }
        return false
    }

    /** IsNear @1010:7bfe. Puts a reason into comment when not near. */
    private fun isNear(o: GameObject, verbWord: String, comment: StringBuilder): Boolean {
        if (o.carried != 0) return true
        val text: Int
        if (o.screen == e.screen) {
            if (o.cycling == Cycle.INVISIBLE) {
                if (o.seqNumb != 0) { comment.setLength(0); comment.append(e.str(Str.DONT_SEE_HERE)); return false }
                if (o.radius < 0) return true
                if (Math.abs(o.x - hero.x) <= o.radius && Math.abs(o.y - hero.img.y2 - hero.y) <= o.radius) return true
            } else {
                if (o.radius < 0) return true
                if (Math.abs(o.x - hero.x) <= o.radius &&
                    Math.abs(o.img.y2 - hero.img.y2 + o.y - hero.y) <= o.radius) return true
            }
            text = if (o.value == 0 || verbWord == t.take.first) Str.NOT_CLOSE else Str.DONT_HAVE_ANY
        } else {
            text = if (o.value == 0) Str.THERE_ARENT else Str.DONT_HAVE_ANY
        }
        comment.setLength(0); comment.append(e.str(text))
        return false
    }

    /** IsGenericVerb @1010:7f8e: look / take / drop. */
    private fun isGenericVerb(o: GameObject, comment: StringBuilder): Boolean {
        if (o.genericCmd == 0) return false
        if (isWordPresent(t.look) && isNear(o, t.look.first, comment)) {
            val text = when {
                o.genericCmd and Generic.LOOK_S == Generic.LOOK_S -> t.description(o.dataPtr, o.state, true)
                o.genericCmd and Generic.LOOK == Generic.LOOK -> { if (o.dataPtr == 0) return false; t.description(o.dataPtr, 0, false) }
                else -> e.str(Str.NOTHING_UNUSUAL)
            }
            e.box(0, text ?: ""); return true
        }
        if (isWordPresent(t.take) && isNear(o, t.take.first, comment)) {
            if (o.carried != 0) { e.box(0, e.str(Str.ALREADY_HAVE)); return true }
            if (o.genericCmd and Generic.TAKE != Generic.TAKE) return false   // ("It is of no use" is unreachable in the original)
            takeObject(o); return true
        }
        if (isWordPresent(t.drop)) {
            when {
                o.carried == 0 && o.genericCmd and Generic.DROP == Generic.DROP -> e.box(0, e.str(Str.DONT_HAVE_IT))
                o.carried != 0 && o.genericCmd and Generic.DROP == Generic.DROP -> dropObject(o)
                o.cmdIndex != 0 -> return false
                else -> e.box(0, e.str(Str.NEED_IT))
            }
            return true
        }
        return false
    }

    /** IsObjectVerb @1010:8100: per-object verb commands. */
    private fun isObjectVerb(o: GameObject, comment: StringBuilder): Boolean {
        val cmds = t.cmds(o.cmdIndex)
        val cmd = cmds.firstOrNull { isWordPresent(it.verb) } ?: return false
        val verbWord = cmd.verb.first
        if (!isNear(o, verbWord, comment)) return false
        for (req in cmd.reqObjs) {
            if (!isCarried(req.first)) { e.box(0, cmd.textNotCarried ?: ""); return true }
        }
        if (o.state == cmd.reqState || cmd.reqState == 0xFF) {
            if (cmd.reqState != 0xFF) o.state = cmd.newState
            cmd.textDone?.let { e.box(0, it) }
            e.scheduler.insertActionList(cmd.actList)
            if (verbWord == t.look.first || verbWord == t.take.first || verbWord == t.drop.first) isGenericVerb(o, comment)
        } else {
            cmd.textWrongState?.let { e.box(0, it) }
        }
        return true
    }

    /** IsCarried @1010:80c6. */
    private fun isCarried(noun: String) = e.objects.any { t.words(it.nounPtr).first == noun && it.carried != 0 }

    /** ShowTakeables @1010:7e2a. */
    fun showTakeables() {
        for (o in e.objects) {
            if (o.cycling != Cycle.INVISIBLE && o.screen == e.screen &&
                (o.genericCmd and Generic.TAKE == Generic.TAKE || o.value != 0))
                e.box(0, e.fmt(Str.ALSO_SEE, t.words(o.nounPtr).words.getOrElse(1) { "" }))
        }
    }

    /** TakeObject @1010:7e96. */
    fun takeObject(o: GameObject) {
        o.carried = 1
        if (o.seqNumb != 0) o.cycling = Cycle.INVISIBLE
        e.score += o.value
        if (o.seqNumb != 0) o.viewx = -1
        e.box(0, e.fmt(Str.PICKED_UP, t.words(o.nounPtr).words.getOrElse(2) { "" }))
    }

    /** DropObject @1010:7ee0. */
    private fun dropObject(o: GameObject) {
        o.carried = 0
        o.screen = e.screen
        o.cycling = if (o.seqNumb < 2 && o.seqList[0].imageNbr < 2) Cycle.NOT_CYCLING else Cycle.FORWARD
        o.x = hero.x - 1
        o.y = hero.img.y2 + hero.y - 1
        if ((o.img.y2 + o.y) and 0xFFFF > 199) o.y = 190 - o.img.y2
        e.score -= o.value
        e.box(0, e.str(Str.OK))
    }
}
