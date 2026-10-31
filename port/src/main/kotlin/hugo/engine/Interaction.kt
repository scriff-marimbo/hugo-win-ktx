package hugo.engine

import hugo.data.Str
import hugo.engine.Display.Companion.INV_DY
import hugo.engine.Display.Companion.XPIX

/** Mouse handling (MouseHandler @1010:82dc and friends). */
class Mouse(private val e: Engine) {
    /** Cursor position in game coordinates (x 0..319, y 8..191), or -1 when outside the view. */
    @Volatile var x = -1
    @Volatile var y = -1

    fun findExit(cx: Int, cy: Int, screen: Int): Int {
        for ((i, h) in e.tables.hotspots.withIndex())
            if (h.screen == screen && cx >= h.x1 && cx <= h.x2 && cy >= h.y1 && cy <= h.y2) return i
        return -1
    }

    /** CursorText @1010:8224. */
    private fun cursorText(text: String, cx: Int, cy: Int, font: Int, color: Int) {
        val d = e.display
        d.loadFont(font)
        val w = d.stringLength(text)
        val h = d.fontHeight()
        val sx = if (cx < 160) cx + 10 else cx - w - 5
        d.shadowStr(sx, cy - 2, text, color)
        d.displayAdd(sx, cy - 2, w, h + 1)
    }

    fun mouseHandler() {
        val cx = x; val cy = y
        if (cx !in 0..320 || cy !in 8..192) return
        var objId = -1
        if (e.objectInHand != -1) {
            var p = 0
            while (p < 32 && e.tables.inventoryOrder.getOrElse(p) { -1 } != e.objectInHand) p++
            val ux = ((p + 2) * 32) % XPIX
            val uy = ((p + 2) * 32) / XPIX * 32
            val dx = (cx + 8).coerceIn(0, 288)
            val dy = (cy - 8 + 18).coerceIn(0, 168)
            e.display.moveImage(e.display.gui, ux, uy, 32, 32, XPIX, e.display.front, dx, dy, XPIX)
            e.display.displayAdd(dx, dy, 32, 32)
        }
        if (e.inventoryState == Inventory.ACTIVE) objId = e.inventory.processInventory(Inventory.GET, cx, cy)
        if (objId == -1) objId = e.objs.findObject(cx, cy)
        if (objId >= 0) {
            val o = e.objects[if (objId == 0) e.heroImage else objId]
            val name = e.tables.words(o.nounPtr).words.getOrElse(2) { "" }
            if (!name.startsWith("~")) cursorText(name, cx, cy, 2, 15)
            if (e.rightClick) processRightClick(cy, cx, objId)
        }
        if (objId == -1) {
            val i = findExit(cx, cy, e.screen)
            if (i != -1 && e.tables.hotspots[i].viewx >= 0) {
                objId = -4
                cursorText(e.str(Str.EXIT), cx, cy, 2, 15)
            }
        }
        if (e.leftClick) processLeftClick(cy, cx, objId)
        e.leftClick = false
        e.rightClick = false
    }

    /** ProcessRightClick @1010:84e8: take / use. */
    private fun processRightClick(cy: Int, cx: Int, objId: Int) {
        if (e.storyModeFl != 0 || e.hero.pathType == PathType.QUIET) return
        if (e.inventoryState == Inventory.ACTIVE && cy < 40) {
            if (e.objectInHand == -1) { e.objectInHand = objId; return }
            if (e.objectInHand == objId) { e.objectInHand = -1; return }
        } else {
            val o = e.objects[objId]
            if (o.viewx == -1) {
                val (ok, tx, ty) = e.objs.findObjectSpace(o)
                if (ok && e.route.startRoute(Route.GET, objId, tx, ty)) return
            } else if (o.viewx != 0) {
                if (e.route.startRoute(Route.GET, objId, o.viewx, o.viewy)) return
                if (e.hero.cycling != Cycle.INVISIBLE) { e.box(0, e.str(Str.NO_WAY)); return }
            }
        }
        e.useObject(objId)
    }

