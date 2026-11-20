/*
 * Copyright (c) 2026 Auxio Project
 * EqChainTest.kt is part of Auxio.
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

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqChainTest {
    private val flat = ParametricEq(0.0, emptyList())

    private fun buffer(bytes: Int, order: ByteOrder = ByteOrder.nativeOrder()): ByteBuffer =
        ByteBuffer.allocate(bytes).order(order)

    private fun pcm16(vararg samples: Int, order: ByteOrder = ByteOrder.nativeOrder()) =
        buffer(samples.size * 2, order).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    /** Packed 24-bit samples, little-endian unless [bigEndian]. */
    private fun pcm24(vararg samples: Int, bigEndian: Boolean = false) =
        buffer(samples.size * 3).apply {
            samples.forEach {
                val bytes = byteArrayOf(it.toByte(), (it shr 8).toByte(), (it shr 16).toByte())
                if (bigEndian) bytes.reverse()
                put(bytes)
            }
            flip()
        }

    private fun pcm32(vararg samples: Int) =
        buffer(samples.size * 4).apply {
            samples.forEach(::putInt)
            flip()
        }

    private fun pcmFloat(vararg samples: Float) =
        buffer(samples.size * 4).apply {
            samples.forEach(::putFloat)
            flip()
        }

    private fun bytes(vararg values: Byte) =
        buffer(values.size).apply {
            put(values)
            flip()
        }

    private fun run(chain: EqChain, input: ByteBuffer, encoding: Int): FloatArray {
        val samples =
            input.remaining() /
                when (encoding) {
                    C.ENCODING_PCM_8BIT -> 1
                    C.ENCODING_PCM_16BIT,
                    C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
                    C.ENCODING_PCM_24BIT,
                    C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
                    C.ENCODING_PCM_DOUBLE -> 8
                    else -> 4
                }
        val output = buffer(samples * 4)
        chain.process(input, encoding, output)
        output.flip()
        return FloatArray(output.remaining() / 4) { output.getFloat() }
    }

    private fun run16(chain: EqChain, vararg samples: Int) =
        run(chain, pcm16(*samples), C.ENCODING_PCM_16BIT)

    @Test
    fun flatChainConverts16BitToFloatExactly() {
        val chain = EqChain(flat, 48000, 2)
        assertTrue(chain.isPassthrough)
        val input = intArrayOf(0, 1, -1, 12345, -32768, 32767)
        val expected = FloatArray(input.size) { input[it] / 32768f }
        assertArrayEquals(expected, run16(chain, *input), 0f)
    }

    @Test
    fun flatChainKeepsEveryBitOf24BitInput() {
        val input = intArrayOf(0, 1, -1, 0x123456, -0x123456, 8_388_607, -8_388_608)
        val expected = FloatArray(input.size) { input[it] / 8_388_608f }
        assertArrayEquals(
            expected,
            run(EqChain(flat, 96000, 1), pcm24(*input), C.ENCODING_PCM_24BIT),
            0f,
        )
        assertArrayEquals(
            expected,
            run(
                EqChain(flat, 96000, 1),
                pcm24(*input, bigEndian = true),
                C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            ),
            0f,
        )
    }

    @Test
    fun otherEncodingsAreConverted() {
        val chain = EqChain(flat, 48000, 1)
        assertArrayEquals(
            floatArrayOf(0f, -1f, 0.5f),
            run(chain, pcm32(0, Int.MIN_VALUE, 1 shl 30), C.ENCODING_PCM_32BIT),
            0f,
        )
        val floats = floatArrayOf(0f, 0.25f, -0.999f, 1.5f)
        assertArrayEquals(floats, run(chain, pcmFloat(*floats), C.ENCODING_PCM_FLOAT), 0f)
        assertArrayEquals(
            floatArrayOf(-1f, 0f, 0.5f),
            run(chain, bytes(0, -128, -64), C.ENCODING_PCM_8BIT),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(0.5f, -0.25f),
            run(
                chain,
                pcm16(16384, -8192, order = ByteOrder.BIG_ENDIAN),
                C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            ),
            0f,
        )
        val doubles =
            buffer(16).apply {
                putDouble(0.75)
                putDouble(-0.125)
                flip()
            }
        assertArrayEquals(
            floatArrayOf(0.75f, -0.125f),
            run(chain, doubles, C.ENCODING_PCM_DOUBLE),
            0f,
        )
    }

    @Test
    fun bigEndianInputLeavesBufferOrderAlone() {
        val input = pcm16(1, 2, order = ByteOrder.BIG_ENDIAN).order(ByteOrder.LITTLE_ENDIAN)
        run(
            EqChain(ParametricEq(-1.0, emptyList()), 48000, 1),
            input,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
        )
        assertEquals(ByteOrder.LITTLE_ENDIAN, input.order())
    }

    @Test
    fun preampScalesAndOutputIsNotClamped() {
        val half = EqChain(ParametricEq(-20 * log10(2.0), emptyList()), 48000, 1)
        val halved = run16(half, 16384, -16384)
        assertEquals(0.25, halved[0].toDouble(), 1e-6)
        assertEquals(-0.25, halved[1].toDouble(), 1e-6)

        val boost = EqChain(ParametricEq(6.0, emptyList()), 48000, 1)
        val boosted = run16(boost, 30000, -30000)
        assertTrue(boosted[0] > 1.5f && boosted[1] < -1.5f)
    }

    @Test
    fun peakingBandBoostsItsCentreFrequency() {
        val sampleRate = 48000
        val band = EqBand(1000.0, 6.0, 1.0, FilterType.PK)
        val chain = EqChain(ParametricEq(0.0, listOf(band)), sampleRate, 1)

        val count = sampleRate
        val input = buffer(count * 4)
        repeat(count) { input.putFloat((0.25 * sin(2 * PI * 1000 * it / sampleRate)).toFloat()) }
        input.flip()
        val output = run(chain, input, C.ENCODING_PCM_FLOAT)

        // Measure the steady-state peak over the second half, after the filter has settled.
        val peak = (count / 2 until count).maxOf { abs(output[it].toDouble()) }
        val expected = 0.25 * 10.0.pow(6.0 / 20)
        assertEquals(expected, peak, expected * 0.01)

        val coefficients = BiquadDesign.design(band.filterType, sampleRate, 1000.0, 6.0, 1.0)
        assertEquals(6.0, BiquadDesign.magnitudeDb(coefficients, sampleRate, 1000.0), 1e-6)
        assertTrue(BiquadDesign.isStable(coefficients))
    }

    @Test
    fun filters24BitInputLike16BitInput() {
        val eq =
            ParametricEq(
                -3.0,
                listOf(
                    EqBand(200.0, 4.0, 0.7, FilterType.LSC),
                    EqBand(3000.0, -5.0, 2.0, FilterType.PK),
                ),
            )
        val n = 2000
        val sine = DoubleArray(n) { 0.5 * sin(2 * PI * 440 * it / 48000) }
        val from16 = run16(EqChain(eq, 48000, 1), *IntArray(n) { (sine[it] * 32768).toInt() })
        val from24 =
            run(
                EqChain(eq, 48000, 1),
                pcm24(*IntArray(n) { (sine[it] * 8_388_608).toInt() }),
                C.ENCODING_PCM_24BIT,
            )
        assertArrayEquals(from16, from24, 1e-4f)
    }

    @Test
    fun dropsBandsAtOrAboveNyquist() {
        val eq = ParametricEq(0.0, listOf(EqBand(30000.0, 6.0, 1.0, FilterType.PK)))
        assertTrue(EqChain(eq, 44100, 2).isPassthrough)
    }
}
