/*
 * Copyright (c) 2026 Auxio Project
 * AssetSource.kt is part of Auxio.
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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

/**
 * Read-only access to bundled files, abstracted so the index can be tested against a plain
 * directory.
 */
interface AssetSource {
    /** Child names of [path], sorted. Empty when [path] is a file or missing. */
    fun list(path: String): List<String>

    fun open(path: String): InputStream
}

class AndroidAssetSource @Inject constructor(@ApplicationContext context: Context) : AssetSource {
    private val assets = context.assets

    override fun list(path: String): List<String> = assets.list(path).orEmpty().sorted()

    override fun open(path: String): InputStream = assets.open(path)
}
