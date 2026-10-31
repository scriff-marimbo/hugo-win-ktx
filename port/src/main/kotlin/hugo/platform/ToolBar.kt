package hugo.platform

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.ToolTipManager

/**
 * CToolBar from the EXE: ? | music sound | save load | recall turbo | look inventory.
 * Painted by hand (not JButtons) so it keeps the Windows 3.x look in any Swing look and feel:
 * the same geometry and bevels the Nuklear version drew, with the EXE's own 16x15 bitmaps.
 */
class ToolBar(private val images: List<BufferedImage>, private val tools: List<Tool>) : JComponent() {
    class Tool(val image: Int, val tip: String, val gapBefore: Boolean, val checked: () -> Boolean, val action: () -> Unit)

    private val rects: List<Rectangle>
    private var hover = -1
    private var pressed = -1

    init {
        // Nuklear layout: 4 px window padding, 4 px between widgets, a 6 px spacer before each group
        var x = 4
        rects = tools.map { t ->
            if (t.gapBefore) x += 6 + 4
            Rectangle(x, 2, 24, 22).also { x += 24 + 4 }
        }
        preferredSize = Dimension(x, HEIGHT)
        minimumSize = Dimension(0, HEIGHT)
        isFocusable = false
        isOpaque = true
        ToolTipManager.sharedInstance().registerComponent(this)
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = setHover(hit(e))
            override fun mouseDragged(e: MouseEvent) = setHover(hit(e))
            override fun mouseExited(e: MouseEvent) = setHover(-1)
            override fun mousePressed(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                pressed = hit(e); repaint()
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val p = pressed
                pressed = -1; repaint()
                if (p >= 0 && p == hit(e)) tools[p].action()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun hit(e: MouseEvent) = rects.indexOfFirst { it.contains(e.point) }
    private fun setHover(i: Int) { if (i != hover) { hover = i; repaint() } }

    override fun getToolTipText(e: MouseEvent): String? = hit(e).takeIf { it >= 0 && it != pressed }?.let { tools[it].tip }

    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, HEIGHT)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        fun box(x: Int, y: Int, w: Int, h: Int, c: Color) { g.color = c; g.fillRect(x, y, w, h) }
        box(0, 0, width, height, FACE)
        box(0, height - 1, width, 1, Color.BLACK)              // bottom edge, like the MFC control bar
        for ((i, t) in tools.withIndex()) {
            val r = rects[i]
            val down = pressed == i && hover == i
            val sunk = down || t.checked()
            val x = r.x; val y = r.y; val w = r.width; val h = r.height
            box(x + 1, y, w - 2, 1, Color.BLACK); box(x + 1, y + h - 1, w - 2, 1, Color.BLACK)
            box(x, y + 1, 1, h - 2, Color.BLACK); box(x + w - 1, y + 1, 1, h - 2, Color.BLACK)
            box(x + 1, y + 1, w - 2, h - 2, FACE)
            if (sunk) {
                box(x + 1, y + 1, w - 2, 1, DARK); box(x + 1, y + 1, 1, h - 2, DARK)
            } else {
                box(x + 1, y + 1, w - 3, 1, Color.WHITE); box(x + 1, y + 1, 1, h - 3, Color.WHITE)
                box(x + 1, y + h - 2, w - 2, 1, DARK); box(x + w - 2, y + 1, 1, h - 2, DARK)
                box(x + 2, y + h - 3, w - 4, 1, DARK); box(x + w - 3, y + 2, 1, h - 4, DARK)
            }
            val o = if (sunk) 1 else 0
            images.getOrNull(t.image)?.let { g.drawImage(it, x + 4 + o, y + 3 + o, 16, 15, null) }
        }
        g.dispose()
    }

    companion object {
        const val HEIGHT = 28
        private val FACE = Color(192, 192, 192)
        private val DARK = Color(128, 128, 128)
    }
}
