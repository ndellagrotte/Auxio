/*
 * Copyright (c) 2026 Auxio Project
 * Eq.kt is part of Auxio.
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

/**
 * Filter types from the Equalizer APO / AutoEq parametric format. Persisted by name, so entries
 * must never be renamed.
 */
enum class FilterType {
    /** Peaking */
    PK,
    /** Low shelf */
    LSC,
    /** High shelf */
    HSC,
}

/** A single parametric EQ band. */
data class EqBand(
    val frequency: Double,
    val gain: Double,
    val q: Double,
    val filterType: FilterType,
    val enabled: Boolean = true,
)

/** A complete parametric EQ: a preamp applied to the signal followed by a cascade of [bands]. */
data class ParametricEq(val preampDb: Double, val bands: List<EqBand>)

/**
 * A saved, named [ParametricEq].
 *
 * @param id Unique identifier. AutoEQ profiles use [autoEqId] so that re-importing them replaces
 *   the old copy.
 * @param source The measurement source (e.g. oratory1990), if known.
 * @param rig The measurement rig (e.g. GRAS 43AG-7), if known.
 */
data class EqProfile(
    val id: String,
    val name: String,
    val source: String?,
    val rig: String?,
    val eq: ParametricEq,
    val addedTimestamp: Long,
) {
    companion object {
        fun autoEqId(source: String, formDirectory: String, label: String) =
            "autoeq:$source/$formDirectory/$label"

        fun customId(nowMs: Long) = "custom:$nowMs"
    }
}
