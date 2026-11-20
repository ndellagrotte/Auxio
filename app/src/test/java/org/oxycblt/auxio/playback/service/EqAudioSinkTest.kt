/*
 * Copyright (c) 2026 Auxio Project
 * EqAudioSinkTest.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.service

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.playback.eq.EqChain
import org.oxycblt.auxio.playback.eq.ParametricEq

class EqAudioSinkTest {
    /** Records what reaches the real sink; accepts at most [maxBytesPerCall] bytes per call. */
    private class FakeSink(private val maxBytesPerCall: Int = Int.MAX_VALUE) :
        AudioSink by noOpSink() {
        var configured: AudioSink.AudioSinkConfig? = null
        val received = mutableListOf<ByteBuffer>()
        val written: ByteBuffer = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder())

        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            configured = audioSinkConfig
        }

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int,
        ): Boolean {
            received += buffer
            repeat(minOf(maxBytesPerCall, buffer.remaining())) { written.put(buffer.get()) }
            return !buffer.hasRemaining()
        }

        fun writtenFloats(): FloatArray {
            written.flip()
            return FloatArray(written.remaining() / 4) { written.getFloat() }
        }

        companion object {
            fun noOpSink(): AudioSink =
                Proxy.newProxyInstance(
                    AudioSink::class.java.classLoader,
                    arrayOf(AudioSink::class.java),
                ) { _, method, _ ->
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Long.TYPE -> 0L
                        Integer.TYPE -> 0
                        else -> null
                    }
                } as AudioSink
        }
    }

    /** Stands in for the EQ processor: any linear PCM in, [eq] applied, float out. */
    private class ChainProcessor(private val eq: ParametricEq) : BaseAudioProcessor() {
        private var chain: EqChain? = null

        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
            if (inputAudioFormat.encoding in EqChain.SUPPORTED_ENCODINGS) {
                chain = EqChain(eq, inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
                AudioFormat(
                    inputAudioFormat.sampleRate,
                    inputAudioFormat.channelCount,
                    C.ENCODING_PCM_FLOAT,
                )
            } else {
                AudioFormat.NOT_SET
            }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val encoding = inputAudioFormat.encoding
            val depth =
                when (encoding) {
                    C.ENCODING_PCM_16BIT -> 2
                    C.ENCODING_PCM_24BIT -> 3
                    else -> 4
                }
            val output = replaceOutputBuffer(inputBuffer.remaining() / depth * 4)
            chain!!.process(inputBuffer.order(ByteOrder.nativeOrder()), encoding, output)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
        }
    }

    /** Stands in for ReplayGain: scales float by [gain]. */
    private class GainProcessor(private val gain: Float) : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
            assertEquals(C.ENCODING_PCM_FLOAT, inputAudioFormat.encoding)
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val output = replaceOutputBuffer(inputBuffer.remaining())
            val input = inputBuffer.order(ByteOrder.nativeOrder())
            while (input.remaining() >= 4) output.putFloat(input.getFloat() * gain)
            output.flip()
        }
    }

    private fun rawFormat(encoding: Int, channels: Int = 2) =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(48000)
            .setChannelCount(channels)
            .build()

    private fun config(format: Format) = AudioSink.AudioSinkConfig.Builder(format).build()

    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach(::putShort)
            flip()
        }

    private val halfEq = ParametricEq(-6.0206, emptyList())

    @Test
    fun processesEachBufferOnceEvenWhenTheSinkTakesItInPieces() {
        val fake = FakeSink(maxBytesPerCall = 6)
        val sink = EqAudioSink(fake, ChainProcessor(halfEq), GainProcessor(0.5f))
        sink.configure(config(rawFormat(C.ENCODING_PCM_16BIT)))
        assertEquals(C.ENCODING_PCM_FLOAT, fake.configured!!.format.pcmEncoding)

        val input = pcm16(20000, -20000, 10000, -10000)
        var calls = 0
        while (!sink.handleBuffer(input, 0, 1)) calls++
        assertEquals(2, calls) // 16 float bytes at 6 per call: false, false, true
        assertFalse(input.hasRemaining())
        assertTrue(fake.received.all { it === fake.received[0] })

        // EQ halves, then ReplayGain halves again.
        val out = fake.writtenFloats()
        listOf(5000, -5000, 2500, -2500).forEachIndexed { i, expected ->
            assertEquals(expected / 32768.0, out[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun highResolutionInputReachesTheSinkAsFullPrecisionFloat() {
        val fake = FakeSink()
        val flat = ParametricEq(0.0, emptyList())
        val sink = EqAudioSink(fake, ChainProcessor(flat), GainProcessor(1f))
        sink.configure(config(rawFormat(C.ENCODING_PCM_24BIT, channels = 1)))
        assertEquals(C.ENCODING_PCM_FLOAT, fake.configured!!.format.pcmEncoding)

        val sample = 0x123457 // Odd, so it would be lost in a 16-bit conversion.
        val input =
            ByteBuffer.allocateDirect(3).apply {
                put(sample.toByte())
                put((sample shr 8).toByte())
                put((sample shr 16).toByte())
                flip()
            }
        assertTrue(sink.handleBuffer(input, 0, 1))
        assertEquals(sample / 8_388_608f, fake.writtenFloats().single(), 0f)
    }

    @Test
    fun flushDropsAPartiallyHandledBuffer() {
        val fake = FakeSink(maxBytesPerCall = 4)
        val sink = EqAudioSink(fake, ChainProcessor(halfEq), GainProcessor(1f))
        sink.configure(config(rawFormat(C.ENCODING_PCM_16BIT)))
        assertFalse(sink.handleBuffer(pcm16(1000, 1000), 0, 1))
        sink.flush()

        // The rest of the old buffer (another 1000) must not be re-offered.
        assertFalse(sink.handleBuffer(pcm16(2000, 2000), 0, 1))
        val out = fake.writtenFloats()
        assertEquals(500 / 32768.0, out[0].toDouble(), 1e-4)
        assertEquals(1000 / 32768.0, out[1].toDouble(), 1e-4)
    }

    @Test
    fun encodedAudioPassesThroughUntouched() {
        val fake = FakeSink()
        val sink = EqAudioSink(fake, ChainProcessor(halfEq), GainProcessor(0.5f))
        val ac3 =
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setSampleRate(48000)
                .setChannelCount(6)
                .build()
        val config = config(ac3)
        sink.configure(config)
        assertSame(config, fake.configured)

        val input = ByteBuffer.allocateDirect(4)
        assertTrue(sink.handleBuffer(input, 0, 1))
        assertSame(input, fake.received.single())
    }
}
