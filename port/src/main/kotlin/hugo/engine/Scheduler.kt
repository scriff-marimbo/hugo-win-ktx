package hugo.engine

import hugo.data.Str

/**
 * Timed event queue and the action interpreter (Get_queue, Insert_action, Do_action ...).
 * Actions are read straight from the EXE's DGROUP at their original addresses.
 */
class Scheduler(private val e: Engine) {
    class Event(val index: Int) {
        var action = 0          // DGROUP address of the action struct
        var localFl = 0
        var time = 0L           // u32 ticks
        var prev: Event? = null
        var next: Event? = null
    }

    val events = Array(MAX_EVENTS) { Event(it) }
    var freeEvent: Event? = null
    var headEvent: Event? = null
    var tailEvent: Event? = null

    private val x get() = e.exe

    /** InitEventQueue @1010:53c0. */
    fun initEventQueue() {
        for (i in 0 until MAX_EVENTS - 1) events[i].next = events[i + 1]
        events[MAX_EVENTS - 1].next = null
        for (i in 1 until MAX_EVENTS) events[i].prev = events[i - 1]
        events[0].prev = null
        headEvent = null; tailEvent = null
        freeEvent = events[0]
    }

    private fun getQueue(): Event {
        val ev = freeEvent ?: throw IllegalStateException("An error has occurred.\nGet_queue")
        freeEvent = ev.next
        ev.next = null
        return ev
    }

    /** DelQueue @1010:542a. */
    fun delQueue(ev: Event) {
        if (ev === headEvent) headEvent = ev.next
        else {
            ev.prev!!.next = ev.next
            if (ev.next == null) tailEvent = ev.prev else ev.next!!.prev = ev.prev
        }
        if (headEvent == null) tailEvent = null else headEvent!!.prev = null
        ev.next = freeEvent
        freeEvent?.prev = ev
        freeEvent = ev
    }

    /** InsertAction @1010:5492: queue ordered by time, ties keep insertion order. */
    fun insertAction(action: Int) {
        val ev = getQueue()
        ev.action = action
        ev.localFl = if (x.w(action) == AGSCHEDULE) 0 else 1
        ev.time = (getTicks() + x.sw(action + 2)) and 0xFFFFFFFFL
        val tail = tailEvent
        if (tail == null) {
            headEvent = ev; tailEvent = ev
            ev.prev = null; ev.next = null
        } else {
            var w: Event? = tail
            var found = false
            while (w != null && !found) {
                if (w.time <= ev.time) {
                    found = true
                    if (w !== tailEvent) w.next!!.prev = ev
                    else tailEvent = ev
                    ev.next = w.next
                    w.next = ev
                    ev.prev = w
                }
                w = w.prev
            }
            if (!found) {
                headEvent!!.prev = ev
                ev.next = headEvent
                ev.prev = null
                headEvent = ev
            }
        }
    }

    /** InsertActionList @1010:5542. */
    fun insertActionList(list: Int) {
        if (list == 0) return
        var i = 0
        while (x.w(list + 2 * i) != 0) { insertAction(x.w(list + 2 * i)); i++ }
    }

    fun getTicks(): Long = e.tick

    /** RunScheduler @1010:5d9a. */
    fun runScheduler() {
        var ev = headEvent
        while (ev != null && ev.time <= e.tick) ev = doAction(ev)
        e.tick = (e.tick + 1) and 0xFFFFFFFFL
    }

    /** ProcessBonus @1010:5dc8. */
    fun processBonus(i: Int) {
        if (i in 0 until e.pointsScored.size && !e.pointsScored[i]) {
            e.score += e.tables.pointScores[i]
            e.pointsScored[i] = true
        }
    }

    /** NewScreen @1010:5df2. */
    fun newScreen(screen: Int) {
        var ev = headEvent
        while (ev != null) {
            val nx = ev.next
            if (ev.localFl != 0) delQueue(ev)
            ev = nx
        }
        e.setCarriedScreen(screen)
        e.readScreenFiles(screen)
        screenActions(screen)
        e.display.initNewScreenDisplay()
    }

