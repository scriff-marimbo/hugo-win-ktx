package hugo.platform

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.*
import org.lwjgl.system.MemoryUtil
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.midi.MetaEventListener
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequencer
import javax.sound.midi.Synthesizer
import javax.sound.midi.SysexMessage

/** Sound effects: 8-bit unsigned mono PCM at 11025 Hz through OpenAL (the original used waveOut). */
class Effects {
    private var device = 0L
    private var context = 0L
    private var source = 0
    private var buffer = 0
    var available = false; private set

    fun open() {
        try {
            device = alcOpenDevice(null as java.nio.ByteBuffer?)
            if (device == 0L) { System.err.println("OpenAL: no audio device, sound effects disabled"); return }
            val caps = ALC.createCapabilities(device)
            context = alcCreateContext(device, null as java.nio.IntBuffer?)
            alcMakeContextCurrent(context)
            AL.createCapabilities(caps)
            source = alGenSources()
            available = true
        } catch (t: Throwable) {
            System.err.println("OpenAL unavailable: ${t.message}")
        }
    }

    @Synchronized fun play(pcm: ByteArray, volumePercent: Int, rate: Int = 11025) {
        if (!available) return
        alSourceStop(source)
        alSourcei(source, AL_BUFFER, 0)
        if (buffer != 0) alDeleteBuffers(buffer)
        buffer = alGenBuffers()
        val bb = MemoryUtil.memAlloc(pcm.size)
        bb.put(pcm).flip()
        alBufferData(buffer, AL_FORMAT_MONO8, bb, rate)
        MemoryUtil.memFree(bb)
        alSourcei(source, AL_BUFFER, buffer)
        alSourcef(source, AL_GAIN, volumePercent / 100f)
        alSourcePlay(source)
    }

    @Synchronized fun isPlaying(): Boolean = available && alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING

    @Synchronized fun stop() { if (available) alSourceStop(source) }

    /** A short square-wave beep (MessageBeep when the command line is full). */
    fun beep(volumePercent: Int) {
        val n = 11025 / 12
        play(ByteArray(n) { if ((it / 14) % 2 == 0) 0xA0.toByte() else 0x60.toByte() }, volumePercent)
    }

    @Synchronized fun close() {
        if (!available) return
        available = false
        alSourceStop(source)
        alDeleteSources(source)
        if (buffer != 0) alDeleteBuffers(buffer)
        alcMakeContextCurrent(0L)
        alcDestroyContext(context)
        alcCloseDevice(device)
    }
}

/** Music: the game's MIDI files through Java's built-in synthesizer, optionally with a SoundFont. */
class Music(soundFont: File?, private val onTuneEnded: () -> Unit) {
    private var synth: Synthesizer? = null
    private var sequencer: Sequencer? = null
    private var paused = false
    var available = false; private set

    init {
        try {
            val s = MidiSystem.getSynthesizer()
            s.open()
            if (soundFont != null && soundFont.isFile) {
                try {
                    val sb = MidiSystem.getSoundbank(soundFont)
                    s.defaultSoundbank?.let { s.unloadAllInstruments(it) }
                    s.loadAllInstruments(sb)
                } catch (t: Throwable) { System.err.println("SoundFont ${soundFont}: ${t.message}") }
            }
            val q = MidiSystem.getSequencer(false)
            q.open()
            q.transmitter.receiver = s.receiver
            q.addMetaEventListener(MetaEventListener { if (it.type == 47) onTuneEnded() })
            synth = s; sequencer = q
            available = true
        } catch (t: Throwable) {
            System.err.println("MIDI unavailable, music disabled: ${t.message}")
        }
    }

    @Synchronized fun play(midi: ByteArray, volumePercent: Int) {
        val q = sequencer ?: return
        try {
            q.stop()
            q.sequence = MidiSystem.getSequence(ByteArrayInputStream(midi))
            q.tickPosition = 0
            setVolume(volumePercent)
            paused = false
            q.start()
        } catch (t: Throwable) { System.err.println("MIDI: ${t.message}") }
    }

    @Synchronized fun stop() { sequencer?.let { if (it.isOpen) it.stop() }; paused = false }

    /** OnActivateApp/PauseSound: stop the tune while the window is inactive, resume afterwards. */
    @Synchronized fun pause(p: Boolean) {
        val q = sequencer ?: return
        if (p && q.isRunning) { q.stop(); paused = true }
        else if (!p && paused) { q.start(); paused = false }
    }

    /** GM master volume (universal real-time SysEx), which Gervill understands. */
    @Synchronized fun setVolume(volumePercent: Int) {
        val s = synth ?: return
        val v = (volumePercent.coerceIn(0, 100) * 16383) / 100
        val msg = byteArrayOf(0xF0.toByte(), 0x7F, 0x7F, 0x04, 0x01, (v and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte(), 0xF7.toByte())
        try { s.receiver.send(SysexMessage(msg, msg.size), -1) } catch (_: Throwable) {}
    }

    fun close() {
        try { sequencer?.close() } catch (_: Throwable) {}
        try { synth?.close() } catch (_: Throwable) {}
    }
}
