package hugo.engine

import hugo.data.u16
import hugo.data.s16
import hugo.data.u32
import hugo.data.cstr
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * HHHn.SAV in the original byte layout (DAT_FORMATS.md section 10), so saves made by the
 * Windows original load here and the other way round.
 */
class SaveGame(private val e: Engine) {
    /** Length of HHH00.SAV; every save must have exactly this length (InitSaveState). */
    var saveMagic = -1L

    fun file(slot: Int): File = if (slot == -1) File(e.saveDir, "HHH00.SAV") else File(e.saveDir, "HHH$slot.SAV")

    private class Out : ByteArrayOutputStream() {
        fun w8(v: Int) = write(v and 0xFF)
        fun w16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
        fun w32(v: Long) { w16((v and 0xFFFF).toInt()); w16(((v shr 16) and 0xFFFF).toInt()) }
        fun bytes(b: ByteArray, n: Int = b.size) { for (i in 0 until n) w8(if (i < b.size) b[i].toInt() else 0) }
    }

    /** SaveGame @1010:335a. */
    fun saveGame(slot: Int, description: String) {
        val o = Out()
        o.w32(0)
        o.bytes("v1.5".toByteArray(), 5)
        o.bytes(description.take(31).toByteArray(Charsets.ISO_8859_1), 32)
        for (obj in e.objects) { e.objs.saveSeq(obj); o.bytes(obj.toRecord()) }
        o.w8(e.heroImage)
        o.w16(e.score); o.w16(e.storyModeFl); o.w16(e.jumpExitFl); o.w16(e.gameOverFl)
        for (i in 0 until 17) o.w8(e.screenStates[i])
        for (i in 0 until e.tables.numPoints) {
            o.w8(e.tables.pointScores[i]); o.w8(0); o.w16(if (e.pointsScored[i]) 1 else 0)
        }
        // SaveEvents @1010:5e34
        val s = e.scheduler
        fun idx(ev: Scheduler.Event?) = ev?.index ?: -1
        o.w32(s.getTicks())
        o.w16(e.tables.actionBase)
        o.w16(idx(s.freeEvent)); o.w16(idx(s.headEvent)); o.w16(idx(s.tailEvent))
        for (ev in s.events) {
            o.w16(ev.action); o.w16(ev.localFl); o.w32(ev.time); o.w16(idx(ev.prev)); o.w16(idx(ev.next))
        }
        // SavePal @1010:2154: 256 x u16 remap table
        for (i in 0 until 256) o.w16(e.display.remap[i])
        val m = e.maze
        o.w16(m.enabled); o.w8(m.size); o.w8(0)
        o.w16(m.x1); o.w16(m.y1); o.w16(m.x2); o.w16(m.y2); o.w16(m.x3); o.w16(m.x4)
        o.w8(m.firstScreen); o.w8(0)
        val b = o.toByteArray()
        val n = b.size
        b[0] = n.toByte(); b[1] = (n shr 8).toByte(); b[2] = (n shr 16).toByte(); b[3] = (n shr 24).toByte()
        try { file(slot).writeBytes(b) }
        catch (ex: Exception) { e.host.message("HugoWin Error", "Unable to write file:\n${file(slot)}") }
    }

    /** Description of a slot, or null when it does not exist (BuildSaveSlotList @1010:1352). */
    fun description(slot: Int): String? {
        val f = file(slot)
        if (!f.isFile) return null
        val b = f.readBytes()
        return if (b.size >= 41) b.cstr(9).take(32) else null
    }

