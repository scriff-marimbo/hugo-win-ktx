package hugo.platform

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.KeyboardFocusManager
import java.awt.SecondaryLoop
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JInternalFrame
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.event.InternalFrameAdapter
import javax.swing.event.InternalFrameEvent
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.UIManager
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

/**
 * A modal dialog drawn inside the game window (a JInternalFrame on the frame's layered pane),
 * not a separate top-level window: closing it never hands focus to the window manager, which on
 * some Linux desktops gave it to another application. [open] blocks like JDialog.setVisible
 * (it pumps events in a secondary loop) until [dispose].
 */
open class ModalDialog(private val owner: Window?, title: String, centreButtons: Boolean = false) :
    JInternalFrame(title, false, true, false, false) {

    val body = JPanel(BorderLayout(0, 8))
    private val buttonGrid = JPanel(GridLayout(1, 0, 8, 0))
    /** Escape key; null = Escape does nothing. */
    var onEscape: (() -> Unit)? = { dispose() }
    /** The title bar's close box. */
    var onCloseBox: () -> Unit = { dispose() }
    /** Gets the keyboard when the dialog opens (default: the first button). */
    var initialFocus: JComponent? = null
    private var firstButton: JButton? = null
    private var loop: SecondaryLoop? = null
    private var closed = false

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        frameIcon = NO_ICON                  // no Java cup in the title bar (GTK puts it back for null)
        val root = JPanel(BorderLayout(0, 12))
        root.border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
        root.add(body, BorderLayout.CENTER)
        val row = JPanel(FlowLayout(if (centreButtons) FlowLayout.CENTER else FlowLayout.RIGHT, 0, 0))
        row.add(buttonGrid)
        root.add(row, BorderLayout.SOUTH)
        contentPane = root
        addInternalFrameListener(object : InternalFrameAdapter() {
            override fun internalFrameClosing(e: InternalFrameEvent) = onCloseBox()
        })
    }

    private companion object {
        /** Zero-size and not a UIResource, so no look and feel swaps its own icon in. */
        val NO_ICON = object : Icon {
            override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) {}
            override fun getIconWidth() = 0
            override fun getIconHeight() = 0
        }
    }

    private fun action(f: () -> Unit) = object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = f() }

    /** Add a button to the row; the first one added is the default (Enter) button. */
    fun button(label: String, icon: Icon? = null, action: () -> Unit): JButton {
        val b = if (icon != null) JButton(icon).apply { isFocusPainted = false; toolTipText = label; accessibleContext.accessibleName = label; preferredSize = Dimension(88, 44) }
                else JButton(label)
        b.addActionListener { action() }
        buttonGrid.add(b)
        if (firstButton == null) { firstButton = b; rootPane.defaultButton = b }
        return b
    }

    /**
     * Enter (sent by the App whatever has the keyboard focus): a focused button inside the
     * dialog, a text field's own action (Save's description, the prompt), else the first button.
     */
    fun pressEnter() {
        when (val f = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner) {
            is JButton -> if (SwingUtilities.isDescendingFrom(f, this)) f.doClick(0) else firstButton?.doClick(0)
            is JTextField -> if (SwingUtilities.isDescendingFrom(f, this) && f.actionListeners.isNotEmpty()) f.postActionEvent() else firstButton?.doClick(0)
            else -> firstButton?.doClick(0)
        }
    }

    fun pressEscape() { onEscape?.invoke() }

    fun focusInside() { (initialFocus ?: firstButton ?: this).requestFocusInWindow() }

    /** GTK draws the Java cup on the title bar's window-menu button whatever the frame icon is: hide that button. */
    private fun hideMenuButton() {
        val north = (ui as? javax.swing.plaf.basic.BasicInternalFrameUI)?.northPane ?: return
        fun walk(c: java.awt.Component) {
            if (c is JButton && (c.name ?: "").contains("menuButton", ignoreCase = true)) c.isVisible = false
            if (c is java.awt.Container) c.components.forEach { walk(it) }
        }
        walk(north)
    }

    fun open() {
        hideMenuButton()
        val layers = (owner as? RootPaneContainer)?.layeredPane ?: return
        pack()
        val w = minOf(width, layers.width); val h = minOf(height, layers.height)
        setBounds((layers.width - w) / 2, (layers.height - h) / 2, w, h)
        Dialogs.push(this, layers)
        layers.add(this, JLayeredPane.MODAL_LAYER)
        layers.moveToFront(this)
        isVisible = true
        runCatching { isSelected = true }
        focusInside()
        if (closed) return
        val l = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        loop = l
        l.enter()
    }

    override fun dispose() {
        if (closed) return
        closed = true
        val layers = parent
        isVisible = false
        layers?.remove(this)
        layers?.repaint(bounds.x, bounds.y, bounds.width, bounds.height)
        Dialogs.pop(this)
        loop?.exit()
    }
}

/** The original's CBoxDlg kinds (message, prompt, yes/no) plus a few helpers for the port's dialogs. */
object Dialogs {
    /** The Box dialog's picture buttons (icons 6 = check, 4 = X from the EXE). */
    var checkIcon: Icon? = null
    var crossIcon: Icon? = null

