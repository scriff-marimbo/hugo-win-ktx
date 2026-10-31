package hugo.engine

import hugo.engine.Display.Companion.XPIX
import hugo.engine.Display.Companion.YPIX

/** Object movement, collisions, boundaries and per-frame animation (segment 1010:3ecc..60c0). */
class Objects(private val e: Engine) {
    val boundary = ByteArray(8000)      // 1028:3b50 screen walls
    val objBound = ByteArray(8000)      // 1028:1c10 object baselines
    val overlay = ByteArray(8000)       // 1068:1f40 foreground mask
    val ovlBase = ByteArray(8000)       // 1068:0000 overlay base lines

    private val objs get() = e.objects
    private val hero get() = e.hero

    // ------------------------------------------------------------------ images
    /** ReadImage @1010:2caa for every object. */
    fun readObjectImages() {
        for (obj in objs) {
            if (obj.seqNumb == 0) continue
            val frames = e.dat.objectFrames(obj.index)
            var k = 0
            var last: Seq? = null
            for (j in 0 until obj.seqNumb) {
                var first: Seq? = null
                var prev: Seq? = null
                for (n in 0 until obj.seqList[j].imageNbr) {
                    val pcx = frames[k++]
                    val s = Seq(pcx.pixels, pcx.width, pcx.lines)
                    // Bounding box of non-transparent pixels, scanning the valid width only
                    val w = pcx.dataWidth
                    s.x1 = w; s.x2 = 0; s.y1 = pcx.lines; s.y2 = 0
                    for (y in 0 until pcx.lines) for (x in 0 until w) {
                        if (pcx.pixels[y * pcx.width + x].toInt() != 0) {
                            if (x < s.x1) s.x1 = x
                            if (x > s.x2) s.x2 = x
                            if (y < s.y1) s.y1 = y
                            if (y > s.y2) s.y2 = y
                        }
                    }
                    if (first == null) { first = s; obj.seqList[j].seq = s } else prev!!.next = s
                    prev = s; last = s
                }
                prev?.next = first!!
            }
            obj.curr = when (obj.cycling) {
                Cycle.BACKWARD -> last
                else -> obj.seqList[0].seq
            }
        }
    }

    /** SaveSeq @1010:3292. */
    fun saveSeq(obj: GameObject) {
        for (i in 0 until obj.seqNumb) {
            var q = obj.seqList[i].seq
            for (j in 0 until obj.seqList[i].imageNbr) {
                if (q === obj.curr) { obj.curSeqNum = i; obj.curImageNum = j; return }
                q = q!!.next
            }
        }
    }

    /** RestoreSeq @1010:331e. */
    fun restoreSeq(obj: GameObject) {
        if (obj.seqNumb == 0) return
        var q = obj.seqList[obj.curSeqNum].seq
        for (j in 0 until obj.curImageNum) q = q!!.next
        obj.curr = q
    }

    /** SwapImages @1010:60c0 (first object is always the hero). */
    fun swapImages(i1: Int, i2: Int) {
        val a = objs[i1]; val b = objs[i2]
        saveSeq(a)
        for (j in 0 until 4) {
            val t1 = a.seqList[j].imageNbr; val t2 = a.seqList[j].seq
            a.seqList[j].imageNbr = b.seqList[j].imageNbr; a.seqList[j].seq = b.seqList[j].seq
            b.seqList[j].imageNbr = t1; b.seqList[j].seq = t2
        }
        restoreSeq(a)
        b.curr = b.seqList[0].seq
        e.heroImage = if (e.heroImage == 0) i2 else 0
        a.y = a.y + (b.img.y2 - a.img.y2)
    }

    // ------------------------------------------------------------------ boundaries
    fun storeBoundary(x1: Int, x2: Int, y: Int) {
        if (y !in 0 until YPIX) return
        for (i in (x1 shr 3)..(x2 shr 3)) {
            val idx = y * 40 + i
            if (idx !in 0 until 8000) continue
            objBound[idx] = when {
                i == x2 shr 3 -> (objBound[idx].toInt() or (0xFF shl ((i shl 3) + 7 - x2))).toByte()
                i == x1 shr 3 -> (objBound[idx].toInt() or (0xFF ushr (x1 - (i shl 3)))).toByte()
                else -> 0xFF.toByte()
            }
        }
    }

