/*
 * Copyright (c) 2026 Auxio Project
 * EqProfileRepository.kt is part of Auxio.
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

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber as L

/**
 * Stores saved [EqProfile]s and which one is active.
 *
 * Profiles are kept as a JSON array in a dedicated SharedPreferences file. The scale involved (a
 * handful of profiles with at most 20 bands each) does not warrant a database.
 */
@Singleton
class EqProfileRepository @Inject constructor(@ApplicationContext context: Context) {
    /** Notified whenever the active profile changes, including when its contents change. */
    interface Listener {
        fun onActiveProfileChanged(profile: EqProfile?)
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<Listener>()

    private val _profiles = MutableStateFlow(loadProfiles())
    /** All saved profiles, newest first. */
    val profiles: StateFlow<List<EqProfile>> = _profiles

    private val _activeProfile = MutableStateFlow(prefs.getString(KEY_ACTIVE_ID, null)?.let(::find))
    /** The profile currently applied to playback, or null if the EQ is off. */
    val activeProfile: StateFlow<EqProfile?> = _activeProfile

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Activate the profile with [id], or turn the EQ off if null. */
    @Synchronized
    fun setActive(id: String?) {
        L.d("Activating EQ profile $id")
        prefs.edit { putString(KEY_ACTIVE_ID, id) }
        updateActive(id?.let(::find))
    }

    /** Save [newProfiles], replacing any existing profiles with the same IDs. */
    @Synchronized
    fun upsertAll(newProfiles: List<EqProfile>) {
        if (newProfiles.isEmpty()) return
        L.d("Saving ${newProfiles.size} EQ profiles")
        val ids = newProfiles.mapTo(HashSet()) { it.id }
        commit(_profiles.value.filterNot { it.id in ids } + newProfiles)
    }

    /** Delete the profile with [id]. Turns the EQ off if it was active. */
    @Synchronized
    fun delete(id: String) {
        L.d("Deleting EQ profile $id")
        if (_activeProfile.value?.id == id) {
            prefs.edit { remove(KEY_ACTIVE_ID) }
        }
        commit(_profiles.value.filterNot { it.id == id })
    }

    private fun commit(profiles: List<EqProfile>) {
        val sorted = profiles.sortedByDescending { it.addedTimestamp }
        prefs.edit { putString(KEY_PROFILES, EqProfileCodec.encode(sorted)) }
        _profiles.value = sorted
        updateActive(prefs.getString(KEY_ACTIVE_ID, null)?.let(::find))
    }

    private fun updateActive(profile: EqProfile?) {
        if (_activeProfile.value == profile) return
        _activeProfile.value = profile
        for (listener in listeners) {
            listener.onActiveProfileChanged(profile)
        }
    }

    private fun find(id: String) = _profiles.value.find { it.id == id }

    private fun loadProfiles(): List<EqProfile> {
        val text = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            EqProfileCodec.decode(text).sortedByDescending { it.addedTimestamp }
        } catch (e: JSONException) {
            L.e("Unable to read saved EQ profiles: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    private companion object {
        const val PREFS_NAME = "eq_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE_ID = "active_id"
    }
}

/** Converts between the stored JSON array and [EqProfile]s. */
internal object EqProfileCodec {
    fun encode(profiles: List<EqProfile>): String {
        val array = JSONArray()
        for (profile in profiles) {
            val bands = JSONArray()
            for (band in profile.eq.bands) {
                bands.put(
                    JSONObject()
                        .put("frequency", band.frequency)
                        .put("gain", band.gain)
                        .put("q", band.q)
                        .put("type", band.filterType.name)
                        .put("enabled", band.enabled),
                )
            }
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .putOpt("source", profile.source)
                    .putOpt("rig", profile.rig)
                    .put("preamp", profile.eq.preampDb)
                    .put("bands", bands)
                    .put("added", profile.addedTimestamp),
            )
        }
        return array.toString()
    }

    fun decode(text: String): List<EqProfile> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val bandArray = obj.getJSONArray("bands")
            val bands =
                (0 until bandArray.length()).mapNotNull { j ->
                    val band = bandArray.getJSONObject(j)
                    val type =
                        FilterType.entries.find { it.name == band.getString("type") }
                            ?: return@mapNotNull null
                    EqBand(
                        frequency = band.getDouble("frequency"),
                        gain = band.getDouble("gain"),
                        q = band.getDouble("q"),
                        filterType = type,
                        enabled = band.optBoolean("enabled", true),
                    )
                }
            EqProfile(
                id = obj.getString("id"),
                name = obj.getString("name"),
                source = obj.optStringOrNull("source"),
                rig = obj.optStringOrNull("rig"),
                eq = ParametricEq(obj.optDouble("preamp", 0.0), bands),
                addedTimestamp = obj.optLong("added", 0L),
            )
        }
    }

    private fun JSONObject.optStringOrNull(key: String) =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
