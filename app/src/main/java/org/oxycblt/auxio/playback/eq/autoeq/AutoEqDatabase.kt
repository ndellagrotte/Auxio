/*
 * Copyright (c) 2026 Auxio Project
 * AutoEqDatabase.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.eq.autoeq

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.playback.eq.ParametricEq
import org.oxycblt.auxio.playback.eq.ParametricEqParser
import timber.log.Timber as L

/**
 * The AutoEQ profile database bundled in `assets/autoeqDB`. The index is built once and cached.
 *
 * Data from the AutoEq project (https://github.com/jaakkopasanen/AutoEq), MIT licensed.
 */
@Singleton
class AutoEqDatabase @Inject constructor(private val assets: AssetSource) {
    private val mutex = Mutex()
    @Volatile private var cached: AutoEqIndex? = null

    /** Whether the index has already been built, so that queries return immediately. */
    val isLoaded: Boolean
        get() = cached != null

    suspend fun index(): AutoEqIndex =
        cached
            ?: mutex.withLock {
                cached
                    ?: withContext(Dispatchers.IO) {
                            L.d("Building AutoEQ index")
                            AutoEqIndexBuilder(assets).build()
                        }
                        .also {
                            L.d("Built AutoEQ index with ${it.entries.size} entries")
                            cached = it
                        }
            }

    suspend fun searchModels(query: String): List<AutoEqModel> =
        index().let { withContext(Dispatchers.Default) { it.searchModels(query) } }

    suspend fun variants(modelKey: String): List<AutoEqEntry> = index().variants(modelKey)

    suspend fun loadEq(entry: AutoEqEntry): ParametricEq =
        withContext(Dispatchers.IO) {
            assets.open(entry.assetPath).bufferedReader().use {
                ParametricEqParser.parse(it.readText())
            }
        }
}
