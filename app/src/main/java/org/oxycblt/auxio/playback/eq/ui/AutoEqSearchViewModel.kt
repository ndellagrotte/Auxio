/*
 * Copyright (c) 2026 Auxio Project
 * AutoEqSearchViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.eq.autoeq.AutoEqDatabase
import org.oxycblt.auxio.playback.eq.autoeq.AutoEqModel

/** Searches the bundled AutoEQ database for headphone models. */
@HiltViewModel
class AutoEqSearchViewModel @Inject constructor(private val database: AutoEqDatabase) :
    ViewModel() {
    private val _results = MutableStateFlow<List<AutoEqModel>?>(null)
    /** Models matching the current query, or null while the database is still being indexed. */
    val results: StateFlow<List<AutoEqModel>?> = _results

    private var query: String? = null
    private var searchJob: Job? = null

    init {
        search("")
    }

    /** Search for models matching [query], debouncing rapid changes. */
    fun search(query: String) {
        if (query == this.query) return
        this.query = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isNotEmpty() && database.isLoaded) {
                delay(DEBOUNCE_MS)
            }
            _results.value = database.searchModels(query)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
