package hugo.data

/**
 * The game logic compiled into HUGOWIN1.EXE. Everything is addressed the way the original
 * code addresses it: by 16-bit DGROUP near pointers. Keeping the raw addresses means save
 * files stay byte-compatible with the original program.
 *
 * Layouts: docs/DAT_FORMATS.md section 9.
 */
class GameTables(val exe: Exe) {

    /** A noun or verb list: char*[] ending with "". [0] = id, [1] = article form, [2..] synonyms. */
    class WordList(val addr: Int, val words: List<String>) {
        val first: String get() = words.firstOrNull() ?: ""
        val isEmptyList: Boolean get() = words.isEmpty()
        override fun toString() = words.toString()
    }

    private val wordLists = HashMap<Int, WordList>()

    /** Word list at a DGROUP address (cached; identity matters, like the original's pointer compares). */
    fun words(addr: Int): WordList = wordLists.getOrPut(addr) {
        val out = ArrayList<String>()
        if (addr != 0) {
            var p = addr
            while (true) {
                val sp = exe.w(p)
                if (sp == 0) break
                val s = exe.str(sp)
                if (s.isEmpty()) break
                out += s; p += 2
            }
        }
        WordList(addr, out)
    }

    /** True when the list's first pointer is 0 (how the original marks "no noun" in background cmds). */
    fun isNullList(addr: Int) = addr == 0 || exe.w(addr) == 0

