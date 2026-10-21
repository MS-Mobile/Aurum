package com.msmobile.aurum.processing

import kotlin.math.floor

/**
 * Exact order statistics over large float arrays, standing in for `np.percentile` and `np.median` without sorting
 * or copying all 12+ million values. It is a two-pass radix select on the order-preserving integer key of each
 * float: a histogram of the high 16 bits picks the bucket holding each rank, and only that bucket is sorted.
 */
internal object OrderStatistics {

    /** Values at the given 0-based [ranks] of [values] sorted ascending. */
    fun select(values: FloatArray, ranks: IntArray): FloatArray {
        val histogram = IntArray(BUCKETS)
        for (v in values) histogram[bucketOf(v)]++

        val bucketOfRank = IntArray(ranks.size)
        val rankInBucket = IntArray(ranks.size)
        for ((i, rank) in ranks.withIndex()) {
            require(rank in values.indices) { "Rank $rank out of bounds" }
            var below = 0
            var bucket = 0
            while (below + histogram[bucket] <= rank) below += histogram[bucket++]
            bucketOfRank[i] = bucket
            rankInBucket[i] = rank - below
        }

        val selectedBuckets = bucketOfRank.distinct()
        val slotOfBucket = IntArray(BUCKETS) { -1 }
        selectedBuckets.forEachIndexed { slot, bucket -> slotOfBucket[bucket] = slot }
        val members = Array(selectedBuckets.size) { FloatArray(histogram[selectedBuckets[it]]) }
        val filled = IntArray(selectedBuckets.size)
        for (v in values) {
            val slot = slotOfBucket[bucketOf(v)]
            if (slot >= 0) members[slot][filled[slot]++] = v
        }
        members.forEach { it.sort() }
        return FloatArray(ranks.size) { members[slotOfBucket[bucketOfRank[it]]][rankInBucket[it]] }
    }

    /** `np.percentile(values, q)` with the default "linear" method, as float64 like NumPy returns it. */
    fun percentile(values: FloatArray, q: DoubleArray): DoubleArray {
        val n = values.size
        val virtualIndexes = DoubleArray(q.size) { (n - 1) * (q[it] / 100.0) }
        val previous = IntArray(q.size) { floor(virtualIndexes[it]).toInt().coerceIn(0, n - 1) }
        val next = IntArray(q.size) { (previous[it] + 1).coerceAtMost(n - 1) }
        val selected = select(values, previous + next)
        return DoubleArray(q.size) { i ->
            val a = selected[i]
            val b = selected[q.size + i]
            val gamma = virtualIndexes[i] - previous[i]
            // numpy _lerp: diff in the array dtype, then float64 because gamma is float64.
            val diff = (b - a).toDouble()
            if (gamma >= 0.5) b - diff * (1 - gamma) else a + diff * gamma
        }
    }

    /** `np.median(values)` for a float32 array: the float32 mean of the middle value(s). */
    fun median(values: FloatArray): Float {
        val n = values.size
        return if (n % 2 == 1) {
            select(values, intArrayOf(n / 2))[0]
        } else {
            val (a, b) = select(values, intArrayOf(n / 2 - 1, n / 2)).let { it[0] to it[1] }
            ((a + b).toDouble() / 2).toFloat()
        }
    }

    private const val BUCKETS = 1 shl 16

    /** High 16 bits of an unsigned key that orders like the floats themselves. */
    private fun bucketOf(v: Float): Int {
        val bits = v.toRawBits()
        val key = if (bits < 0) bits.inv() else bits xor Int.MIN_VALUE
        return key ushr 16
    }
}
