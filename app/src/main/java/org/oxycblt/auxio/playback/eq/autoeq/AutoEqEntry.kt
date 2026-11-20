/*
 * Copyright (c) 2026 Auxio Project
 * AutoEqEntry.kt is part of Auxio.
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

/** One measured headphone in the bundled AutoEQ database (one `ParametricEQ.txt`). */
data class AutoEqEntry(
    /** Directory/headphone name, e.g. `Sony WH-1000XM4 (ANC on)`. */
    val label: String,
    /** Measurement source directory, e.g. `oratory1990`. */
    val source: String,
    /** `in-ear`, `over-ear`, `earbud` or `unknown`. */
    val form: String,
    val rig: String,
    /** The actual form directory name; needed to rebuild the asset path. */
    val formDirectory: String,
    val modelKey: String,
) {
    val assetPath: String
        get() =
            "${AutoEqIndexBuilder.RESULTS_DIR}/$source/$formDirectory/$label/$label ParametricEQ.txt"
}

/** A headphone model, grouping every measurement variant of it. */
data class AutoEqModel(val key: String, val name: String, val variantCount: Int)
