/*
 * Copyright (c) 2026 Auxio Project
 * EqAudioProcessor.kt is part of Auxio.
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
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import timber.log.Timber as L

/**
 * An [AudioProcessor] that applies the active [EqProfile] to decoded PCM.
 *
 * Accepts any linear PCM encoding and always outputs 32-bit float (unclamped), including while the
 * EQ is off, so turning the EQ on or off never changes the output format. Other formats are not
 * handled ([onConfigure] returns [AudioFormat.NOT_SET]) and bypass the EQ.
 *
 * This runs in [org.oxycblt.auxio.playback.service.EqAudioSink], ahead of `DefaultAudioSink`, since
 * that sink's own processor chain only ever sees 16-bit PCM.
 *
 * Profile changes may arrive from any thread: they publish an immutable snapshot, and the audio
 * thread picks it up at the start of the next buffer, recomputing coefficients for the current
 * sample rate. A change becomes audible once the audio already queued in the AudioTrack has played
 * (a few hundred ms).
 *
 * Note: This audio processor must be attached with [attach] to a respective [Player] instance to
 * function properly.
 */
@OptIn(UnstableApi::class)
class EqAudioProcessor @Inject constructor(private val profileRepository: EqProfileRepository) :
    BaseAudioProcessor(), EqProfileRepository.Listener {
    @Volatile private var requested: ParametricEq? = null

    // Audio thread only.
    private var applied: ParametricEq? = null
    private var chain: EqChain? = null

    fun attach() {
        profileRepository.addListener(this)
        requested = profileRepository.activeProfile.value?.eq
    }

    /** Remove this instance from the components required for it to function correctly. */
    fun release() {
        profileRepository.removeListener(this)
    }

    override fun onActiveProfileChanged(profile: EqProfile?) {
        L.d("Active EQ profile changed to ${profile?.name}")
        requested = profile?.eq
    }

    // --- AUDIO PROCESSOR IMPLEMENTATION ---

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (
            inputAudioFormat.encoding in EqChain.SUPPORTED_ENCODINGS &&
                inputAudioFormat.channelCount > 0
        ) {
            AudioFormat(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                C.ENCODING_PCM_FLOAT,
            )
        } else {
            AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val encoding = inputAudioFormat.encoding
        val output = replaceOutputBuffer(size / Util.getByteDepth(encoding) * FLOAT_BYTES)
        currentChain().process(inputBuffer.order(ByteOrder.nativeOrder()), encoding, output)
        // Drop a trailing partial sample, if any, so the input always counts as consumed.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun currentChain(): EqChain {
        val eq = requested
        val format = inputAudioFormat
        val existing = chain
        if (
            existing != null &&
                eq === applied &&
                existing.sampleRate == format.sampleRate &&
                existing.channelCount == format.channelCount
        ) {
            return existing
        }
        applied = eq
        return EqChain(eq ?: FLAT, format.sampleRate, format.channelCount)
            .also { new -> existing?.let(new::copyStateFrom) }
            .also { chain = it }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        chain?.resetState()
    }

    override fun onReset() {
        chain = null
        applied = null
    }

    private companion object {
        const val FLOAT_BYTES = 4
        val FLAT = ParametricEq(0.0, emptyList())
    }
}