    /** ProcessLeftClick @1010:85ca: walk / look / exits / icon bar arrows. */
    private fun processLeftClick(cy: Int, cx: Int, objId: Int) {
        if (e.storyModeFl != 0 || e.hero.pathType == PathType.QUIET) return
        when (objId) {
            -4 -> {
                val i = findExit(cx, cy, e.screen)
                val h = e.tables.hotspots[i]
                var vx = h.viewx
                if (vx >= 0) {
                    if (e.jumpExitFl == 0) {
                        if (h.direction == Parser.VK.RIGHT) vx -= 24 else if (h.direction == Parser.VK.LEFT) vx += 24
                        if (!e.route.startRoute(Route.EXIT, i, vx, h.viewy)) e.box(0, e.str(Str.NO_WAY))
                    } else {
                        if (e.inventoryState != Inventory.OFF) e.inventoryState = Inventory.UP
                        e.scheduler.insertActionList(h.actList)
                    }
                    e.objectInHand = -1
                }
            }
            -3, -2 -> {
                e.inventory.processInventory(if (objId == -2) Inventory.LEFT else Inventory.RIGHT, 0, 0)
                val d = e.display
                d.moveImage(d.iconBar, 0, 0, XPIX, INV_DY, XPIX, d.front, 0, 8, XPIX)
                d.moveImage(d.iconBar, 0, 0, XPIX, INV_DY, XPIX, d.back, 0, 8, XPIX)
                d.displayAdd(0, 8, XPIX, INV_DY)
            }
            -1 -> e.route.startRoute(Route.SPACE, 0, cx, cy)
            else -> {
                val o = e.objects[objId]
                if (e.inventoryState != Inventory.ACTIVE || cy > 39) {
                    if (o.viewx == -1) {
                        val (ok, tx, ty) = e.objs.findObjectSpace(o)
                        if (ok && e.route.startRoute(Route.LOOK, objId, tx, ty)) return
                    } else if (o.viewx != 0) {
                        if (e.route.startRoute(Route.LOOK, objId, o.viewx, o.viewy)) return
                        if (e.hero.cycling != Cycle.INVISIBLE) { e.box(0, e.str(Str.NO_WAY)); return }
                    }
                }
                e.lookObject(o)
            }
        }
    }
}

/** The sliding icon bar (inventory) @1010:6eda..70e4. */
class Inventory(private val e: Engine) {
    var first = 0          // DGROUP:37a4 first visible carried object

    private fun counts(): Pair<Int, Int> {
        var n = 0; var carried = 0
        val order = e.tables.inventoryOrder
        while (n < 32 && n < order.size) { if (e.objects[order[n]].carried != 0) carried++; n++ }
        return n to carried
    }

    /** ProcessInventory @1010:6fe2. Returns an object index, -2/-3 for the arrows, or -1. */
    fun processInventory(action: Int, cx: Int, cy: Int): Int {
        val (nInv, count) = counts()
        when (action) {
            INIT -> {}
            LEFT -> { first--; if (first < 0) first = 0 }
            RIGHT -> { first++; if (count < first) first = count }
            GET -> {
                if (cy - 8 < 1 || cy - 8 > 31) return -1
                var i = cx / 32
                if (count > 10) {
                    if (i == 0) return -2
                    if (i == 9) return -3
                    i += first - 1
                }
                if (count <= i) return -1
                var k = i + 1
                for (o in 0 until 88) if (e.objects[o].carried != 0) { k--; if (k == 0) return o }
                return -1
            }
            else -> return -1
        }
        drawBar(nInv, count, count > 10, first)
        return -1
    }

    /** DrawInventoryBar @1010:6eda. */
    private fun drawBar(nInv: Int, count0: Int, arrows: Boolean, first0: Int) {
        val d = e.display
        d.iconBar.fill(0)
        var count = count0; var firstVis = first0
        if (arrows) {
            d.moveImage(d.gui, 0, 0, 32, 32, XPIX, d.iconBar, 0, 0, XPIX)
            d.moveImage(d.gui, 32, 0, 32, 32, XPIX, d.iconBar, 288, 0, XPIX)
            if (count > 8) count = 8
        } else firstVis = 0
        var shown = 0; var seen = 0
        val order = e.tables.inventoryOrder
        for (i in 0 until nInv) {
            if (e.objects[order[i]].carried != 0) {
                if (shown < count && firstVis <= seen) {
                    val src = (i + 2) * 32
                    val slot = if (arrows) shown + 1 else shown
                    shown++
                    d.moveImage(d.gui, src % XPIX, src / XPIX * 32, 32, 32, XPIX, d.iconBar, slot * 32, 0, XPIX)
                }
                seen++
            }
        }
    }

    var offset = 0     // DGROUP:68c0 how far the bar has slid in