    /** RestoreScreen @1010:60a2. */
    fun restoreScreen(screen: Int) {
        e.setCarriedScreen(screen)
        e.readScreenFiles(screen)
        e.display.initNewScreenDisplay()
    }

    fun screenActions(screen: Int) {
        e.tables.screenActs.getOrNull(screen)?.forEach { insertActionList(it) }
    }

    fun delEventType(type: Int) {
        var ev = headEvent
        while (ev != null) {
            val nx = ev.next
            if (x.w(ev.action) == type) delQueue(ev)
            ev = nx
        }
    }

    /** DoAction @1010:55a6. Returns the next event to look at. */
    fun doAction(ev: Event): Event? {
        val a = ev.action
        val type = x.w(a)
        val o = e.objects
        fun obj(off: Int) = o[x.sw(a + off)]
        fun w(off: Int) = x.sw(a + off)
        fun uw(off: Int) = x.w(a + off)
        fun b(off: Int) = x.b(a + off)
        fun sb(off: Int) = x.sb(a + off)
        fun cond(c: Boolean, pass: Int, fail: Int) = insertActionList(if (c) uw(pass) else uw(fail))
        val hero = e.hero
        when (type) {
            0 -> insertActionList(uw(4))                                              // ASCHEDULE
            1 -> { obj(4).cycleNumb = b(6); obj(4).cycling = w(8) }                  // START_OBJ
            2 -> { obj(4).x = w(6); obj(4).y = w(8) }                                 // INIT_OBJXY
            3 -> {                                                                    // PROMPT
                val resp = e.host.box(2, e.dat.string(uw(4)))?.lowercase() ?: ""
                var found = false
                var i = 0
                val rl = uw(6)
                while (!found && x.sw(rl + 2 * i) != -1) {
                    if (resp.contains(e.dat.string(x.w(rl + 2 * i)).lowercase())) found = true
                    i++
                }
                insertActionList(if (found) uw(8) else uw(10))
            }
            4 -> {}                                                                   // BKGD_COLOR: empty in the Windows build
            5 -> { obj(4).vx = b(6); obj(4).vy = b(8) }                               // INIT_OBJVXY
            6 -> obj(4).carried = w(6)                                                // INIT_CARRY
            7 -> {                                                                    // INIT_HF_COORD
                val t = obj(4)
                t.x = hero.x - 1
                t.y = hero.img.y2 + hero.y - 1
                t.screen = e.screen
            }
            8 -> newScreen(w(4))                                                      // NEW_SCREEN
            9 -> obj(4).state = b(6)                                                  // INIT_OBJSTATE
            10 -> { obj(4).pathType = w(6); obj(4).vxPath = sb(8); obj(4).vyPath = sb(9) }   // INIT_PATH
            11 -> cond(obj(4).state == b(6), 8, 10)                                   // COND_R
            12 -> e.box(0, e.dat.string(uw(4)))                                       // TEXT
            13 -> e.objs.swapImages(w(4), w(6))                                       // SWAP_IMAGES
            14 -> cond(obj(4).screen == (w(6) and 0xFFFF), 8, 10)                     // COND_SCR
            15 -> autopilot(obj(4), obj(6), sb(8), sb(9))                             // AUTOPILOT
            16 -> obj(4).let { it.curr = it.seqList[w(6)].seq }                       // INIT_OBJ_SEQ
            17 -> obj(4).let { it.state = it.state or b(6) }                          // SET_STATE_BITS
            18 -> obj(4).let { it.state = it.state and b(6).inv() }                   // CLEAR_STATE_BITS
            19 -> cond((obj(4).state and b(6)) == w(6), 8, 10)                        // TEST_STATE_BITS
            20 -> delEventType(uw(4))                                                 // DEL_EVENTS
            21 -> e.gameOverFl = 1                                                    // GAMEOVER
            22 -> { val t = obj(4); t.x = hero.x; t.y = hero.y; t.screen = e.screen }  // INIT_HH_COORD
            23 -> e.endGame()                                                         // EXIT
            24 -> processBonus(w(4))                                                  // BONUS
            25 -> {                                                                   // COND_BOX
                val t = obj(4)
                val dx = t.x + t.img.x1
                val dy = t.y + t.img.y2
                cond(dx >= w(6) && dx <= w(10) && dy >= w(8) && dy <= w(12), 14, 16)
            }
            26 -> if (w(4) < 12) e.sound.playMusic(w(4)) else e.sound.playSound(w(4), 0, 1)   // SOUND
            27 -> e.score += obj(4).value                                             // ADD_SCORE
            28 -> e.score -= obj(4).value                                             // SUB_SCORE
            29 -> cond(obj(4).carried != 0, 6, 8)                                     // COND_CARRY
            30 -> e.maze.apply {                                                      // INIT_MAZE
                enabled = 1; size = b(4); x1 = w(6); y1 = w(8); x2 = w(10); y2 = w(12); x3 = w(14); x4 = w(16); firstScreen = b(18)
            }
            31 -> e.maze.enabled = 0                                                  // EXIT_MAZE
            32 -> obj(4).priority = w(6)                                              // INIT_PRIORITY
            33 -> obj(4).screen = b(6)                                                // INIT_SCREEN
            34 -> insertActionList(uw(4))                                             // AGSCHEDULE
            35 -> e.display.remapPal(w(4), w(6))                                      // REMAPPAL
            36 -> cond(e.parser.isWordPresent(e.tables.words(uw(4))), 6, 8)           // COND_NOUN
            37 -> e.screenStates[w(4)] = b(6)                                         // SCREEN_STATE
            38 -> {                                                                   // INIT_LIPS
                val lips = obj(4); val t = obj(6)
                lips.x = t.x + b(8); lips.y = t.y + b(9)
                lips.screen = e.screen
                lips.cycling = Cycle.FORWARD
            }
            39 -> {                                                                   // INIT_STORY_MODE
                e.storyModeFl = w(4)
                if (e.demoFl && w(4) == 0) e.endGame()
            }
            40 -> e.box(1, e.dat.string(uw(4)))                                       // WARN
            41 -> cond(e.pointsScored[w(4)], 6, 8)                                    // COND_BONUS
            42 -> e.box(0, e.fmt(Str.PICKED_UP, e.tables.words(obj(4).nounPtr).words.getOrElse(2) { "" }))   // TEXT_TAKE
            43 -> cond(e.host.box(3, e.dat.string(uw(4))) != null, 6, 8)              // YESNO
            44 -> e.routeIndex = -1                                                   // STOP_ROUTE
            45 -> cond(!(w(4) > e.routeIndex), 6, 8)                                  // COND_ROUTE
            46 -> e.jumpExitFl = w(4)                                                 // INIT_JUMPEXIT
            47 -> obj(4).let { it.viewx = w(6); it.viewy = w(8); it.direction = b(10) }   // INIT_VIEW
            48 -> obj(4).let {                                                        // INIT_OBJ_FRAME
                it.curr = it.seqList[w(6)].seq
                for (k in 0 until w(8)) it.curr = it.curr!!.next
            }
            255 -> {}
            else -> throw IllegalStateException("An error has occurred.\nDo_action")
        }
        if (type == NEW_SCREEN) return null
        val nx = ev.next
        delQueue(ev)
        return nx
    }

    /** AUTOPILOT (inline in DoAction): home obj1 in on obj2. */
    private fun autopilot(o1: GameObject, o2: GameObject, pdx: Int, pdy: Int) {
        o1.pathType = PathType.AUTO
        var dx = (o1.img.x1 - o2.img.x1 - o2.x + o1.x).i16()
        var dy = (o1.img.y1 - o2.img.y1 - o2.y + o1.y).i16()
        if (dx == 0) dx = 1
        if (dy == 0) dy = 1
        fun sgn(v: Int) = if (v < 0) -1 else 1
        if (Math.abs(dy) < Math.abs(dx)) {
            o1.vx = -(sgn(dx) * pdx)
            o1.vy = -(sgn(dy) * Math.abs((pdy * dy) / dx).i8())
        } else {
            o1.vy = -(sgn(dy) * pdy)
            o1.vx = -(sgn(dx) * Math.abs((pdx * dx) / dy).i8())
        }
    }

    companion object {
        const val MAX_EVENTS = 50
        const val NEW_SCREEN = 8
        const val AGSCHEDULE = 34
    }
}
