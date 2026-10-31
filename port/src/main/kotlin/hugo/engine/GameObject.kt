package hugo.engine

import hugo.data.s16
import hugo.data.s8
import hugo.data.u16
import hugo.data.u8

/** One animation frame (Seq, 0x14 bytes in the original). Frames of a sequence form a ring. */
class Seq(val image: ByteArray, val bytesPerLine8: Int, val lines: Int) {
    var x1 = 0; var x2 = 0; var y1 = 0; var y2 = 0
    lateinit var next: Seq
}

class SeqList { var imageNbr = 0; var seq: Seq? = null }

object Cycle { const val INVISIBLE = 0; const val NOT_CYCLING = 1; const val FORWARD = 2; const val BACKWARD = 3 }
object PathType { const val USER = 0; const val AUTO = 1; const val QUIET = 2; const val CHASE = 3; const val CHASE2 = 4; const val WANDER = 5; const val WANDER2 = 6 }
object Priority { const val FOREGROUND = 0; const val BACKGROUND = 1; const val FLOATING = 2; const val OVEROVL = 3 }
object Generic { const val LOOK = 1; const val TAKE = 2; const val DROP = 4; const val LOOK_S = 8 }

/**
 * struct Object (0x54 bytes). Fields keep the original widths: setters truncate exactly like
 * stores into the C struct would. Pointer fields keep their DGROUP addresses.
 */
class GameObject(val index: Int) {
    var nounPtr = 0          // 00
    var dataPtr = 0          // 02
    var pathType = 0; set(v) { field = v.i16() }      // 04
    var vxPath = 0; set(v) { field = v.i16() }        // 06
    var vyPath = 0; set(v) { field = v.i16() }        // 08
    var actIndex = 0         // 0A
    var seqNumb = 0; set(v) { field = v.u8() }        // 0C
    var curr: Seq? = null    // 0E (far ptr)
    val seqList = Array(4) { SeqList() }              // 12
    var cycling = 0; set(v) { field = v.i16() }       // 2A
    var cycleNumb = 0; set(v) { field = v.u8() }      // 2C
    var frameInterval = 0; set(v) { field = v.u8() }  // 2D
    var frameTimer = 0; set(v) { field = v.u8() }     // 2E
    var radius = 0; set(v) { field = v.i8() }         // 2F
    var screen = 0; set(v) { field = v.u8() }         // 30
    var x = 0; set(v) { field = v.i16() }             // 32
    var y = 0; set(v) { field = v.i16() }             // 34
    var oldx = 0; set(v) { field = v.i16() }          // 36
    var oldy = 0; set(v) { field = v.i16() }          // 38
    var vx = 0; set(v) { field = v.i8() }             // 3A
    var vy = 0; set(v) { field = v.i8() }             // 3B
    var value = 0; set(v) { field = v.u8() }          // 3C
    var genericCmd = 0; set(v) { field = v and 0xFFFF } // 3E
    var cmdIndex = 0         // 40
    var carried = 0; set(v) { field = v.i16() }       // 42
    var state = 0; set(v) { field = v.u8() }          // 44
    var verbOnly = 0; set(v) { field = v.i16() }      // 46
    var priority = 0; set(v) { field = v.i16() }      // 48
    var viewx = 0; set(v) { field = v.i16() }         // 4A
    var viewy = 0; set(v) { field = v.i16() }         // 4C
    var direction = 0; set(v) { field = v.u8() }      // 4E
    var curSeqNum = 0; set(v) { field = v.u8() }      // 4F
    var curImageNum = 0; set(v) { field = v.u8() }    // 50
    var oldvx = 0; set(v) { field = v.i8() }          // 51
    var oldvy = 0; set(v) { field = v.i8() }          // 52

    /** Load from an 84-byte record. Runtime pointers are left alone; RestoreGame also keeps seqList and cmdIndex. */
    fun load(r: ByteArray, restoring: Boolean = false) {
        nounPtr = r.u16(0); dataPtr = r.u16(2); pathType = r.s16(4); vxPath = r.s16(6); vyPath = r.s16(8)
        actIndex = r.u16(0x0a); seqNumb = r.u8(0x0c)
        if (!restoring) for (j in 0 until 4) seqList[j].imageNbr = r.u16(0x12 + 6 * j)
        cycling = r.s16(0x2a); cycleNumb = r.u8(0x2c); frameInterval = r.u8(0x2d); frameTimer = r.u8(0x2e)
        radius = r.s8(0x2f); screen = r.u8(0x30); x = r.s16(0x32); y = r.s16(0x34); oldx = r.s16(0x36); oldy = r.s16(0x38)
        vx = r.s8(0x3a); vy = r.s8(0x3b); value = r.u8(0x3c); genericCmd = r.u16(0x3e)
        if (!restoring) cmdIndex = r.u16(0x40)
        carried = r.s16(0x42); state = r.u8(0x44); verbOnly = r.s16(0x46); priority = r.s16(0x48)
        viewx = r.s16(0x4a); viewy = r.s16(0x4c); direction = r.u8(0x4e); curSeqNum = r.u8(0x4f)
        curImageNum = r.u8(0x50); oldvx = r.s8(0x51); oldvy = r.s8(0x52)
    }

    /** Serialize to the 84-byte layout. Far pointers are written as 0 (the original rebuilds them). */
    fun toRecord(): ByteArray {
        val r = ByteArray(0x54)
        fun w16(o: Int, v: Int) { r[o] = v.toByte(); r[o + 1] = (v shr 8).toByte() }
        fun w8(o: Int, v: Int) { r[o] = v.toByte() }
        w16(0, nounPtr); w16(2, dataPtr); w16(4, pathType); w16(6, vxPath); w16(8, vyPath); w16(0x0a, actIndex)
        w8(0x0c, seqNumb)
        for (j in 0 until 4) w16(0x12 + 6 * j, seqList[j].imageNbr)
        w16(0x2a, cycling); w8(0x2c, cycleNumb); w8(0x2d, frameInterval); w8(0x2e, frameTimer); w8(0x2f, radius)
        w8(0x30, screen); w16(0x32, x); w16(0x34, y); w16(0x36, oldx); w16(0x38, oldy); w8(0x3a, vx); w8(0x3b, vy)
        w8(0x3c, value); w16(0x3e, genericCmd); w16(0x40, cmdIndex); w16(0x42, carried); w8(0x44, state)
        w16(0x46, verbOnly); w16(0x48, priority); w16(0x4a, viewx); w16(0x4c, viewy); w8(0x4e, direction)
        w8(0x4f, curSeqNum); w8(0x50, curImageNum); w8(0x51, oldvx); w8(0x52, oldvy)
        return r
    }

    /** Current frame; only valid for objects with images. */
    val img: Seq get() = curr!!
}
