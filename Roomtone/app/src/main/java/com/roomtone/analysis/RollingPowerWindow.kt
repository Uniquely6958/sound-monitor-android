package com.roomtone.analysis

import java.util.ArrayDeque

/** Time-weighted mean-square power over the most recent, sample-counted window. */
class RollingPowerWindow(
    private val sampleRateHz: Int,
    windowSeconds: Int,
) {
    private data class Segment(var power: Double, var samples: Int)

    private val capacitySamples: Int
    private val segments = ArrayDeque<Segment>()
    private var weightedPower = 0.0
    private var sampleCount = 0

    init {
        require(sampleRateHz > 0)
        require(windowSeconds in 1..60)
        capacitySamples = sampleRateHz * windowSeconds
    }

    val isReady: Boolean
        get() = sampleCount >= capacitySamples

    val availableSamples: Int
        get() = sampleCount

    val averagePower: Double
        get() = if (sampleCount == 0) 0.0 else weightedPower / sampleCount

    fun add(power: Double, samples: Int) {
        require(power.isFinite() && power >= 0.0)
        require(samples > 0)
        segments.addLast(Segment(power, samples))
        weightedPower += power * samples
        sampleCount += samples

        while (sampleCount > capacitySamples) {
            val excess = sampleCount - capacitySamples
            val oldest = segments.first()
            val removed = minOf(excess, oldest.samples)
            oldest.samples -= removed
            sampleCount -= removed
            weightedPower -= oldest.power * removed
            if (oldest.samples == 0) segments.removeFirst()
        }
    }

    fun isAtOrAbove(thresholdDbfs: Double): Boolean {
        require(thresholdDbfs.isFinite() && thresholdDbfs <= 0.0)
        if (!isReady) return false
        val thresholdPower = 10.0.pow10(thresholdDbfs / 10.0)
        return averagePower >= thresholdPower
    }

    fun reset() {
        segments.clear()
        weightedPower = 0.0
        sampleCount = 0
    }

    private fun Double.pow10(exponent: Double): Double = Math.pow(10.0, exponent)
}

fun powerToDbfs(power: Double): Double =
    if (power <= 0.0) -120.0 else (10.0 * Math.log10(power)).coerceIn(-120.0, 0.0)

fun pcm16MeanSquare(samples: ShortArray, length: Int): Double {
    require(length in 1..samples.size)
    var sum = 0.0
    for (index in 0 until length) {
        val normalized = samples[index] / 32768.0
        sum += normalized * normalized
    }
    return sum / length
}