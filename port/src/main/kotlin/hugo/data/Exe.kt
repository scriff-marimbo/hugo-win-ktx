package hugo.data

import java.io.File

/** Little-endian helpers on a byte array. */
fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
fun ByteArray.s8(o: Int): Int = this[o].toInt()
fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
fun ByteArray.s16(o: Int): Int = u16(o).toShort().toInt()
fun ByteArray.u32(o: Int): Long = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
fun ByteArray.cstr(o: Int): String {
    var e = o
    while (e < size && this[e] != 0.toByte()) e++
    return String(this, o, e - o, Charsets.ISO_8859_1)
}

/**
 * HUGOWIN1.EXE (Hugo for Windows v1.5, 16-bit NE). Only the two data segments the game
 * logic needs are kept: DGROUP (segment 15, selector 1070) and the object table
 * (segment 5, selector 1020).
 */
class Exe(val bytes: ByteArray) {
    val dg: ByteArray
    val objSeg: ByteArray

    init {
        require(bytes.size > 0x40 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) { "not an EXE" }
        val ne = bytes.u16(0x3c) or (bytes.u16(0x3e) shl 16)
        require(bytes[ne] == 'N'.code.toByte() && bytes[ne + 1] == 'E'.code.toByte()) { "not a 16-bit Windows (NE) program" }
        val nseg = bytes.u16(ne + 0x1c)
        val segTab = ne + bytes.u16(ne + 0x22)
        val align = bytes.u16(ne + 0x32)
        fun seg(i: Int): ByteArray {               // 1-based
            val e = segTab + 8 * (i - 1)
            val off = bytes.u16(e) shl align
            val len = bytes.u16(e + 2).let { if (it == 0 && off != 0) 0x10000 else it }
            return bytes.copyOfRange(off, off + len)
        }
        require(nseg >= 15) { "unexpected segment count $nseg" }
        dg = seg(15)
        objSeg = seg(5)
        require(dg.cstr(STRING_CYPHER) == "Copyright 1992, Gray Design Associates") {
            "This does not look like HUGOWIN1.EXE v1.5 (Hugo's Horrific Adventure)"
        }
    }

    fun w(o: Int) = dg.u16(o)
    fun sw(o: Int) = dg.s16(o)
    fun b(o: Int) = dg.u8(o)
    fun sb(o: Int) = dg.s8(o)
    fun str(o: Int): String = dg.cstr(o)
    /** DGROUP is writable: ProcessMaze patches its own action structs. */
    fun setW(o: Int, v: Int) { dg[o] = v.toByte(); dg[o + 1] = (v shr 8).toByte() }

    companion object {
        const val STRING_CYPHER = 0x3774
        fun load(f: File) = Exe(f.readBytes())
    }
}

/** DGROUP addresses of the engine's own message strings (read from the EXE, not hard-coded). */
object Str {
    const val OBSOLETE = 0x4c30
    const val SCORE = 0x4036
    const val PROMPT = 0x4e3e
    const val BAG_EMPTY = 0x408a
    const val NOT_SAVED = 0x4044
    const val UNUSED_SLOT = 0x40b2
    const val CURRENT_GAME = 0x4eb2
    const val USE_ALT_F4 = 0x4e7c
    const val MAZE = 0x4ec8
    const val NO_POINT = 0x4f08
    const val NOT_FULLY = 0x4f1a
    const val NOT_QUITE = 0x4f34
    const val EH = 0x4f4e
    const val OK = 0x4fbe
    const val EXIT = 0x5040
    const val NO_WAY = 0x5046
    const val TWO_LINES = 0x4c00
    const val COPYRIGHT = 0x3fc8
    const val PICKED_UP = 0x4c10
    const val ALSO_SEE = 0x4fa8
    const val CAREFUL_TITLE = 0x4d82
    const val GAME_OVER = 0x4d9e
    const val ORDER = 0x4a4e
    const val TITLE = 0x3f30
    const val NOTHING_UNUSUAL = 0x4fc2
    const val ALREADY_HAVE = 0x4fe4
    const val NO_USE = 0x4ffa
    const val DONT_HAVE_IT = 0x5012
    const val NEED_IT = 0x5026
    const val DONT_SEE_HERE = 0x4f78
    const val NOT_CLOSE = 0x4f8e
    const val THERE_ARENT = 0x4f66
    const val DONT_HAVE_ANY = 0x4f52
    const val VERSION = 0x3fb2
    const val CREATED_BY = 0x3fec
    const val DISTRIBUTED = 0x401c
    const val LICENSE = 0x421e
    const val WARNING_TITLE = 0x4c5e
    const val ERROR_TITLE = 0x4d74
    const val NO_SAVES = -1   // string resource 61204 in the EXE resources
}
