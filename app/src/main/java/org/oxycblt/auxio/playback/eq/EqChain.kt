/*
 * Copyright (c) 2026 Auxio Project
 * EqChain.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.round

/**
 * A cascade of biquads applied to interleaved PCM, in double precision.
 *
 * The output encoding always matches the input encoding ([SUPPORTED_ENCODINGS]): 16-bit input is
 * clamped back into 16-bit, while float input is written back as unclamped float. Preserving the
 * encoding is required since ExoPlayer's silence-skipping processor, which always runs after this,
 * only accepts 16-bit PCM.
 *
 * Not thread-safe: owned by the audio thread. Processing allocates nothing.
 */
@OptIn(UnstableApi::class)
class EqChain(eq: ParametricEq, val sampleRate: Int, val channelCount: Int) {
    private val preampGain = 10.0.pow(eq.preampDb / 20)
    private val coefficients: DoubleArray
    private val filterCount: Int
    /** Transposed direct form II state, `[channel][filter][z1, z2]` flattened. */
    private val state: DoubleArray

    /** Whether this chain leaves the signal untouched, allowing a plain copy. */
    val isPassthrough: Boolean

    init {
        val nyquist = sampleRate / 2.0
        val bands = eq.bands.filter { it.enabled && it.frequency > 0 && it.frequency < nyquist }
        filterCount = bands.size
        coefficients = DoubleArray(filterCount * BiquadDesign.COEFFICIENT_COUNT)
        bands.forEachIndexed { i, band ->
            BiquadDesign.design(band.filterType, sampleRate, band.frequency, band.gain, band.q)
                .copyInto(coefficients, i * BiquadDesign.COEFFICIENT_COUNT)
        }
        state = DoubleArray(channelCount * filterCount * 2)
        isPassthrough = filterCount == 0 && preampGain == 1.0
    }

    /**
     * Reads all whole samples of [input], which holds [encoding] PCM in native byte order, and
     * writes the filtered samples to [output] in the same encoding.
     */
    fun process(input: ByteBuffer, encoding: Int, output: ByteBuffer) {
        if (isPassthrough) {
            output.put(input)
            return
        }
        var channel = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                while (input.remaining() >= 2) {
                    val y = round(filter(input.getShort() / SCALE_16, channel) * SCALE_16)
                    output.putShort(
                        y.toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort(),
                    )
                    channel = nextChannel(channel)
                }
            C.ENCODING_PCM_FLOAT ->
                while (input.remaining() >= 4) {
                    output.putFloat(filter(input.getFloat().toDouble(), channel).toFloat())
                    channel = nextChannel(channel)
                }
            else -> throw IllegalArgumentException("Unsupported PCM encoding $encoding")
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun nextChannel(channel: Int) =
        if (channel + 1 == channelCount) 0 else channel + 1

    @Suppress("NOTHING_TO_INLINE")
    private inline fun filter(sample: Double, channel: Int): Double {
        var x = sample * preampGain
        var s = channel * filterCount * 2
        var c = 0
        repeat(filterCount) {
            val y = coefficients[c] * x + state[s]
            state[s] = coefficients[c + 1] * x - coefficients[c + 3] * y + state[s + 1]
            state[s + 1] = coefficients[c + 2] * x - coefficients[c + 4] * y
            x = y
            s += 2
            c += BiquadDesign.COEFFICIENT_COUNT
        }
        return x
    }

    fun resetState() = state.fill(0.0)

    /**
     * Carries filter memory over from [other] when the layout matches, so a profile switch doesn't
     * click.
     */
    fun copyStateFrom(other: EqChain) {
        if (other.filterCount == filterCount && other.channelCount == channelCount) {
            other.state.copyInto(state)
        }
    }

    companion object {
        /** Encodings [process] accepts. */
        val SUPPORTED_ENCODINGS = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)

        private const val SCALE_16 = 32768.0
    }
}