    fun clearBoundary(x1: Int, x2: Int, y: Int) {
        if (y !in 0 until YPIX) return
        for (i in (x1 shr 3)..(x2 shr 3)) {
            val idx = y * 40 + i
            if (idx !in 0 until 8000) continue
            objBound[idx] = when {
                i == x2 shr 3 -> (objBound[idx].toInt() and (0xFF shl ((i shl 3) + 7 - x2)).inv()).toByte()
                i == x1 shr 3 -> (objBound[idx].toInt() and (0xFF ushr (x1 - (i shl 3))).inv()).toByte()
                else -> 0
            }
        }
    }

    private fun bound(idx: Int): Int =
        if (idx in 0 until 8000) (boundary[idx].toInt() or objBound[idx].toInt()) and 0xFF else 0

    private fun firstBit(b: Int): Int { if (b == 0) return 8; for (i in 0 until 8) if ((b shl i) and 0x80 != 0) return i; return 8 }
    private fun lastBit(b: Int): Int { if (b == 0) return 8; for (i in 7 downTo 0) if ((b shl i) and 0x80 != 0) return i; return -1 }

    /** DeltaX @1010:4454. */
    private fun deltaX(x1: Int, x2: Int, vx: Int, y: Int): Int {
        if (vx == 0) return 0
        if (vx > 0) {
            for (i in (x1 shr 3)..((vx + x2) shr 3)) {
                var b = firstBit(bound(y * 40 + i))
                if (b < 8) {
                    b += i * 8
                    if (b >= x1 && b <= vx + x2) return if (b < ((x2 - x1) shr 1) + x1) vx else b - x2 - 1
                }
            }
        } else {
            var i = x2 shr 3
            while (i >= (vx + x1) shr 3) {
                var b = lastBit(bound(y * 40 + i))
                if (b < 8) {
                    b += i * 8
                    if (b >= vx + x1 && b <= x2) return if (((x2 - x1) shr 1) + x1 < b) vx else b - x1 + 1
                }
                i--
            }
        }
        return vx
    }

    /** DeltaY @1010:4556. */
    private fun deltaY(x1: Int, x2: Int, vy: Int, y: Int): Int {
        if (vy == 0) return 0
        val inc = if (vy < 1) -1 else 1
        var j = inc + y
        while (j != inc + y + vy) {
            for (i in (x1 shr 3)..(x2 shr 3)) {
                var b = bound(j * 40 + i)
                if (b != 0) {
                    if (i == x2 shr 3) b = b and (0xFF shl ((i * 8 - x2 + 7) and 0x1F))
                    else if (i == x1 shr 3) b = b and (0xFF ushr ((x1 - i * 8) and 0x1F))
                    if (b and 0xFF != 0) return j - inc - y
                }
            }
            j += inc
        }
        return vy
    }

