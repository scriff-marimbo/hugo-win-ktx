package hugo.engine

/**
 * Bits of the Microsoft C 7 / Visual C++ 1.5 runtime whose exact behaviour shows in the
 * game: rand() (wandering objects) and qsort() (sprite draw order when sort keys tie).
 */
object Crt {
    private var seed: Long = 1

    /** srand @1008:956c. The game calls srand(42) once at start-up. */
    fun srand(s: Int) { seed = (s and 0xFFFF).toLong() }

    /** rand @1008:9584: seed = seed*214013 + 2531011 (32-bit); return (seed >> 16) & 0x7FFF. */
    fun rand(): Int {
        seed = (seed * 214013L + 2531011L) and 0xFFFFFFFFL
        return ((seed shr 16) and 0x7FFF).toInt()
    }

    /**
     * qsort @1008:940a, element size 1, transcribed from the disassembly: an "already
     * sorted?" pre-pass, then an explicit-stack quicksort with the first element as pivot
     * that excludes keys equal to the pivot from both partitions.
     */
    fun qsort(a: IntArray, n: Int, cmp: (Int, Int) -> Int) {
        if (n <= 1) return
        var sorted = true
        for (i in 0 until n - 1) if (cmp(a[i + 1], a[i]) < 0) { sorted = false; break }
        if (sorted) return
        fun swap(i: Int, j: Int) { val t = a[i]; a[i] = a[j]; a[j] = t }
        val stack = ArrayList<IntArray>()
        stack += intArrayOf(0, n - 1)
        while (stack.isNotEmpty()) {
            val cur = stack[stack.size - 1]
            val lo = cur[0]; val hi = cur[1]
            if (lo >= hi) { stack.removeAt(stack.size - 1); continue }
            var b2 = hi; var b4 = lo
            var i = lo
            var jStart = hi + 1
            while (true) {
                while (true) {                          // forward scan
                    i++
                    if (i == hi) break
                    val r = cmp(a[i], a[lo])
                    if (r > 0) break
                    if (r < 0) b4 = i
                }
                var j = jStart
                while (true) {                          // backward scan
                    j--
                    val r = cmp(a[lo], a[j])
                    if (r > 0) break
                    if (r < 0) { b2 = j; continue }
                    if (j == lo) break
                }
                if (j > i) { swap(i, j); b2 = j; b4 = i; jStart = j; continue }
                swap(lo, j)
                break
            }
            if (hi - b2 >= b4 - lo) { cur[0] = b2; stack += intArrayOf(lo, b4) }
            else { cur[1] = b4; stack += intArrayOf(b2, hi) }
        }
    }
}

/** 16-bit / 8-bit truncation helpers, to keep arithmetic identical to the original. */
fun Int.i16(): Int = toShort().toInt()
fun Int.i8(): Int = toByte().toInt()
fun Int.u8(): Int = this and 0xFF
