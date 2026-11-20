/*
 * Copyright (c) 2026 Auxio Project
 * BundledDatabaseTest.kt is part of Auxio.
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

import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.playback.eq.ParametricEqParser

/** Checks the real bundled database (src/main/assets/autoeqDB) against the indexer and parser. */
class BundledDatabaseTest {
    private val root = File("src/main/assets/autoeqDB")
    private val index by lazy { AutoEqIndexBuilder(FileAssetSource(root)).build() }

    @Test
    fun indexesTheWholeDatabase() {
        assertTrue("run from the app module directory", root.isDirectory)
        assertTrue(index.entries.size > 8_000)
        assertTrue(index.searchModels("Sennheiser HD 600").first().name == "Sennheiser HD 600")
    }

    @Test
    fun everyProfileExistsAndParses() {
        val assets = FileAssetSource(root)
        for (entry in index.entries) {
            val eq =
                assets.open(entry.assetPath).bufferedReader().use {
                    ParametricEqParser.parse(it.readText())
                }
            assertNull(
                "${entry.assetPath}: ${ParametricEqParser.validate(eq)}",
                ParametricEqParser.validate(eq),
            )
        }
    }
}
