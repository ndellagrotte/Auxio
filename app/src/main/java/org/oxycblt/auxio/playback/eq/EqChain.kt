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
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * A cascade of biquads applied to interleaved PCM, in double precision.
 *
 * Reads any linear PCM encoding ([SUPPORTED_ENCODINGS]) and always writes 32-bit float, unclamped,
 * so neither a positive preamp nor a boost clips here. High-resolution input (24-bit, 32-bit,
 * float) keeps its full precision.
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

    /** Whether this chain leaves the signal untouched (beyond conversion to float). */
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
     * Reads all whole samples of [input], which holds [encoding] PCM, and writes one native-order
     * float per sample to [output]. Little-endian encodings are read in [input]'s byte order, which
     * should be native.
     */
    fun process(input: ByteBuffer, encoding: Int, output: ByteBuffer) {
        if (isPassthrough && encoding == C.ENCODING_PCM_FLOAT) {
            output.put(input)
            return
        }
        val inputOrder = input.order()
        var channel = 0
        when (encoding) {
            C.ENCODING_PCM_8BIT ->
                while (input.hasRemaining()) {
                    output.putFloat(
                        filter(((input.get().toInt() and 0xFF) - 128) / SCALE_8, channel),
                    )
                    channel = nextChannel(channel)
                }
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                if (encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN) input.order(ByteOrder.BIG_ENDIAN)
                while (input.remaining() >= 2) {
                    output.putFloat(filter(input.getShort() / SCALE_16, channel))
                    channel = nextChannel(channel)
                }
            }
            C.ENCODING_PCM_24BIT ->
                while (input.remaining() >= 3) {
                    val b0 = input.get().toInt() and 0xFF
                    val b1 = input.get().toInt() and 0xFF
                    val b2 = input.get().toInt() // Sign carries from the top byte.
                    output.putFloat(filter((b0 or (b1 shl 8) or (b2 shl 16)) / SCALE_24, channel))
                    channel = nextChannel(channel)
                }
            C.ENCODING_PCM_24BIT_BIG_ENDIAN ->
                while (input.remaining() >= 3) {
                    val b2 = input.get().toInt() // Sign carries from the top byte.
                    val b1 = input.get().toInt() and 0xFF
                    val b0 = input.get().toInt() and 0xFF
                    output.putFloat(filter((b0 or (b1 shl 8) or (b2 shl 16)) / SCALE_24, channel))
                    channel = nextChannel(channel)
                }
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                if (encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN) input.order(ByteOrder.BIG_ENDIAN)
                while (input.remaining() >= 4) {
                    output.putFloat(filter(input.getInt() / SCALE_32, channel))
                    channel = nextChannel(channel)
                }
            }
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> {
                if (encoding == C.ENCODING_PCM_FLOAT_BIG_ENDIAN) input.order(ByteOrder.BIG_ENDIAN)
                while (input.remaining() >= 4) {
                    output.putFloat(filter(input.getFloat().toDouble(), channel))
                    channel = nextChannel(channel)
                }
            }
            C.ENCODING_PCM_DOUBLE,
            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> {
                if (encoding == C.ENCODING_PCM_DOUBLE_BIG_ENDIAN) input.order(ByteOrder.BIG_ENDIAN)
                while (input.remaining() >= 8) {
                    output.putFloat(filter(input.getDouble(), channel))
                    channel = nextChannel(channel)
                }
            }
            else -> throw IllegalArgumentException("Unsupported PCM encoding $encoding")
        }
        input.order(inputOrder)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun nextChannel(channel: Int) =
        if (channel + 1 == channelCount) 0 else channel + 1

    @Suppress("NOTHING_TO_INLINE")
    private inline fun filter(sample: Double, channel: Int): Float {
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
        return x.toFloat()
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
        /** Input encodings [process] accepts: every linear PCM encoding. */
        val SUPPORTED_ENCODINGS =
            setOf(
                C.ENCODING_PCM_8BIT,
                C.ENCODING_PCM_16BIT,
                C.ENCODING_PCM_16BIT_BIG_ENDIAN,
                C.ENCODING_PCM_24BIT,
                C.ENCODING_PCM_24BIT_BIG_ENDIAN,
                C.ENCODING_PCM_32BIT,
                C.ENCODING_PCM_32BIT_BIG_ENDIAN,
                C.ENCODING_PCM_FLOAT,
                C.ENCODING_PCM_FLOAT_BIG_ENDIAN,
                C.ENCODING_PCM_DOUBLE,
                C.ENCODING_PCM_DOUBLE_BIG_ENDIAN,
            )

        private const val SCALE_8 = 128.0
        private const val SCALE_16 = 32768.0
        private const val SCALE_24 = 8388608.0
        private const val SCALE_32 = 2147483648.0
    }
}