    // ------------------------------------------------------------------ movement
    /** MoveObjects @1010:3ecc. */
    fun moveObjects() {
        if (e.routeIndex >= 0) e.route.processRoute()
        val screen = e.screen
        for (obj in objs) {
            if (obj.screen != screen) continue
            var cur = obj.curr
            when (obj.pathType) {
                PathType.CHASE, PathType.CHASE2 -> {
                    var radius = obj.radius
                    if (radius < 0) radius = 5
                    val dx = hero.img.x1 - cur!!.x1 - obj.x + hero.x
                    val dy = hero.img.y2 - obj.y - cur.y2 + hero.y - 1
                    obj.vx = if (Math.abs(dx) > radius) { if (dx < 1) Math.max(dx, -obj.vxPath) else Math.min(dx, obj.vxPath) } else 0
                    obj.vy = if (Math.abs(dy) > radius) { if (dy < 1) Math.max(dy, -obj.vyPath) else Math.min(dy, obj.vyPath) } else 0
                    when (obj.seqNumb) {
                        2, 3 -> if (obj.vx != obj.oldvx) obj.curr = if (dx < 1) obj.seqList[1].seq else obj.seqList[0].seq
                        4 -> if (obj.vx == 0) {
                            if (obj.vx != obj.oldvx) obj.curr = if (dy < 0) obj.seqList[3].seq else obj.seqList[2].seq
                        } else if (obj.vx != obj.oldvx) obj.curr = if (dx < 1) obj.seqList[1].seq else obj.seqList[0].seq
                    }
                    if (obj.vx == 0 && obj.vy == 0) { obj.cycling = Cycle.NOT_CYCLING; boundaryCollision(obj) }
                    else obj.cycling = Cycle.FORWARD
                    obj.oldvx = obj.vx; obj.oldvy = obj.vy
                    cur = obj.curr
                }
                PathType.WANDER, PathType.WANDER2 -> {
                    if (Crt.rand() % 27 == 0) {
                        obj.vx = if (obj.vxPath and 0x7FFF == 0) 0 else (Crt.rand() % (obj.vxPath * 2)).i8() - obj.vxPath
                        obj.vy = if (obj.vyPath and 0x7FFF == 0) 0 else (Crt.rand() % (obj.vyPath * 2)).i8() - obj.vyPath
                        if (obj.seqNumb > 1) {
                            if (obj.vx == 0 && obj.seqNumb > 3) {
                                if (obj.vx != obj.oldvx) obj.curr = if (obj.vy < 1) obj.seqList[3].seq else obj.seqList[2].seq
                            } else if (obj.vx != obj.oldvx) obj.curr = if (obj.vx < 1) obj.seqList[1].seq else obj.seqList[0].seq
                        }
                        obj.oldvx = obj.vx; obj.oldvy = obj.vy
                        cur = obj.curr
                    }
                    if (obj.vx != 0 || obj.vy != 0) obj.cycling = Cycle.FORWARD
                }
            }
            if (obj.cycling != Cycle.INVISIBLE && obj.priority == Priority.FLOATING && cur != null)
                storeBoundary(cur.x1 + obj.x, cur.x2 + obj.x, cur.y2 + obj.y)
        }

        for (obj in objs) {
            if (obj.screen != screen || (obj.vx == 0 && obj.vy == 0)) continue
            val cur = obj.curr ?: continue
            val x1 = cur.x1 + obj.x
            val x2 = obj.x + cur.x2
            val y1 = obj.y + cur.y1
            val y2 = cur.y2 + obj.y
            val floating = obj.cycling != Cycle.INVISIBLE && obj.priority == Priority.FLOATING
            if (floating) clearBoundary(x1, x2, y2)
            val dx = deltaX(x1, x2, obj.vx, y2)
            if (obj.vx != dx) { boundaryCollision(obj); obj.vx = 0 }
            val dy = deltaY(x1, x2, obj.vy, y2)
            if (obj.vy != dy) { boundaryCollision(obj); obj.vy = 0 }
            if (floating) storeBoundary(x1, x2, y2)
            obj.x = obj.x + dx
            obj.y = obj.y + dy
            if (x1 < 10) obj.x = 20
            if (x2 > 310) obj.x = (x1 - x2) + 300
            if (y1 < 10) obj.y = 20
            if (y2 > 190) obj.y = (y1 - y2) + 180
            if (obj.vx == 0 && obj.vy == 0 && obj.pathType != PathType.WANDER2 && obj.pathType != PathType.CHASE2)
                obj.cycling = Cycle.NOT_CYCLING
        }

        for (obj in objs) {
            if (obj.screen == screen && obj.cycling != Cycle.INVISIBLE && obj.priority == Priority.FLOATING) {
                val cur = obj.curr ?: continue
                clearBoundary(cur.x1 + obj.oldx, cur.x2 + obj.oldx, cur.y2 + obj.oldy)
            }
        }
        if (e.maze.enabled != 0) e.processMaze()
    }

    /** BoundaryCollision @1010:51f0. */
    fun boundaryCollision(obj: GameObject) {
        if (obj === hero) {
            val x = (if (obj.vx < 1) obj.img.x1 else obj.img.x2) + obj.x
            val y = obj.img.y2 + obj.y
            val i = e.mouse.findExit(x, y, obj.screen)
            if (i >= 0) e.scheduler.insertActionList(e.tables.hotspots[i].actList)
        } else {
            var radius = obj.radius
            if (radius < 0) radius = 10
            if (Math.abs(hero.img.x1 - obj.img.x1 - obj.x + hero.x) <= radius &&
                Math.abs(hero.img.y2 - obj.img.y2 - obj.y + hero.y) <= radius)
                e.scheduler.insertActionList(obj.actIndex)
        }
    }

    // ------------------------------------------------------------------ drawing
    /** CompareObjectsY @1010:4816 (qsort comparator). */
    private fun compare(i1: Int, i2: Int): Int {
        val p1 = objs[i1]; val p2 = objs[i2]
        if (i1 == i2) return 0
        if (p1.priority != Priority.BACKGROUND) {
            if (p2.priority == Priority.BACKGROUND || p1.priority == Priority.FOREGROUND) return 1
            if (p2.priority != Priority.FOREGROUND)
                return ((p1.img.y2 - p2.img.y2) - p2.y + p1.y).i16()
        }
        return -1
    }