    /** RunInventory @1010:70e4 (machine state 4). */
    fun runInventory() {
        val d = e.display
        when (e.inventoryState) {
            UP -> {
                offset -= 8; if (offset < 1) offset = 0
                d.moveImage(d.iconBar, 0, 0, XPIX, offset, XPIX, d.front, 0, 8, XPIX)
                d.moveImage(d.backSave, 0, offset + 8, XPIX, 8, XPIX, d.front, 0, offset + 8, XPIX)
                if (offset == 0) {
                    d.backSave.copyInto(d.back)
                    d.back.copyInto(d.front)
                    e.objs.updateImages()
                    e.inventoryState = OFF
                    e.machineState = 3
                }
                e.host.frameReady()
            }
            DOWN -> {
                if (offset == 0) {
                    processInventory(INIT, 0, 0)
                    d.displayRestore()
                    e.objs.updateImages()
                    d.displayFlush()
                }
                offset += 8; if (offset > 31) offset = 32
                d.moveImage(d.iconBar, 0, 0, XPIX, offset, XPIX, d.front, 0, 8, XPIX)
                if (offset == 32) {
                    d.back.copyInto(d.backSave)
                    d.front.copyInto(d.back)
                    d.displayInit()
                    e.inventoryState = ACTIVE
                }
                e.host.frameReady()
            }
            ACTIVE -> {
                e.parser.charHandler()
                d.displayRestore()
                e.mouse.mouseHandler()
                d.displayFlush()
            }
        }
    }

    companion object {
        const val OFF = 0; const val UP = 1; const val DOWN = 2; const val ACTIVE = 3
        const val INIT = 0; const val LEFT = 1; const val RIGHT = 2; const val GET = 3
    }
}

/** Route finding (Segment / FindRoute / ProcessRoute / StartRoute @1010:87b6..8ffe). */
class Route(private val e: Engine) {
    private val map = ByteArray(XPIX * 200)
    private class SegRec(val y: Int, val x1: Int, val x2: Int)
    private val segments = ArrayList<SegRec>()
    private val nodeX = IntArray(256); private val nodeY = IntArray(256)
    private var nodeCount = 0                 // DGROUP:69da
    private var found = false; private var fullStack = false; private var fullSeg = false
    private var heroWidth = 16
    private var destX = 0; private var destY = 0
    private var depth = 0
    private var turnedFl = false

    var routeType = 0; var routeObj = -1

    /** Segment @1010:87b6 (recursive flood fill). */
    private fun segment(x: Int, y: Int) {
        depth++
        try {
            // _stackavail() < 256 with a 12000-byte stack and 18 bytes per level (estimate)
            fullStack = depth > STACK_LEVELS
            val row = y * XPIX
            var x1 = x
            while (x1 > 0 && map[row + x1].toInt() == 0) { map[row + x1] = 2; x1-- }
            var x2 = x
            while (x2 + 1 < XPIX && map[row + x2 + 1].toInt() == 0) { map[row + x2 + 1] = 2; x2++ }
            x1++
            if (heroWidth > x2 - x1 + 1) return
            if (destY == y && x1 <= destX && destX <= x2) found = true
            if (y <= 0 || y >= 199) return
            val hx = e.hero.x
            fun stop() = fullSeg || fullStack || found
            fun up(px: Int) { if (map[row - XPIX + px].toInt() == 0) segment(px, y - 1) }
            fun dn(px: Int) { if (map[row + XPIX + px].toInt() == 0) segment(px, y + 1) }
            if (hx < x1) {
                var px = x1; while (!stop() && px <= x2) { up(px); px++ }
                px = x1; while (!stop() && px <= x2) { dn(px); px++ }
            } else if (x2 < hx + 24) {
                var px = x2; while (!stop() && x1 <= px) { up(px); px-- }
                px = x2; while (!stop() && x1 <= px) { dn(px); px-- }
            } else {
                var px = hx; while (!stop() && px <= x2) { up(px); px++ }
                px = x1; while (!stop() && px < hx) { up(px); px++ }
                px = hx; while (!stop() && px <= x2) { dn(px); px++ }
                px = x1; while (!stop() && px < hx) { dn(px); px++ }
            }
            if (found) {
                if (segments.size < 255) segments += SegRec(y, x1, x2) else fullSeg = true
            }
        } finally { depth-- }
    }

    private fun newNode(): Int {
        if (nodeCount > 255) return -1
        nodeCount++
        if (nodeCount > 255) return -1
        nodeX[nodeCount] = nodeX[nodeCount - 1]; nodeY[nodeCount] = nodeY[nodeCount - 1]
        return nodeCount
    }

