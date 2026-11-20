/*
 * Copyright (c) 2026 Auxio Project
 * FileAssetSource.kt is part of Auxio.
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
import java.io.InputStream

/**
 * [AssetSource] over a directory whose layout mirrors `assets/` (the `autoeqDB` folder is [root]).
 */
class FileAssetSource(private val root: File) : AssetSource {
    val openedPaths = mutableListOf<String>()

    private fun resolve(path: String) = File(root, path.removePrefix("autoeqDB/"))

    override fun list(path: String): List<String> = resolve(path).list()?.sorted().orEmpty()

    override fun open(path: String): InputStream {
        openedPaths += path
        return resolve(path).inputStream()
    }

    companion object {
        fun fixture(): FileAssetSource {
            val url =
                requireNotNull(
                    FileAssetSource::class.java.classLoader!!.getResource("autoeqDB-fixture"),
                ) {
                    "fixture missing"
                }
            return FileAssetSource(File(url.toURI()))
        }
    }
}
