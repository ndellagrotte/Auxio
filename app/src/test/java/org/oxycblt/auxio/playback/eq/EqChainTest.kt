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
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqChainTest {
    private fun shorts(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder())
        values.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun process16(chain: EqChain, input: ByteBuffer): ShortArray {
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.nativeOrder())
        chain.process(input, C.ENCODING_PCM_16BIT, output)
        output.flip()
        return ShortArray(output.remaining() / 2) { output.getShort() }
    }

    @Test
    fun flatEqIsPassthrough() {
        val chain = EqChain(ParametricEq(0.0, emptyList()), 48000, 2)
        assertTrue(chain.isPassthrough)
        val samples = intArrayOf(0, 1, -1, 12345, -32768, 32767)
        assertArrayEquals(
            samples.map { it.toShort() }.toShortArray(),
            process16(chain, shorts(*samples)),
        )
    }

    @Test
    fun preampScales16BitAndClamps() {
        val half = EqChain(ParametricEq(-20 * kotlin.math.log10(2.0), emptyList()), 48000, 1)
        assertArrayEquals(shortArrayOf(8192, -8192), process16(half, shorts(16384, -16384)))

        val boost = EqChain(ParametricEq(6.0, emptyList()), 48000, 1)
        assertArrayEquals(
            shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE),
            process16(boost, shorts(30000, -30000)),
        )
    }

    @Test
    fun peakingBandBoostsItsCentreFrequency() {
        val sampleRate = 48000
        val band = EqBand(1000.0, 6.0, 1.0, FilterType.PK)
        val chain = EqChain(ParametricEq(0.0, listOf(band)), sampleRate, 1)

        val count = sampleRate
        val input = ByteBuffer.allocate(count * 4).order(ByteOrder.nativeOrder())
        repeat(count) { input.putFloat((0.25 * sin(2 * PI * 1000 * it / sampleRate)).toFloat()) }
        input.flip()
        val output = ByteBuffer.allocate(count * 4).order(ByteOrder.nativeOrder())
        chain.process(input, C.ENCODING_PCM_FLOAT, output)
        output.flip()

        // Measure the steady-state peak over the second half, after the filter has settled.
        var peak = 0.0
        for (i in 0 until count) {
            val sample = output.getFloat().toDouble()
            if (i >= count / 2) peak = maxOf(peak, abs(sample))
        }
        val expected = 0.25 * 10.0.pow(6.0 / 20)
        assertEquals(expected, peak, expected * 0.01)

        val coefficients = BiquadDesign.design(band.filterType, sampleRate, 1000.0, 6.0, 1.0)
        assertEquals(6.0, BiquadDesign.magnitudeDb(coefficients, sampleRate, 1000.0), 1e-6)
        assertTrue(BiquadDesign.isStable(coefficients))
    }

    @Test
    fun dropsBandsAtOrAboveNyquist() {
        val eq = ParametricEq(0.0, listOf(EqBand(30000.0, 6.0, 1.0, FilterType.PK)))
        assertTrue(EqChain(eq, 44100, 2).isPassthrough)
    }
}
