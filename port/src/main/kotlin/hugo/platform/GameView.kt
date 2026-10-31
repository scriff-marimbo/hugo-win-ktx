package hugo.platform

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.JComponent

/** The 320x184 game picture, scaled into the component with Java2D. */
class GameView(var scale: PortSettings.Scale, var smooth: Boolean) : JComponent() {
    private val image = BufferedImage(320, 184, BufferedImage.TYPE_INT_RGB)
    private val pixels = (image.raster.dataBuffer as DataBufferInt).data
    private val lock = Any()
    private val pending = IntArray(320 * 184)
    private var dirty = false

    init {
        isOpaque = true
        isFocusable = true
        focusTraversalKeysEnabled = false
        minimumSize = Dimension(320, 184)
    }

    /** Called on the game thread with a new frame (0xRRGGBB). */
    fun submit(compose: (IntArray) -> Unit) {
        synchronized(lock) { compose(pending); dirty = true }
        repaint()
    }

    /** Where the picture goes in the component, in component coordinates. */
    fun viewRect(): Rectangle {
        val aw = width; val ah = height
        return when (scale) {
            PortSettings.Scale.STRETCH -> Rectangle(0, 0, aw, ah)
            PortSettings.Scale.ASPECT -> {
                val s = minOf(aw / 320.0, ah / 184.0)
                val w = Math.round(320 * s).toInt(); val h = Math.round(184 * s).toInt()
                Rectangle((aw - w) / 2, (ah - h) / 2, w, h)
            }
            PortSettings.Scale.INTEGER -> {
                val s = maxOf(1, minOf(aw / 320, ah / 184))
                Rectangle((aw - 320 * s) / 2, (ah - 184 * s) / 2, 320 * s, 184 * s)
            }
        }
    }

    override fun paintComponent(g0: Graphics) {
        synchronized(lock) { if (dirty) { System.arraycopy(pending, 0, pixels, 0, pixels.size); dirty = false } }
        val g = g0 as Graphics2D
        g.color = Color.BLACK
        g.fillRect(0, 0, width, height)
        val r = viewRect()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            if (smooth) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(image, r.x, r.y, r.width, r.height, null)
    }
}