    /** FindRoute @1010:8aee. */
    private fun findRoute(cx: Int, cy: Int): Boolean {
        found = false; fullStack = false; fullSeg = false
        segments.clear()
        heroWidth = 16
        destY = cy; destX = cx
        val h = e.hero
        val herox1 = h.img.x1 + h.x
        val herox2 = h.x + h.img.x2
        val heroy = h.img.y2 + h.y
        val objs = e.objs
        for (i in 1 until 88) {
            val o = e.objects[i]
            if (o.screen == e.screen && o.cycling != Cycle.INVISIBLE && o.priority == Priority.FLOATING && o.curr != null)
                objs.storeBoundary(o.img.x1 + o.oldx, o.img.x2 + o.oldx, o.img.y2 + o.oldy)
        }
        for (yy in 0 until 200) for (xb in 0 until 40) {
            val b = (objs.boundary[yy * 40 + xb].toInt() or objs.objBound[yy * 40 + xb].toInt()) and 0xFF
            for (bit in 0 until 8) map[yy * XPIX + xb * 8 + bit] = if (b and (0x80 shr bit) != 0) 1 else 0
        }
        for (i in 0 until 88) {
            val o = e.objects[i]
            if (o.screen == e.screen && o.cycling != Cycle.INVISIBLE && o.priority == Priority.FLOATING && o.curr != null)
                objs.clearBoundary(o.img.x1 + o.oldx, o.img.x2 + o.oldx, o.img.y2 + o.oldy)
        }
        depth = 0
        if (heroy in 0 until 200 && herox1 in 0 until XPIX) segment(herox1, heroy)
        if (!found || fullStack || fullSeg) return false

        nodeX[0] = destX; nodeY[0] = destY
        segments += SegRec(heroy, herox1, herox2)
        nodeCount = 0
        var i = 0
        while (segments.size - 1 > i) {
            var node = newNode(); if (node < 0) return false
            nodeY[node] = segments[i].y
            var j = i + 1
            var broke = false
            while (segments.size > j) {
                val s = segments[j]
                if (s.x1 <= nodeX[node] && nodeX[node] + heroWidth - 1 <= s.x2) { nodeY[node] = s.y; j++; continue }
                node = newNode(); if (node < 0) return false
                val x1 = Math.max(segments[j - 1].x1, s.x1)
                val x2 = Math.min(segments[j - 1].x2, s.x2)
                var dx = 12
                if (x2 - x1 < heroWidth + 12) dx = 0
                nodeX[node] = when {
                    segments.size - j - 1 == 0 -> herox1
                    herox1 < x1 -> x1 + dx
                    x2 - heroWidth + 1 >= herox1 -> herox1
                    else -> x2 - dx - heroWidth
                }
                i = j - 2
                broke = true
                break
            }
            if (!broke) { /* i unchanged */ }
            if (nodeX[node] == herox1 && nodeY[node] == heroy) return true
            i++
        }
        return true
    }

    /** StartRoute @1010:8ffe. */
    fun startRoute(type: Int, objId: Int, cx0: Int, cy: Int): Boolean {
        val h = e.hero
        if (h.pathType != PathType.USER) return false
        if (e.inventoryState != Inventory.OFF) e.inventoryState = Inventory.UP
        routeType = type; routeObj = objId
        val cx = if (type == SPACE) cx0 - 8 else cx0
        val ok = findRoute(cx, cy)
        if (ok) { e.routeIndex = nodeCount; h.vy = 0; h.vx = 0 }
        return ok
    }

    /** ProcessRoute @1010:8e36 (called from MoveObjects). */
    fun processRoute() {
        val h = e.hero
        val herox = h.img.x1 + h.x
        val heroy = h.img.y2 + h.y
        val ri = e.routeIndex
        val nx = nodeX[ri]; val ny = nodeY[ri]
        if (Math.abs(herox - nx) < 6 && Math.abs(heroy - ny) < 4) {
            h.oldx = nx - h.img.x1; h.x = h.oldx
            h.oldy = ny - h.img.y2; h.y = h.oldy
            h.vy = 0; h.vx = 0
            h.cycling = Cycle.NOT_CYCLING
            e.routeIndex = ri - 1
            if (e.routeIndex >= 0) return
            when (routeType) {
                EXIT -> e.parser.moveHeroByKey(e.tables.hotspots[routeObj].direction)
                LOOK, GET -> {
                    if (!turnedFl) {
                        e.parser.setDirection(e.objects[routeObj].direction)
                        turnedFl = true
                        e.routeIndex++
                        return
                    }
                    if (routeType == LOOK) e.lookObject(e.objects[routeObj]) else e.useObject(routeObj)
                    turnedFl = false
                }
            }
        } else if (h.vx == 0 && h.vy == 0) {
            when {
                herox < nx -> e.parser.moveHeroByKey(Parser.VK.RIGHT)
                herox > nx -> e.parser.moveHeroByKey(Parser.VK.LEFT)
                heroy < ny -> { e.parser.moveHeroByKey(Parser.VK.DOWN); h.oldx = nx - h.img.x1; h.x = h.oldx }
                heroy > ny -> { e.parser.moveHeroByKey(Parser.VK.UP); h.oldx = nx - h.img.x1; h.x = h.oldx }
            }
        }
    }

    companion object {
        const val SPACE = 0; const val EXIT = 1; const val LOOK = 2; const val GET = 3
        const val STACK_LEVELS = 560
    }
}
