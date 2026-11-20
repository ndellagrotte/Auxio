/*
 * Copyright (c) 2026 Auxio Project
 * AutoEqIndex.kt is part of Auxio.
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

/** Immutable, searchable view of the AutoEQ database. */
class AutoEqIndex(val entries: List<AutoEqEntry>) {
    /** Every model, sorted alphabetically. */
    val models: List<AutoEqModel>
    private val variantsByModel: Map<String, List<AutoEqEntry>> =
        entries
            .groupBy { it.modelKey }
            .mapValues { (_, list) ->
                list.sortedWith(compareBy({ it.source }, { it.label }, { it.formDirectory }))
            }

    init {
        models =
            variantsByModel
                .map { (key, variants) ->
                    AutoEqModel(
                        key = key,
                        name = mostCommon(variants.map { ModelNames.displayName(it.label) }),
                        variantCount = variants.size,
                    )
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * Models matching [query], best matches first: exact, then prefix, then word prefix, then
     * substring. An empty query returns every model.
     */
    fun searchModels(query: String): List<AutoEqModel> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return models
        return models
            .mapNotNull { model ->
                val t = model.name.lowercase()
                val score =
                    when {
                        t == q -> 0
                        t.startsWith(q) -> 1
                        t.split(' ', '-').any { it.startsWith(q) } -> 2
                        t.contains(q) -> 3
                        else -> return@mapNotNull null
                    }
                score to model
            }
            .sortedBy { it.first } // stable: keeps alphabetical order within a score
            .take(MAX_RESULTS)
            .map { it.second }
    }

    fun variants(modelKey: String): List<AutoEqEntry> = variantsByModel[modelKey].orEmpty()

    private fun mostCommon(values: List<String>): String =
        values
            .groupingBy { it }
            .eachCount()
            .maxWith(
                compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key },
            )
            .key

    private companion object {
        const val MAX_RESULTS = 100
    }
}

/** Builds an [AutoEqIndex] from directory listings only; no profile file is opened. */
class AutoEqIndexBuilder(private val assets: AssetSource) {
    fun build(): AutoEqIndex {
        val rigs = RigResolver(assets)
        val entries = ArrayList<AutoEqEntry>(9_000)
        for (source in assets.list(RESULTS_DIR)) {
            if (source.endsWith(".md")) continue
            for (formDirectory in assets.list("$RESULTS_DIR/$source")) {
                val form = RigResolver.parseFormDirectory(formDirectory).form
                for (label in assets.list("$RESULTS_DIR/$source/$formDirectory")) {
                    entries +=
                        AutoEqEntry(
                            label = label,
                            source = source,
                            form = form,
                            rig = rigs.resolve(source, formDirectory, label),
                            formDirectory = formDirectory,
                            modelKey = ModelNames.key(label),
                        )
                }
            }
        }
        return AutoEqIndex(entries)
    }

    companion object {
        const val RESULTS_DIR = "autoeqDB/results"
        const val MEASUREMENTS_DIR = "autoeqDB/measurements"
    }
}
