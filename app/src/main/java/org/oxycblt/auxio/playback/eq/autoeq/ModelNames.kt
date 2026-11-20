/*
 * Copyright (c) 2026 Auxio Project
 * ModelNames.kt is part of Auxio.
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

/** Groups measurement variants such as `X (ANC on)` and `X (ANC off)` under one model. */
object ModelNames {
    private val PARENTHESISED = Regex("""\([^)]*\)""")
    private val WHITESPACE = Regex("""\s+""")
    private val NON_KEY_CHARS = Regex("[^a-z0-9_]")

    /** The label without any parenthesised variant details. */
    fun displayName(label: String): String =
        label.replace(PARENTHESISED, " ").replace(WHITESPACE, " ").trim()

    /**
     * Stable key: the display name lowercased, with spaces turned into `_` and anything outside
     * `[a-z0-9_]` dropped, so spellings like `1More` and `1MORE` merge.
     */
    fun key(label: String): String =
        displayName(label).lowercase().replace(" ", "_").replace(NON_KEY_CHARS, "")
}
