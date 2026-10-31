package hugo.engine

/**
 * Services the engine needs from the platform. Blocking calls (box, message) are made on the
 * game thread and must block until the user answers, exactly like the original's modal
 * dialogs (the game does not tick meanwhile).
 */
interface Host {
    /** Box @1010:6462. type 0/1 = message, 2 = prompt (returns typed text), 3 = yes/no ("yes" or null). */
    fun box(type: Int, text: String): String?

    /** Win32 MessageBox equivalent. Returns true for OK/Yes. */
    fun message(title: String, text: String, okCancel: Boolean = false): Boolean

    fun warn(text: String) { message("HugoWin Warning", text) }

    /** Called after each D_DISPLAY: the front buffer has a new frame. */
    fun frameReady() {}

    /** Status bar changed (prompt line or score). */
    fun statusChanged() {}

    fun playSound(pcm: ByteArray, volumePercent: Int)
    fun isSoundPlaying(): Boolean
    fun stopSound()

    fun playMusic(midi: ByteArray, volumePercent: Int)
    fun stopMusic()
    fun setMusicVolume(volumePercent: Int)

    fun beep() {}

    /** The About box shown at start-up (modal). */
    fun about() {}

    /** The game asked to end (EXIT action / Game -> Exit). */
    fun quit()
}

/** Host that does nothing - for tests and headless runs. Boxes are answered from a queue. */
open class HeadlessHost : Host {
    val log = ArrayList<String>()
    val answers = ArrayDeque<String?>()
    var quitRequested = false
    override fun box(type: Int, text: String): String? {
        log += "BOX$type: $text"
        return if (type >= 2) answers.removeFirstOrNull() else "ok"
    }
    override fun message(title: String, text: String, okCancel: Boolean): Boolean { log += "MSG $title: $text"; return true }
    override fun playSound(pcm: ByteArray, volumePercent: Int) { log += "SOUND ${pcm.size}" }
    override fun isSoundPlaying() = false
    override fun stopSound() {}
    override fun playMusic(midi: ByteArray, volumePercent: Int) { log += "MUSIC ${midi.size}" }
    override fun stopMusic() {}
    override fun setMusicVolume(volumePercent: Int) {}
    override fun quit() { quitRequested = true }
}
