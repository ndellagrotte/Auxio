/*
 * Copyright (c) 2026 Auxio Project
 * ParametricEqParser.kt is part of Auxio.
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
 * Parses Equalizer APO / AutoEq `ParametricEQ.txt` content:
 * ```
 * Preamp: -6.3 dB
 * Filter 1: ON LSC Fc 105 Hz Gain 6.6 dB Q 0.70
 * Filter 2: ON PK Fc 2240 Hz Gain -2.2 dB Q 1.83
 * ```
 *
 * Lines that aren't a preamp or an enabled PK/LSC/HSC filter are ignored.
 */
object ParametricEqParser {
    private const val NUMBER = """[-+]?(?:\d+(?:\.\d*)?|\.\d+)"""
    private val PREAMP = Regex("""^Preamp:\s*($NUMBER)\s*dB""", RegexOption.IGNORE_CASE)
    private val FILTER =
        Regex("""^Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)\b(.*)$""", RegexOption.IGNORE_CASE)
    private val FC = Regex("""\bFc\s+($NUMBER)\s*Hz""", RegexOption.IGNORE_CASE)
    private val GAIN = Regex("""\bGain\s+($NUMBER)\s*dB""", RegexOption.IGNORE_CASE)
    private val Q = Regex("""\bQ\s+($NUMBER)""", RegexOption.IGNORE_CASE)

    /** Q used for shelves when the file doesn't specify one (Butterworth). */
    const val DEFAULT_Q = 0.707

    /** Maximum number of bands accepted by [validate]. */
    const val MAX_BANDS = 20

    fun parse(content: String): ParametricEq {
        var preamp = 0.0
        val bands = mutableListOf<EqBand>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            PREAMP.find(line)?.let { preamp = it.groupValues[1].toDouble() }
            parseFilter(line)?.let(bands::add)
        }
        return ParametricEq(preamp, bands)
    }

    private fun parseFilter(line: String): EqBand? {
        val match = FILTER.find(line) ?: return null
        val (state, typeName, params) = match.destructured
        if (!state.equals("ON", ignoreCase = true)) return null
        val type =
            FilterType.entries.firstOrNull { it.name.equals(typeName, ignoreCase = true) }
                ?: return null

        val frequency = FC.find(params)?.groupValues?.get(1)?.toDouble() ?: return null
        val gain = GAIN.find(params)?.groupValues?.get(1)?.toDouble() ?: return null
        val q = Q.find(params)?.groupValues?.get(1)?.toDouble()
        return when (type) {
            FilterType.PK -> EqBand(frequency, gain, q ?: return null, type)
            FilterType.LSC,
            FilterType.HSC -> EqBand(frequency, gain, q ?: DEFAULT_Q, type)
        }
    }

    /** Returns the first problem with [eq], or null if it is usable. */
    fun validate(eq: ParametricEq): Problem? {
        if (eq.preampDb !in -50.0..50.0) return Problem.Preamp(eq.preampDb)
        if (eq.bands.isEmpty()) return Problem.NoFilters
        if (eq.bands.size > MAX_BANDS) return Problem.TooManyFilters(eq.bands.size)
        eq.bands.forEachIndexed { i, band ->
            val n = i + 1
            if (band.frequency <= 0 || band.frequency > 100_000) {
                return Problem.Frequency(n, band.frequency)
            }
            if (band.gain !in -30.0..30.0) return Problem.Gain(n, band.gain)
            if (band.q <= 0 || band.q > 20) return Problem.Q(n, band.q)
        }
        return null
    }

    /** A reason a parsed [ParametricEq] cannot be used. Filter indices are 1-based. */
    sealed interface Problem {
        data class Preamp(val db: Double) : Problem

        data object NoFilters : Problem

        data class TooManyFilters(val count: Int) : Problem

        data class Frequency(val filter: Int, val hz: Double) : Problem

        data class Gain(val filter: Int, val db: Double) : Problem

        data class Q(val filter: Int, val q: Double) : Problem
    }
}