    private fun ptrTable(base: Int): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (exe.w(base + 2 * i) != 0) { out += exe.w(base + 2 * i); i++ }
        return out
    }

    val nouns: List<WordList> = ptrTable(NOUNS).map { words(it) }
    val verbs: List<WordList> = ptrTable(VERBS).map { words(it) }
    val take = words(VERB_TAKE)
    val drop = words(VERB_DROP)
    val look = words(VERB_LOOK)

    val screenNames: List<String> = (0 until NUM_SCREEN_NAMES).map { exe.str(exe.w(SCREEN_NAMES + 2 * it)) }

    class Hotspot(val screen: Int, val x1: Int, val y1: Int, val x2: Int, val y2: Int,
                  val actList: Int, val viewx: Int, val viewy: Int, val direction: Int)

    val hotspots: List<Hotspot> = buildList {
        var p = HOTSPOTS
        while (exe.sw(p) >= 0) {
            add(Hotspot(exe.sw(p), exe.sw(p + 2), exe.sw(p + 4), exe.sw(p + 6), exe.sw(p + 8),
                exe.w(p + 10), exe.sw(p + 12), exe.sw(p + 14), exe.sw(p + 16)))
            p += 18
        }
    }

    /** Background command: {char **verb, **noun; u16 string; u16 matchFl; i8 roomState; u8 bonus}. */
    class Background(val verb: WordList, val nounAddr: Int, val noun: WordList, val string: Int,
                     val matchFl: Int, val roomState: Int, val bonus: Int)

    private fun backgroundList(addr: Int): List<Background> = buildList {
        var p = addr
        if (addr == 0) return@buildList
        while (exe.w(exe.w(p)) != 0) {
            add(Background(words(exe.w(p)), exe.w(p + 2), words(exe.w(p + 2)), exe.w(p + 4), exe.w(p + 6),
                exe.sb(p + 8), exe.b(p + 9)))
            p += 10
        }
    }

    val catchall: List<Background> = backgroundList(CATCHALL)
    val screenBackground: List<List<Background>> = (0 until NUM_SCREENS).map { backgroundList(exe.w(SCREEN_BG + 2 * it)) }

    /** Per-screen list of action-list pointers queued on entering the screen. */
    val screenActs: List<List<Int>> = (0 until NUM_SCREENS).map { s ->
        val p = exe.w(SCREEN_ACTS + 2 * s)
        if (p == 0) emptyList() else ptrTable(p)
    }

    val numPoints = exe.w(NUM_POINTS)
    val pointScores: IntArray = IntArray(numPoints) { exe.b(POINTS + 4 * it) }

    /** struct cmd (14 bytes), reached via Object.cmdIndex -> 0-terminated cmd*[]. */
    class Cmd(val addr: Int, val verb: WordList, val reqObjs: List<WordList>, val textNotCarried: String?,
              val reqState: Int, val newState: Int, val textWrongState: String?, val textDone: String?, val actList: Int)

    private val cmdCache = HashMap<Int, List<Cmd>>()
    fun cmds(listAddr: Int): List<Cmd> = if (listAddr == 0) emptyList() else cmdCache.getOrPut(listAddr) {
        ptrTable(listAddr).map { c ->
            val req = exe.w(c + 2)
            val reqs = if (req == 0) emptyList() else ptrTable(req).map { words(it) }
            fun s(o: Int) = exe.w(c + o).let { if (it == 0) null else exe.str(it) }
            Cmd(c, words(exe.w(c)), reqs, s(4), exe.b(c + 6), exe.b(c + 7), s(8), s(10), exe.w(c + 12))
        }
    }

    /** Use-with table in segment 5 after the objects: {i16 obj; char *noTarget; {noun, verb}[12]}. */
    class Use(val obj: Int, val textNoTarget: String?, val targets: List<Pair<WordList, WordList>>)

    val uses: List<Use> = buildList {
        val seg = exe.objSeg
        var p = NUM_OBJECTS * OBJ_SIZE
        while (seg.s16(p) != NUM_OBJECTS) {
            val tg = ArrayList<Pair<WordList, WordList>>()
            var q = p + 4
            while (q < p + 52 && seg.u16(q) != 0) { tg += words(seg.u16(q)) to words(seg.u16(q + 2)); q += 4 }
            add(Use(seg.s16(p), seg.u16(p + 2).let { if (it == 0) null else exe.str(it) }, tg))
            p += 52
        }
    }

    /** Initial object records (84 raw bytes each), as stored in segment 5. */
    fun initialObjectRecord(i: Int): ByteArray = exe.objSeg.copyOfRange(i * OBJ_SIZE, (i + 1) * OBJ_SIZE)

    /** Inventory display order (object indices, -1 terminated, max 32). */
    val inventoryOrder: IntArray = buildList {
        for (i in 0 until 32) { val v = exe.sw(INV_ORDER + 2 * i); if (v == -1) break; add(v) }
    }.toIntArray()

    /** Default palette (16 x RGB) at DGROUP:0094. */
    val palette: IntArray = IntArray(16) { i ->
        val o = PALETTE + 4 * i
        (exe.b(o) shl 16) or (exe.b(o + 1) shl 8) or exe.b(o + 2)
    }

    val defaultTunes: List<Int> = buildList {
        var i = 0
        while (exe.sw(DEFAULT_TUNES + 2 * i) != -1) { add(exe.sw(DEFAULT_TUNES + 2 * i)); i++ }
    }

    /** Value saved in HHHn.SAV so pointers can be relocated (word at DGROUP:0C24). */
    val actionBase: Int = exe.w(ACTION_BASE)

    /** Object description: plain string, or one string per state when genericCmd & LOOK_S. */
    fun description(dataPtr: Int, state: Int, stateDependent: Boolean): String? {
        if (dataPtr == 0) return null
        return if (stateDependent) exe.w(dataPtr + 2 * state).let { if (it == 0) null else exe.str(it) }
        else exe.str(dataPtr)
    }

    companion object {
        const val NUM_OBJECTS = 88
        const val OBJ_SIZE = 0x54
        const val NUM_SCREENS = 16          // playable screens; per-screen tables have 16 entries
        const val NUM_SCREEN_NAMES = 17     // SCENERY.DAT and screenStates have 17
        const val NOUNS = 0x03b4
        const val VERBS = 0x057c
        const val VERB_TAKE = 0x047a
        const val VERB_DROP = 0x0486
        const val VERB_LOOK = 0x04c4
        const val SCREEN_NAMES = 0x2ca2
        const val HOTSPOTS = 0x34d2
        const val SCREEN_BG = 0x34b2
        const val CATCHALL = 0x2d28
        const val SCREEN_ACTS = 0x2d08
        const val POINTS = 0x36ca
        const val NUM_POINTS = 0x36fe
        const val INV_ORDER = 0x2aec
        const val PALETTE = 0x0094
        const val DEFAULT_TUNES = 0x3700
        const val ACTION_BASE = 0x0c24
    }
}
