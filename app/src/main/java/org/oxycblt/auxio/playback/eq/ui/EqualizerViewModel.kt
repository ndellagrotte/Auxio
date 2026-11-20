/*
 * Copyright (c) 2026 Auxio Project
 * EqualizerViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.eq.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.eq.EqProfile
import org.oxycblt.auxio.playback.eq.EqProfileRepository
import org.oxycblt.auxio.playback.eq.ParametricEqParser
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import timber.log.Timber as L

/** Manages the list of saved EQ profiles and importing new ones from files. */
@HiltViewModel
class EqualizerViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: EqProfileRepository,
) : ViewModel() {
    /** All saved profiles, newest first. */
    val profiles = profileRepository.profiles

    /** The profile currently applied to playback, or null if the EQ is off. */
    val activeProfile = profileRepository.activeProfile

    private val _importResult = MutableEvent<ImportResult>()
    /** The outcome of the last [importFile] call. */
    val importResult: Event<ImportResult> = _importResult

    /** Apply [profile] to playback, or turn the EQ off if null. */
    fun select(profile: EqProfile?) = profileRepository.setActive(profile?.id)

    fun delete(profile: EqProfile) = profileRepository.delete(profile.id)

    /** Import an Equalizer APO parametric EQ file. */
    fun importFile(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { readProfile(uri) }
            if (result is ImportResult.Success) {
                profileRepository.upsertAll(listOf(result.profile))
            }
            _importResult.put(result)
        }
    }

    private fun readProfile(uri: Uri): ImportResult {
        val resolver = context.contentResolver
        val text =
            try {
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: IOException) {
                L.e("Unable to read EQ file: ${e.stackTraceToString()}")
                null
            } catch (e: SecurityException) {
                L.e("Unable to open EQ file: ${e.stackTraceToString()}")
                null
            } ?: return ImportResult.ReadFailed

        val eq = ParametricEqParser.parse(text)
        ParametricEqParser.validate(eq)?.let {
            return ImportResult.Invalid(it)
        }

        val now = System.currentTimeMillis()
        val profile =
            EqProfile(
                id = EqProfile.customId(now),
                name = displayName(uri) ?: context.getString(R.string.lbl_eq_imported),
                source = null,
                rig = null,
                eq = eq,
                addedTimestamp = now,
            )
        return ImportResult.Success(profile)
    }

    /** The file name of [uri], without the extension or AutoEq's " ParametricEQ" suffix. */
    private fun displayName(uri: Uri): String? =
        try {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            } catch (e: Exception) {
                L.w("Unable to query EQ file name: $e")
                null
            }
            ?.substringBeforeLast('.')
            ?.removeSuffix(" ParametricEQ")
            ?.trim()
            ?.ifEmpty { null }
}

/** The outcome of importing an EQ file. */
sealed interface ImportResult {
    data class Success(val profile: EqProfile) : ImportResult

    data class Invalid(val problem: ParametricEqParser.Problem) : ImportResult

    data object ReadFailed : ImportResult
}