    /** Told when a modal dialog opens (true) and closes (false). */
    var onModal: (Boolean) -> Unit = {}

    private val stack = ArrayList<ModalDialog>()
    /** The dialog that has the keyboard, if any. */
    val top: ModalDialog? get() = stack.lastOrNull()

    /** Covers the window under the dialogs so nothing else can be clicked. */
    private class Blocker : JComponent() {
        init {
            isOpaque = false
            val swallow = object : MouseAdapter() { override fun mousePressed(e: MouseEvent) { top?.focusInside() } }
            addMouseListener(swallow); addMouseMotionListener(swallow); addMouseWheelListener(swallow)
        }
    }
    private val blocker = Blocker()
    private val follow = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) { blocker.setBounds(0, 0, e.component.width, e.component.height) }
    }

    fun push(d: ModalDialog, layers: JLayeredPane) {
        if (stack.isEmpty()) {
            blocker.setBounds(0, 0, layers.width, layers.height)
            layers.add(blocker, JLayeredPane.MODAL_LAYER - 1)
            layers.addComponentListener(follow)
        }
        stack += d
        onModal(true)
    }

    fun pop(d: ModalDialog) {
        if (!stack.remove(d)) return
        if (stack.isEmpty()) {
            (blocker.parent as? JLayeredPane)?.let { it.removeComponentListener(follow); it.remove(blocker); it.repaint() }
        }
        onModal(false)
        top?.let { runCatching { it.isSelected = true }; it.focusInside() }
    }

    /** Box with buttons [labels]; returns the index chosen, or [closeResult] for Escape / the close box. */
    private fun box(owner: Window?, title: String, text: String, labels: List<String>, escape: Boolean, closeResult: Int,
                    field: JTextField? = null): Int {
        var result = closeResult
        val d = ModalDialog(owner, title, centreButtons = true)
        d.body.add(textBlock(text), BorderLayout.CENTER)
        if (field != null) d.body.add(field, BorderLayout.SOUTH)
        val icons = labels.map { when (it) { "OK", "Yes" -> checkIcon; "No" -> crossIcon; else -> null } }
        val pictures = icons.all { it != null }
        labels.forEachIndexed { i, l -> d.button(l, if (pictures) icons[i] else null) { result = i; d.dispose() } }
        d.onEscape = if (escape) ({ result = closeResult; d.dispose() }) else null
        d.onCloseBox = { result = closeResult; d.dispose() }
        if (field != null) { field.addActionListener { result = 0; d.dispose() }; d.initialFocus = field }
        d.open()
        return result
    }

    fun message(owner: Window?, title: String, text: String) { box(owner, title, text, listOf("OK"), escape = true, closeResult = 0) }
    fun okCancel(owner: Window?, title: String, text: String) = box(owner, title, text, listOf("OK", "Cancel"), escape = true, closeResult = 1) == 0
    fun yesNo(owner: Window?, title: String, text: String) = box(owner, title, text, listOf("Yes", "No"), escape = false, closeResult = 1) == 0

    fun prompt(owner: Window?, title: String, text: String): String? {
        val f = asciiField(64, "")
        return if (box(owner, title, text, listOf("OK"), escape = false, closeResult = -1, field = f) == 0) f.text else null
    }

    /** Wrapped, read-only text sized to its longest line (260..520 px wide). */
    fun textBlock(text: String): JComponent {
        val ta = JTextArea(text)
        ta.isEditable = false; ta.isFocusable = false; ta.isOpaque = false
        ta.lineWrap = true; ta.wrapStyleWord = true
        ta.font = UIManager.getFont("Label.font")
        UIManager.getColor("Label.foreground")?.let { ta.foreground = it }
        ta.border = null
        val fm = ta.getFontMetrics(ta.font)
        val w = (text.split('\n').maxOfOrNull { fm.stringWidth(it) } ?: 0).coerceIn(260, 520) + 4
        ta.setSize(w, Short.MAX_VALUE.toInt())
        ta.preferredSize = Dimension(w, ta.preferredSize.height)
        return ta
    }

    /** A text field that takes printable ASCII only (the game's font and parser know nothing else). */
    fun asciiField(max: Int, initial: String): JTextField {
        val f = JTextField(24)
        (f.document as AbstractDocument).documentFilter = object : DocumentFilter() {
            private fun clean(fb: FilterBypass, s: String?, removing: Int): String {
                val ok = s.orEmpty().filter { it.code in 0x20..0x7E }
                return ok.take(maxOf(0, max - (fb.document.length - removing)))
            }
            override fun insertString(fb: FilterBypass, offset: Int, s: String?, attr: AttributeSet?) = super.insertString(fb, offset, clean(fb, s, 0), attr)
            override fun replace(fb: FilterBypass, offset: Int, length: Int, s: String?, attrs: AttributeSet?) = super.replace(fb, offset, length, clean(fb, s, length), attrs)
        }
        f.text = initial
        return f
    }
}