    /** UpdateImages @1010:4892. */
    fun updateImages() {
        val idx = IntArray(128)
        var n = 0
        for (obj in objs) if (obj.screen == e.screen && obj.cycling != Cycle.INVISIBLE) idx[n++] = obj.index
        Crt.qsort(idx, n, ::compare)
        for (k in 0 until n) {
            val obj = objs[idx[k]]
            if (obj.frameTimer != 0) obj.frameTimer = obj.frameTimer - 1
            val cur = obj.curr ?: continue
            val img: Seq = when (obj.cycling) {
                Cycle.NOT_CYCLING -> cur
                Cycle.FORWARD -> if (obj.frameTimer != 0) cur else cur.next
                Cycle.BACKWARD -> if (obj.frameTimer != 0) cur else prevOf(cur)
                else -> continue
            }
            e.display.displayFrame(obj.x, obj.y, img, obj.priority == Priority.OVEROVL)
        }
        for (k in 0 until n) {
            val obj = objs[idx[k]]
            if (obj.cycling == Cycle.INVISIBLE) continue
            val cur = obj.curr
            if (cur != null) when (obj.cycling) {
                Cycle.FORWARD -> if (obj.frameTimer == 0) {
                    obj.curr = cur.next
                    if (obj.frameInterval != 0 || obj.cycleNumb != 0) {
                        obj.frameTimer = obj.frameInterval
                        for (j in 0 until obj.seqNumb) {
                            if (obj.curr!!.next === obj.seqList[j].seq && obj.cycleNumb != 0) {
                                obj.cycleNumb = obj.cycleNumb - 1
                                if (obj.cycleNumb == 0) obj.cycling = Cycle.NOT_CYCLING
                            }
                        }
                    }
                }
                Cycle.BACKWARD -> if (obj.frameTimer == 0) {
                    obj.curr = prevOf(cur)
                    if (obj.frameInterval != 0 || obj.cycleNumb != 0) {
                        obj.frameTimer = obj.frameInterval
                        for (j in 0 until obj.seqNumb) {
                            if (obj.curr === obj.seqList[j].seq && obj.cycleNumb != 0) {
                                obj.cycleNumb = obj.cycleNumb - 1
                                if (obj.cycleNumb == 0) obj.cycling = Cycle.NOT_CYCLING
                            }
                        }
                    }
                }
            }
            obj.oldx = obj.x; obj.oldy = obj.y
        }
    }

    private fun prevOf(s: Seq): Seq { var p = s; while (p.next !== s) p = p.next; return p }

    // ------------------------------------------------------------------ hit testing
    /** FindObject @1010:4b6a: topmost useful object under (x, y), or -1. */
    fun findObject(x: Int, y: Int): Int {
        var found = -1
        var y2Max = 0
        for (obj in objs) {
            if (obj.screen != e.screen || (obj.genericCmd == 0 && obj.value == 0 && obj.cmdIndex == 0)) continue
            val cur = obj.curr
            if (cur == null || obj.cycling == Cycle.INVISIBLE) {
                if (cur == null && obj.vxPath != 0 && obj.carried == 0 &&
                    obj.oldx <= x && x < obj.vxPath + obj.oldx && obj.oldy <= y && y < obj.vyPath + obj.oldy &&
                    y2Max < obj.vyPath + obj.oldy - 1) {
                    y2Max = obj.vyPath + obj.oldy - 1
                    found = obj.index
                }
            } else if (obj.x <= x && x <= cur.x2 + obj.x && obj.y <= y && y <= cur.y2 + obj.y && y2Max < cur.y2 + obj.y) {
                y2Max = cur.y2 + obj.y
                found = obj.index
            }
        }
        return found
    }

    /** FindObjectSpace @1010:4c9c: a clear spot next to obj the hero can walk to. Returns (ok, x, y). */
    fun findObjectSpace(obj: GameObject): Triple<Boolean, Int, Int> {
        val cur = obj.img
        var y = cur.y2 + obj.y - 1
        fun blocked(x: Int, yy: Int) = yy in 0 until YPIX && x in 0 until XPIX &&
            (boundary[yy * 40 + (x shr 3)].toInt() and (0x80 shr (x % 8))) != 0
        var destx = cur.x1 + obj.x
        var ok = (destx until destx + 24).none { blocked(it, y) }
        if (!ok) { destx = cur.x2 + obj.x - 23; ok = (destx..cur.x2 + obj.x).none { blocked(it, y) } }
        if (!ok) { y = cur.y2 + obj.y + 1; destx = cur.x1 + obj.x; ok = (destx until destx + 24).none { blocked(it, y) } }
        if (!ok) { destx = cur.x2 + obj.x - 23; ok = (destx..cur.x2 + obj.x).none { blocked(it, y) } }
        return Triple(ok, destx, y)
    }
}
