package hugo.data

/**
 * Windows resources in HUGOWIN1.EXE (NE resource table): the toolbar bitmap (IDR_MAINFRAME)
 * and the 32x32 icons (Hugo, Penelope, the X / ? / check marks used on dialog buttons).
 * Images are decoded to 0xAARRGGBB.
 */
class Resources(private val exe: ByteArray) {
    class Image(val width: Int, val height: Int, val argb: IntArray)

    private class Res(val type: Int, val id: Int, val offset: Int, val length: Int)

    private val table: List<Res> = buildList {
        val ne = exe.u16(0x3c) or (exe.u16(0x3e) shl 16)
        var p = ne + exe.u16(ne + 0x24)
        val shift = exe.u16(p); p += 2
        while (true) {
            val type = exe.u16(p); if (type == 0) break
            val count = exe.u16(p + 2); p += 8
            repeat(count) {
                add(Res(type, exe.u16(p + 6), exe.u16(p) shl shift, exe.u16(p + 2) shl shift))
                p += 12
            }
        }
    }

    private fun find(type: Int, id: Int) = table.firstOrNull { it.type == (type or 0x8000) && it.id == (id or 0x8000) }

    /** 4-bit DIB (BITMAPINFOHEADER + 16-colour palette, bottom-up rows). */
    private fun dib(o: Int, height: Int?, withMask: Boolean): Image {
        val hs = exe.u32(o).toInt()
        val w = exe.u32(o + 4).toInt()
        val h = height ?: exe.u32(o + 8).toInt()
        val bpp = exe.u16(o + 14)
        require(bpp == 4) { "unexpected ${bpp}-bit bitmap" }
        val pal = IntArray(16) { i -> val q = o + hs + 4 * i; (0xFF shl 24) or (exe.u8(q + 2) shl 16) or (exe.u8(q + 1) shl 8) or exe.u8(q) }
        val px = o + hs + 64
        val stride = ((w * 4 + 31) / 32) * 4
        val mask = px + stride * h
        val mStride = ((w + 31) / 32) * 4
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val b = exe.u8(px + (h - 1 - y) * stride + x / 2)
            var c = pal[if (x % 2 == 0) b shr 4 else b and 15]
            if (withMask && (exe.u8(mask + (h - 1 - y) * mStride + x / 8) shr (7 - x % 8)) and 1 != 0) c = 0
            out[y * w + x] = c
        }
        return Image(w, h, out)
    }

    /** Toolbar strip: 9 buttons of 16x15 (help, music, sound, save, load, recall, turbo, look, inventory). */
    fun toolbar(): Image? = find(2, 2)?.let { dib(it.offset, null, false) }

    /** Icon n (1 Hugo, 2 Hugo, 3 Penelope, 4 X, 5 ?, 6 check). Icon DIB height is doubled (XOR + AND). */
    fun icon(n: Int): Image? = find(3, n)?.let { dib(it.offset, 32, true) }
}
