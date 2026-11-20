/*
 * Copyright (c) 2026 Auxio Project
 * AutoEqIndexTest.kt is part of Auxio.
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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqIndexTest {
    private val assets = FileAssetSource.fixture()
    private val index = AutoEqIndexBuilder(assets).build()

    private fun entry(source: String, label: String) =
        index.entries.single { it.source == source && it.label == label }

    @Test
    fun indexesEveryProfileWithoutOpeningProfileFiles() {
        assertEquals(10, index.entries.size)
        assertTrue(assets.openedPaths.none { it.endsWith("ParametricEQ.txt") })
    }

    @Test
    fun resolvesRigsInPriorityOrder() {
        // Rig named in the form directory.
        assertEquals("711", entry("crinacle", "64 Audio U12t").rig)
        assertEquals("EARS + 711", entry("crinacle", "Sennheiser HD 600").rig)
        assertEquals("over-ear", entry("crinacle", "Sennheiser HD 600").form)
        // name_index.tsv: last row wins, values trimmed.
        assertEquals("HMS II.3", entry("Rtings", "Bose QuietComfort 45").rig)
        // "ignore" rows are skipped, falling through to Unknown.
        assertEquals("Unknown", entry("Rtings", "Bose QuietComfort 35 II").rig)
        // Legacy sources default to HMS II.3.
        assertEquals("HMS II.3", entry("Innerfidelity", "Sennheiser HD 600 (sample 2)").rig)
        // No rig anywhere.
        assertEquals("Unknown", entry("oratory1990", "Sennheiser HD 600").rig)
    }

    @Test
    fun assetPathPointsAtTheProfile() {
        val e = entry("crinacle", "Audio-Technica ATH-IM70")
        assertEquals(
            "autoeqDB/results/crinacle/711 in-ear/Audio-Technica ATH-IM70/" +
                "Audio-Technica ATH-IM70 ParametricEQ.txt",
            e.assetPath,
        )
        assets.open(e.assetPath).close()
    }

    @Test
    fun groupsVariantsUnderModels() {
        assertEquals(
            listOf(
                "64 Audio U12t",
                "Audio-Technica ATH-IM70",
                "Bose QuietComfort 35 II",
                "Bose QuietComfort 45",
                "Sennheiser HD 600",
                "Sony WH-1000XM4",
            ),
            index.models.map { it.name },
        )
        assertEquals(3, index.models.single { it.name == "Sennheiser HD 600" }.variantCount)

        val sony = index.models.single { it.name == "Sony WH-1000XM4" }
        assertEquals(
            listOf("Sony WH-1000XM4 (ANC off)", "Sony WH-1000XM4 (ANC on)"),
            index.variants(sony.key).map { it.label },
        )
    }

    @Test
    fun searchRanksPrefixMatchesFirst() {
        assertEquals(
            listOf("Audio-Technica ATH-IM70", "64 Audio U12t"),
            index.searchModels("audio").map { it.name },
        )
        assertEquals(
            listOf("Bose QuietComfort 35 II", "Bose QuietComfort 45"),
            index.searchModels("quiet").map { it.name },
        )
        assertEquals(listOf("Bose QuietComfort 45"), index.searchModels("45").map { it.name })
        assertEquals(index.models, index.searchModels("  "))
    }

    @Test
    fun loadsAProfile() = runBlocking {
        val eq = AutoEqDatabase(assets).loadEq(entry("oratory1990", "Sennheiser HD 600"))
        assertEquals(-1.0, eq.preampDb, 0.0)
        assertEquals(1, eq.bands.size)
    }
}
