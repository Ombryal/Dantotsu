package ani.dantotsu.media.manga.mangareader

import kotlin.math.roundToInt

/**
 * Turns two live signals - how long pages actually take to decode, and how fast
 * the person is actually turning/scrolling pages - into a single "how many pages
 * should we keep ready ahead of the current one" number.
 *
 * The instinctive rule would be "struggling device -> preload more to compensate",
 * but that's backwards for a reader like this: a device that's already slow to
 * decode one page will only fall further behind if it's asked to decode several
 * at once, and the extra full-res bitmaps held in memory while they're all in
 * flight make an OOM more likely, not less. So a slow decode pulls the
 * recommendation *down* here, and a fast reader who will actually consume a
 * bigger buffer pulls it *up*. Both signals are clamped so neither can push the
 * result to an extreme on its own, and everything is a plain rolling average -
 * no persistence, no allocation beyond two small fixed-size queues.
 */
class AdaptiveReadAheadController(
    private val minPreload: Int = 1,
    private val maxPreload: Int = 8,
    private val baselinePreload: Int = 3
) {
    private val maxSamples = 12
    private val decodeTimesMs = ArrayDeque<Long>()
    private val pageTurnTimestamps = ArrayDeque<Long>()

    /** Call this once a page's bitmap has finished decoding, with how long it took. */
    @Synchronized
    fun recordPageDecodeTime(ms: Long) {
        if (ms <= 0) return
        decodeTimesMs.addLast(ms)
        if (decodeTimesMs.size > maxSamples) decodeTimesMs.removeFirst()
    }

    /** Call this once per genuine page change (not per scroll frame). */
    @Synchronized
    fun recordPageTurn() {
        val now = System.currentTimeMillis()
        pageTurnTimestamps.addLast(now)
        if (pageTurnTimestamps.size > maxSamples) pageTurnTimestamps.removeFirst()
    }

    /** How many pages ahead the reader should currently try to keep ready. */
    @Synchronized
    fun recommendedPreloadCount(): Int {
        val avgDecodeMs = decodeTimesMs.averageOrZero()
        val pagesPerMinute = estimatePagesPerMinute()

        var recommendation = baselinePreload.toDouble()

        // Comfortably fast decodes earn a little extra headroom; slow ones mean
        // the device/network is already behind, so ease off instead of piling on
        // more concurrent decode work.
        recommendation += when {
            avgDecodeMs <= 0.0 -> 0.0
            avgDecodeMs < 120.0 -> 1.5
            avgDecodeMs < 250.0 -> 0.0
            avgDecodeMs < 400.0 -> -1.0
            else -> -2.0
        }

        // A skimmer blowing through pages needs a bigger buffer ahead of them;
        // someone lingering on each page doesn't need much of one at all.
        recommendation += when {
            pagesPerMinute <= 0.0 -> 0.0
            pagesPerMinute > 25.0 -> 2.0
            pagesPerMinute > 12.0 -> 1.0
            pagesPerMinute < 4.0 -> -1.0
            else -> 0.0
        }

        return recommendation.roundToInt().coerceIn(minPreload, maxPreload)
    }

    private fun estimatePagesPerMinute(): Double {
        if (pageTurnTimestamps.size < 2) return 0.0
        val spanMs = pageTurnTimestamps.last() - pageTurnTimestamps.first()
        if (spanMs <= 0) return 0.0
        val turns = pageTurnTimestamps.size - 1
        return turns * 60_000.0 / spanMs
    }

    private fun ArrayDeque<Long>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else sum().toDouble() / size

    /** Call when starting a new chapter so stale timing/velocity data doesn't linger. */
    @Synchronized
    fun reset() {
        decodeTimesMs.clear()
        pageTurnTimestamps.clear()
    }
}
