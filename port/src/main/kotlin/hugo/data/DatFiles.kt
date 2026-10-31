package hugo.data

import java.io.File

/** 8bpp image decoded from a 4-plane PCX. Pixel values are palette indices 0..15. */
class Pcx(val width: Int, val lines: Int, val dataWidth: Int, val pixels: ByteArray, val size: Int)

object PcxReader {
    /** Decodes one PCX at buf[off] (mirrors ReadPCX @1010:2a90). */
    fun read(buf: ByteArray, off: Int): Pcx {
        if (buf.u8(off) != 0x0A) throw IllegalStateException("Bad data file format: PCX expected at $off")
        val x1 = buf.u16(off + 4); val y1 = buf.u16(off + 6); val x2 = buf.u16(off + 8); val y2 = buf.u16(off + 10)
        val planes = buf.u8(off + 65)
        val bpl = buf.u16(off + 66)
        check(buf.u8(off + 3) == 1 && planes == 4) { "unsupported PCX" }
        val w = bpl * 8
        val lines = y2 - y1 + 1
        val lineLen = bpl * planes
        val raw = ByteArray(lineLen * lines)
        var p = off + 128
        var n = 0
        while (n < raw.size) {
            val c = buf.u8(p++)
            if (c and 0xC0 == 0xC0) {
                val v = buf[p++]
                repeat(c and 0x3F) { if (n < raw.size) raw[n++] = v }
            } else raw[n++] = c.toByte()
        }
        val pix = ByteArray(w * lines)
        for (y in 0 until lines) {
            val r = y * lineLen
            for (xb in 0 until bpl) {
                val b0 = raw.u8(r + xb); val b1 = raw.u8(r + bpl + xb)
                val b2 = raw.u8(r + 2 * bpl + xb); val b3 = raw.u8(r + 3 * bpl + xb)
                for (bit in 0 until 8) {
                    val s = 7 - bit
                    pix[y * w + xb * 8 + bit] = (((b0 shr s) and 1) or (((b1 shr s) and 1) shl 1) or
                        (((b2 shr s) and 1) shl 2) or (((b3 shr s) and 1) shl 3)).toByte()
                }
            }
        }
        return Pcx(w, lines, x2 - x1 + 1, pix, p - off)
    }
}

/** All .DAT files of the game, read once. Formats: docs/DAT_FORMATS.md. */
class DatFiles(val dir: File) {
    private fun file(name: String): File = findFile(dir, name)

    val scenery: ByteArray = file("SCENERY.DAT").readBytes()
    val objects: ByteArray = file("OBJECTS.DAT").readBytes()
    val sounds: ByteArray = file("SOUNDS.DAT").readBytes()
    private val strings: ByteArray = file("STRINGS.DAT").readBytes()
    val uif: ByteArray = file("UIF.DAT").readBytes()
    val boot: ByteArray = file("HUGO.BSF").readBytes()

    // ---------------------------------------------------------------- scenery
    val numScenes = (scenery.u32(0) / 32).toInt()

    fun background(screen: Int): Pcx = PcxReader.read(scenery, scenery.u32(screen * 32).toInt())

    /** kind 0 = boundary, 1 = overlay, 2 = base. Raw 8000-byte 1bpp bitmap (zeros when absent). */
    fun overlay(screen: Int, kind: Int): ByteArray {
        val o = scenery.u32(screen * 32 + 8 + kind * 8).toInt()
        val l = scenery.u32(screen * 32 + 12 + kind * 8).toInt()
        return if (l == 0) ByteArray(OVL_SIZE) else scenery.copyOfRange(o, o + OVL_SIZE)
    }

    // ---------------------------------------------------------------- objects
    /** Consecutive PCX frames for object i, in file order. */
    fun objectFrames(i: Int): List<Pcx> {
        val off = objects.u32(i * 8).toInt()
        val len = objects.u32(i * 8 + 4).toInt()
        val out = ArrayList<Pcx>()
        var p = off
        while (len != 0 && p < off + len) { val img = PcxReader.read(objects, p); out += img; p += img.size }
        return out
    }

