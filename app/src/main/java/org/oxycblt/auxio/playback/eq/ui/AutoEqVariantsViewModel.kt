/*
 * Copyright (c) 2026 Auxio Project
 * AutoEqVariantsViewModel.kt is part of Auxio.
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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.eq.EqProfile
import org.oxycblt.auxio.playback.eq.EqProfileRepository
import org.oxycblt.auxio.playback.eq.ParametricEqParser
import org.oxycblt.auxio.playback.eq.autoeq.AutoEqDatabase
import org.oxycblt.auxio.playback.eq.autoeq.AutoEqEntry
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import timber.log.Timber as L

/**
 * The second step of adding AutoEQ profiles: choosing which measurements of a model to save.
 *
 * Receives the `modelKey` navigation argument through [SavedStateHandle].
 */
@HiltViewModel
class AutoEqVariantsViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AutoEqDatabase,
    private val profileRepository: EqProfileRepository,
) : ViewModel() {
    private val modelKey: String = requireNotNull(savedStateHandle["modelKey"])

    private val _variants = MutableStateFlow<List<AutoEqEntry>>(emptyList())
    /** Every measurement of the model. */
    val variants: StateFlow<List<AutoEqEntry>> = _variants

    private val _selected = MutableStateFlow<Set<AutoEqEntry>>(emptySet())
    /** The measurements that will be saved. */
    val selected: StateFlow<Set<AutoEqEntry>> = _selected

    private val _saving = MutableStateFlow(false)
    /** Whether the selected measurements are currently being saved. */
    val saving: StateFlow<Boolean> = _saving

    private val _saveResult = MutableEvent<Boolean>()
    /** Whether the last [save] call saved anything. */
    val saveResult: Event<Boolean> = _saveResult

    init {
        viewModelScope.launch {
            val variants = database.variants(modelKey)
            _variants.value = variants
            // Nothing to choose between, so save the user a tap.
            if (variants.size == 1) {
                _selected.value = variants.toSet()
            }
        }
    }

    fun toggle(entry: AutoEqEntry) {
        val current = _selected.value
        _selected.value = if (entry in current) current - entry else current + entry
    }

    /** Save the selected measurements as EQ profiles. */
    fun save() {
        if (_saving.value) return
        val entries = _variants.value.filter { it in _selected.value }
        if (entries.isEmpty()) return
        _saving.value = true
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val profiles = entries.mapIndexedNotNull { i, entry ->
                val eq =
                    try {
                        database.loadEq(entry)
                    } catch (e: IOException) {
                        L.e("Unable to load ${entry.assetPath}: ${e.stackTraceToString()}")
                        return@mapIndexedNotNull null
                    }
                ParametricEqParser.validate(eq)?.let {
                    L.e("Skipping invalid AutoEQ profile ${entry.assetPath}: $it")
                    return@mapIndexedNotNull null
                }
                EqProfile(
                    id = EqProfile.autoEqId(entry.source, entry.formDirectory, entry.label),
                    name = entry.label,
                    source = entry.source,
                    rig = entry.rig,
                    eq = eq,
                    // Keep the order the variants were listed in.
                    addedTimestamp = now - i,
                )
            }
            profileRepository.upsertAll(profiles)
            _saving.value = false
            _saveResult.put(profiles.isNotEmpty())
        }
    }
}