    /** RestoreGame @1010:34b2 (DoRestore adds RestoreScreen). */
    fun restoreGame(slot: Int) {
        e.initStatus()
        val f = file(slot)
        val b = try { f.readBytes() } catch (ex: Exception) {
            e.host.message("HugoWin Error", "Unable to open file:\n$f"); return
        }
        if (b.size < 9 || b.u32(0) != saveMagic || String(b, 4, 4, Charsets.ISO_8859_1) != "v1.5" || b[8].toInt() != 0) return
        var p = 41
        if (e.heroImage != 0) e.objs.swapImages(0, e.heroImage)
        for (obj in e.objects) {
            obj.load(b.copyOfRange(p, p + 0x54), restoring = true)
            p += 0x54
        }
        // The original's curr pointer comes back as a stale far pointer; SaveSeq inside
        // SwapImages must not match it, so the saved curSeqNum/curImageNum win.
        val savedHero = b[p].toInt() and 0xFF; p++
        if (savedHero != 0) { e.hero.curr = null; e.objs.swapImages(0, savedHero) }
        e.heroImage = savedHero
        e.score = b.s16(p); e.storyModeFl = b.s16(p + 2); e.jumpExitFl = b.s16(p + 4); e.gameOverFl = b.s16(p + 6); p += 8
        for (i in 0 until 17) e.screenStates[i] = b[p + i].toInt() and 0xFF
        p += 17
        for (i in 0 until e.tables.numPoints) { e.pointsScored[i] = b.u16(p + 4 * i + 2) != 0 }
        p += 4 * e.tables.numPoints
        for (obj in e.objects) e.objs.restoreSeq(obj)
        // RestoreEvents @1010:5f58
        val s = e.scheduler
        val savedTick = b.u32(p); p += 4
        val base = b.u16(p); p += 2
        val free = b.s16(p); val head = b.s16(p + 2); val tail = b.s16(p + 4); p += 6
        if (base != e.tables.actionBase) e.box(0, e.str(hugo.data.Str.OBSOLETE))
        fun ev(i: Int) = if (i == -1) null else s.events[i]
        for (ev in s.events) {
            var a = b.u16(p)
            if (base != e.tables.actionBase) a = (a - base + e.tables.actionBase) and 0xFFFF
            ev.action = a; ev.localFl = b.u16(p + 2); ev.time = b.u32(p + 4)
            ev.prev = ev(b.s16(p + 8)); ev.next = ev(b.s16(p + 10))
            p += 12
        }
        s.freeEvent = ev(free); s.headEvent = ev(head); s.tailEvent = ev(tail)
        val now = s.getTicks()
        var q = s.headEvent
        while (q != null) { q.time = (q.time + (now - savedTick)) and 0xFFFFFFFFL; q = q.next }
        for (i in 0 until 256) e.display.remap[i] = b.u16(p + 2 * i) and 0xFF
        p += 512
        val m = e.maze
        m.enabled = b.u16(p); m.size = b[p + 2].toInt() and 0xFF
        m.x1 = b.s16(p + 4); m.y1 = b.s16(p + 6); m.x2 = b.s16(p + 8); m.y2 = b.s16(p + 10); m.x3 = b.s16(p + 12); m.x4 = b.s16(p + 14)
        m.firstScreen = b[p + 16].toInt() and 0xFF
    }

    /** InitSaveState @1010:368c. */
    fun initSaveState(regenerate: Boolean) {
        val f = file(-1)
        if (regenerate || !f.isFile) saveGame(-1, "")
        saveMagic = if (f.isFile) f.length() else -1
        if (saveMagic == -1L) e.host.message("HugoWin Error", "Unable to write file:\n$f")
    }
}

/** struct maze at DGROUP:370a. */
class Maze {
    var enabled = 0; var size = 0
    var x1 = 0; var y1 = 0; var x2 = 0; var y2 = 0; var x3 = 0; var x4 = 0
    var firstScreen = 0
}

/** CONFIG.DAT (50 bytes) plus the port's own options in hugo-port.properties. */
class Config {
    var musicOn = true
    var soundOn = true
    var turbo = false
    var winX = -1; var winY = -1
    var viewWidth = 640; var viewHeight = 368
    var bgSound = 0
    var musicVol = 85
    var soundVol = 100
    val playlist = BooleanArray(16)

    fun read(f: File): Boolean {
        val b = try { f.readBytes() } catch (e: Exception) { return false }
        if (b.size < 50) return false
        musicOn = b.u16(0) != 0; soundOn = b.u16(2) != 0; turbo = b.u16(4) != 0
        winX = b.s16(6); winY = b.s16(8); viewWidth = b.u16(10); viewHeight = b.u16(12); bgSound = b.u16(14)
        musicVol = b[16].toInt() and 0xFF; soundVol = b[17].toInt() and 0xFF
        for (i in 0 until 16) playlist[i] = b.u16(18 + 2 * i) != 0
        return true
    }

    fun write(f: File) {
        val b = ByteArray(50)
        fun w16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        w16(0, if (musicOn) 1 else 0); w16(2, if (soundOn) 1 else 0); w16(4, if (turbo) 1 else 0)
        w16(6, winX); w16(8, winY); w16(10, viewWidth); w16(12, viewHeight); w16(14, bgSound)
        b[16] = musicVol.toByte(); b[17] = soundVol.toByte()
        for (i in 0 until 16) w16(18 + 2 * i, if (playlist[i]) 1 else 0)
        try { f.writeBytes(b) } catch (_: Exception) {}
    }
}