    // ---------------------------------------------------------------- sounds
    /** Sound i (0..11 MIDI, 12+ 8-bit PCM 11025 Hz), or null if the slot is empty. */
    fun sound(i: Int): ByteArray? {
        if (i !in 0 until 64) return null
        val size = sounds.u16(i * 6)
        val off = sounds.u32(i * 6 + 2).toInt()
        return if (size == 0) null else sounds.copyOfRange(off, off + size)
    }

    // ---------------------------------------------------------------- strings
    private val cypher = "Copyright 1992, Gray Design Associates".toByteArray(Charsets.ISO_8859_1)
    val numStrings = (strings.u32(0) / 4).toInt() - 1

    /** Fetch_string @1010:37fe: subtract-cypher decode. */
    fun string(i: Int): String {
        val a = strings.u32(4 * i).toInt(); val b = strings.u32(4 * i + 4).toInt()
        val out = ByteArray(b - a) { k -> (strings[a + k] - cypher[k % cypher.size]).toByte() }
        // The original stops at the first NUL (strlen-based decode)
        val z = out.indexOf(0)
        return String(if (z >= 0) out.copyOf(z) else out, Charsets.ISO_8859_1)
    }

    // ---------------------------------------------------------------- UIF
    private fun uifItem(i: Int): Pair<Int, Int> = uif.u16(i * 6) to uif.u32(i * 6 + 2).toInt()

    /** Icon sheet (UIF item 3), 320x200. */
    fun icons(): Pcx = PcxReader.read(uif, uifItem(3).second)

    /** Bitmap fonts 0..2 (5, 7 and 8 px). */
    fun font(i: Int): Font {
        val (size, off) = uifItem(i)
        val d = uif.copyOfRange(off, off + size)
        val glyphs = arrayOfNulls<Font.Glyph>(128)
        var p = 2
        for (ch in 1 until 128) {
            val h = d.u8(p); val w = d.u8(p + 1)
            val n = h * ((w + 7) shr 3)
            glyphs[ch] = Font.Glyph(w, h, d.copyOfRange(p + 2, p + 2 + n))
            p += 2 + n
        }
        return Font(FONT_HEIGHTS[i], glyphs)
    }

    // ---------------------------------------------------------------- HUGO.BSF
    class Boot(val checksumOk: Boolean, val registered: Boolean, val pbswitch: String, val distributor: String, val exitText: String)

    fun bootInfo(): Boot {
        val key = "Copyright 1992, David P Gray, Gray Design Associates".toByteArray(Charsets.ISO_8859_1)
        fun xor(from: Int, n: Int) = ByteArray(n) { (boot[from + it].toInt() xor key[it % key.size].toInt()).toByte() }
        var chk = 0
        for (i in 0 until 44) chk = chk xor boot.u8(i)
        val h = xor(0, 44)
        val exitLen = h.u16(42)
        val exit = if (boot.size >= 44 + exitLen) String(xor(44, exitLen), Charsets.ISO_8859_1) else ""
        return Boot(chk == 0, h[1].toInt() != 0, h.cstr(2).take(8), h.cstr(10).take(32), exit)
    }

    companion object {
        /** Case-insensitive lookup (the originals are upper case; Linux file systems care). */
        fun findFile(dir: File, name: String): File =
            dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw java.io.FileNotFoundException("File not found:\n${File(dir, name)}")
        const val OVL_SIZE = 8000
        val FONT_HEIGHTS = intArrayOf(5, 7, 8)
    }
}

/** UIF bitmap font. Glyph bits: MSB = leftmost pixel. */
class Font(val height: Int, val glyphs: Array<Glyph?>) {
    class Glyph(val width: Int, val height: Int, val bits: ByteArray) {
        fun pixel(x: Int, y: Int): Boolean {
            val bpr = (width + 7) shr 3
            return (bits.u8(y * bpr + (x shr 3)) and (0x80 shr (x and 7))) != 0
        }
    }
    fun glyph(c: Char): Glyph? = glyphs.getOrNull(c.code and 0x7F)
    /** StringLength @1010:27f2: sum of (width + 1). */
    fun width(s: String): Int = s.sumOf { (glyph(it)?.width ?: 0) + 1 }
}
