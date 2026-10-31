package hugo.engine

import hugo.data.Font

/**
 * The original's DIB buffers and the dirty-rectangle display list (DisplayList @1010:263a).
 * The platform layer shows rows VIEW_Y..VIEW_Y+VIEW_H of [front] through the palette remap.
 */
class Display(private val e: Engine) {
    val back = ByteArray(XPIX * YPIX)          // 1040: clean background (dib_a)
    val backSave = ByteArray(XPIX * YPIX)      // 1048: copy while the icon bar is shown
    val front = ByteArray(XPIX * YPIX)         // 1050: background + objects (dib_b)
    val gui = ByteArray(XPIX * YPIX)           // 1030: UIF icon sheet
    val iconBar = ByteArray(XPIX * INV_DY)     // 1058

    /** Palette remap table (DGROUP:7006, 256 x u16): displayed colour = palette[remap[pixel]]. */
    val remap = IntArray(256) { it }

    class Rect(var x: Int, var y: Int, var dx: Int, var dy: Int)

    private val addList = ArrayList<Rect>()       // 5a9e (max 16)
    private val restoreList = ArrayList<Rect>()   // 5a1e
    /** Set by InitNewScreenDisplay: the whole view gets redrawn, skip one D_DISPLAY. */
    var newScreenFl = false

    fun moveImage(src: ByteArray, x1: Int, y1: Int, dx: Int, dy: Int, w1: Int, dst: ByteArray, x2: Int, y2: Int, w2: Int) {
        for (r in 0 until dy) System.arraycopy(src, (y1 + r) * w1 + x1, dst, (y2 + r) * w2 + x2, dx)
    }

    // -------------------------------------------------------------- display list
    fun displayInit() {
        restoreList.clear(); addList.clear()
        back.copyInto(front)
    }

    fun displayAdd(x: Int, y: Int, dx: Int, dy: Int) {
        if (addList.size < 16) addList += Rect(x, y, dx, dy)
        else e.host.warn("Display list exceeded")
    }

    /** D_RESTORE: copy each rect of last frame back from the clean background. */
    fun displayRestore() {
        restoreList.clear()
        for (r in addList) {
            restoreList += r
            val x = r.x.coerceIn(0, XPIX); val y = r.y.coerceIn(0, YPIX)
            val dx = r.dx.coerceAtMost(XPIX - x); val dy = r.dy.coerceAtMost(YPIX - y)
            if (dx > 0 && dy > 0) moveImage(back, x, y, dx, dy, XPIX, front, x, y, XPIX)
        }
        addList.clear()
    }

    /** D_DISPLAY: in the original this blits dirty rects; we show the whole front buffer. */
    fun displayFlush() {
        if (newScreenFl) newScreenFl = false
        e.host.frameReady()
    }

    // -------------------------------------------------------------- sprites
    /** DisplayFrame @1010:237a: merge an object frame into [front], honouring the overlay. */
    fun displayFrame(sx: Int, sy: Int, seq: Seq, foreFl: Boolean) {
        var ovlState = if (foreFl) 1 else 0          // 0 undefined, 1 foreground, 2 background
        val img = seq.image
        var src = 0
        var dst = sy * XPIX + sx
        for (y in 0 until seq.lines) {
            for (x in 0..seq.x2) {
                val p = img[src]
                if (p.toInt() != 0 && dst in 0 until XPIX * YPIX) {
                    if (bit(e.objs.overlay, dst)) {
                        if (ovlState == 0) ovlState = if (findOvl(seq, dst, y)) 1 else 2
                        if (ovlState == 1) front[dst] = p
                    } else front[dst] = p
                }
                src++; dst++
            }
            src += seq.bytesPerLine8 - seq.x2 - 1
            dst += XPIX - seq.x2 - 1
        }
        displayAdd(sx, sy, seq.x2 + 1, seq.lines)
    }

    /** FindOvl @1010:232c: is there a base-overlay bit below this pixel within the frame? */
    private fun findOvl(seq: Seq, dst0: Int, y0: Int): Boolean {
        var dst = dst0
        for (y in y0 until seq.lines) {
            if (dst in 0 until XPIX * YPIX && bit(e.objs.ovlBase, dst)) return true
            dst += XPIX
        }
        return false
    }

    private fun bit(bits: ByteArray, pixel: Int) = (bits[pixel shr 3].toInt() and (0x80 shr (pixel and 7))) != 0

    // -------------------------------------------------------------- text
    var font: Font? = null
    private val fonts = arrayOfNulls<Font>(3)

    fun loadFont(i: Int) { font = fonts[i] ?: e.dat.font(i).also { fonts[i] = it } }
    fun fontHeight() = font!!.height
    fun stringLength(s: String) = font!!.width(s)
    fun center(s: String) = (XPIX - stringLength(s)) / 2

    fun writeStr(sx0: Int, sy: Int, s: String, color: Int) {
        var sx = if (sx0 == -1) center(s) else sx0
        val f = font!!
        for (c in s) {
            val g = f.glyph(c) ?: continue
            for (y in 0 until g.height) for (x in 0 until g.width) {
                if (g.pixel(x, y)) {
                    val px = sx + x; val py = sy + y
                    if (px in 0 until XPIX && py in 0 until YPIX) front[py * XPIX + px] = color.toByte()
                }
            }
            sx += g.width + 1
        }
    }

    fun shadowStr(sx0: Int, sy: Int, s: String, color: Int) {
        val sx = if (sx0 == -1) center(s) else sx0
        writeStr(sx + 1, sy + 1, s, 0)
        writeStr(sx, sy, s, color)
    }

    /** RemapPal @1010:213c. */
    fun remapPal(oldIndex: Int, newIndex: Int) { remap[oldIndex and 0xFF] = newIndex and 0xFF }
    fun resetPal() { for (i in remap.indices) remap[i] = i }

    /** InitNewScreenDisplay @1010:5318. */
    fun initNewScreenDisplay() {
        displayInit()
        newScreenFl = true
    }

    companion object {
        const val XPIX = 320
        const val YPIX = 200
        const val VIEW_Y = 8
        const val VIEW_H = 184
        const val INV_DX = 32
        const val INV_DY = 32
    }
}
