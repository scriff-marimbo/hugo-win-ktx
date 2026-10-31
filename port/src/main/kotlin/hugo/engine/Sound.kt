package hugo.engine

/** SoundHandler (1010:9100..9702): MIDI tunes 0..11 and 8-bit PCM effects with priorities. */
class Sound(private val e: Engine) {
    private val cfg get() = e.config
    /** Last tune started (DGROUP:69d2). */
    var currentTune = 0
    private var priority = 0          // 37ba
    private var musicActive = false   // 37bc
    private var soundActive = false   // 37be

    fun init() {
        musicActive = cfg.musicOn
        soundActive = cfg.soundOn
    }

    /** PlayMusic @1010:93c6. */
    fun playMusic(tune: Int) {
        if (!cfg.musicOn) return
        currentTune = tune
        val midi = e.dat.sound(tune) ?: return
        e.host.playMusic(midi, cfg.musicVol)
    }

    /** PlayNextTune @1010:9158: called when a tune ends (MCI_NOTIFY_SUCCESSFUL). */
    fun playNextTune() {
        var i = 0
        do {
            currentTune++
            if (currentTune > 15) currentTune = 0
        } while (!cfg.playlist[currentTune] && ++i < 16)
        if (cfg.playlist[currentTune]) playMusic(currentTune)
    }

    /** PlaySound @1010:93f8. [channel] 0 = both speakers, 1 = right, 2 = left. */
    fun playSound(i: Int, channel: Int, prio: Int) {
        if (!cfg.soundOn) return
        if (e.host.isSoundPlaying()) {
            if (prio < priority) return
            e.host.stopSound()
        }
        priority = prio
        val pcm = e.dat.sound(i) ?: return
        e.host.playSound(pcm, cfg.soundVol)
    }

    fun stopMusic() = e.host.stopMusic()

    /** ToggleMusic @1010:9130 + InitSound(2). */
    fun toggleMusic() {
        if (cfg.musicOn) stopMusic()
        cfg.musicOn = !cfg.musicOn
        sync()
    }

    /** ToggleSound @1010:914a + InitSound(2). */
    fun toggleSound() { cfg.soundOn = !cfg.soundOn; sync() }

    /** InitSound(2): bring the devices in line with the on/off settings. */
    fun sync() {
        if (musicActive && !cfg.musicOn) { stopMusic(); musicActive = false }
        else if (!musicActive && cfg.musicOn) { playMusic(currentTune); musicActive = true }
        if (soundActive && !cfg.soundOn) { e.host.stopSound(); soundActive = false }
        else if (!soundActive && cfg.soundOn) soundActive = true
    }

    fun setMusicVolume(v: Int) { cfg.musicVol = v.coerceIn(0, 100); e.host.setMusicVolume(cfg.musicVol) }
    fun setSoundVolume(v: Int) { cfg.soundVol = v.coerceIn(0, 100) }

    /** Tunes dialog: apply a new playlist; start the first tune if nothing enabled is playing. */
    fun setPlaylist(enabled: BooleanArray) {
        enabled.copyInto(cfg.playlist)
    }

    fun shutdown() { e.host.stopMusic(); e.host.stopSound() }
}
