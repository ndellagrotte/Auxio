/*
 * Copyright (c) 2026 Auxio Project
 * EqAudioSink.kt is part of Auxio.
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

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Runs [eq] and then [replayGain] on decoded PCM before it reaches [sink], and hands [sink] 32-bit
 * float.
 *
 * [eq] is an [org.oxycblt.auxio.playback.eq.EqAudioProcessor] in playback: it must accept linear
 * PCM and output float, and [replayGain] must accept float. Both must produce all of their output
 * synchronously in `queueInput`.
 *
 * The processors run here and not in `DefaultAudioSink`'s processor chain because that chain only
 * runs after high-resolution PCM has been converted to 16-bit. With float output enabled,
 * `DefaultAudioSink` skips the chain entirely.
 *
 * ReplayGain runs after the EQ, on its float output. Both are linear and nothing between them
 * clamps, so the order makes no audible difference, and ReplayGain can't clip before an EQ cut.
 *
 * Formats [eq] can't handle (encoded passthrough) are forwarded untouched.
 *
 * The processors may be shared between the sinks of several renderers, as long as only one of them
 * plays at a time.
 */
@OptIn(UnstableApi::class)
class EqAudioSink(
    sink: AudioSink,
    private val eq: AudioProcessor,
    private val replayGain: AudioProcessor,
) : ForwardingAudioSink(sink) {
    private var processing = false

    /**
     * Processed output for the buffer being handled. `handleBuffer` gets the same input buffer back
     * until that buffer is fully handled, so each input is processed only once and this is what
     * gets re-offered.
     */
    private var pending: ByteBuffer? = null

    @Suppress("DEPRECATION") // Carrying over the deprecated buffer size override.
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        pending = null
        val format = audioSinkConfig.format
        processing =
            MimeTypes.AUDIO_RAW == format.sampleMimeType &&
                eq.configure(AudioFormat(format)) != AudioFormat.NOT_SET
        if (!processing) {
            super.configure(audioSinkConfig)
            return
        }
        val floatFormat = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
        replayGain.configure(AudioFormat(floatFormat))
        eq.flush(AudioProcessor.StreamMetadata.DEFAULT)
        replayGain.flush(AudioProcessor.StreamMetadata.DEFAULT)
        super.configure(
            AudioSink.AudioSinkConfig.Builder(floatFormat)
                .setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
                .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
                .setTimeline(audioSinkConfig.timeline)
                .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
                .build(),
        )
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!processing) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        val output =
            pending
                ?: run {
                    eq.queueInput(buffer)
                    replayGain.queueInput(eq.output)
                    replayGain.output
                }
        val handled = super.handleBuffer(output, presentationTimeUs, encodedAccessUnitCount)
        pending = if (handled) null else output
        return handled
    }

    override fun flush() {
        pending = null
        if (processing) {
            eq.flush(AudioProcessor.StreamMetadata.DEFAULT)
            replayGain.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        super.flush()
    }

    override fun reset() {
        pending = null
        processing = false
        eq.reset()
        replayGain.reset()
        super.reset()
    }
}
