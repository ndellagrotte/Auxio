/*
 * Copyright (c) 2026 Auxio Project
 * EqText.kt is part of Auxio.
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
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.eq.EqProfile
import org.oxycblt.auxio.playback.eq.ParametricEqParser
import org.oxycblt.auxio.util.getPlural

/** Joins [parts] with the EQ info separator, e.g. "oratory1990 · GRAS 43AG-7". */
fun Context.joinEqInfo(parts: List<String>) =
    parts.reduceOrNull { acc, part -> getString(R.string.fmt_eq_info, acc, part) } ?: ""

/** Strip a measurement rig that could not be determined. */
fun knownRig(rig: String?) = rig?.takeUnless { it.equals("Unknown", ignoreCase = true) }

/** A summary of [profile]: its measurement source, rig, and band count. */
fun Context.describeProfile(profile: EqProfile) =
    joinEqInfo(
        listOfNotNull(
            profile.source,
            knownRig(profile.rig),
            getPlural(R.plurals.fmt_eq_band_count, profile.eq.bands.size),
        ),
    )

/** A user-facing explanation of [problem]. */
fun Context.describeProblem(problem: ParametricEqParser.Problem) =
    when (problem) {
        is ParametricEqParser.Problem.Preamp -> getString(R.string.err_eq_preamp, problem.db)
        is ParametricEqParser.Problem.NoFilters -> getString(R.string.err_eq_no_filters)
        is ParametricEqParser.Problem.TooManyFilters ->
            getString(
                R.string.err_eq_too_many_filters,
                problem.count,
                ParametricEqParser.MAX_BANDS,
            )
        is ParametricEqParser.Problem.Frequency ->
            getString(R.string.err_eq_frequency, problem.filter, problem.hz)
        is ParametricEqParser.Problem.Gain ->
            getString(R.string.err_eq_gain, problem.filter, problem.db)
        is ParametricEqParser.Problem.Q -> getString(R.string.err_eq_q, problem.filter, problem.q)
    }
