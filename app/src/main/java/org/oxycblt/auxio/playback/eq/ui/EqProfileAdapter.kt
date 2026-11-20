/*
 * Copyright (c) 2026 Auxio Project
 * EqProfileAdapter.kt is part of Auxio.
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

import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemEqEmptyBinding
import org.oxycblt.auxio.databinding.ItemEqGraphBinding
import org.oxycblt.auxio.databinding.ItemEqProfileBinding
import org.oxycblt.auxio.playback.eq.EqProfile
import org.oxycblt.auxio.playback.eq.ParametricEq
import org.oxycblt.auxio.util.inflater

/** Shows the active profile's response graph, followed by every selectable profile. */
class EqProfileAdapter(private val listener: Listener) :
    ListAdapter<EqProfileAdapter.Item, RecyclerView.ViewHolder>(DIFF_CALLBACK) {
    interface Listener {
        /** Called when [profile] is chosen, or null when the "No equalization" row is. */
        fun onSelect(profile: EqProfile?)

        fun onDelete(profile: EqProfile)

        fun onOpenAutoEq()

        fun onImportFile()
    }

    sealed interface Item {
        data class Graph(val eq: ParametricEq?) : Item

        data class None(val selected: Boolean) : Item

        data class Profile(val profile: EqProfile, val selected: Boolean) : Item

        data object Empty : Item
    }

    /** Show [profiles], marking [active] as selected. */
    fun submit(profiles: List<EqProfile>, active: EqProfile?) {
        val items = mutableListOf(Item.Graph(active?.eq), Item.None(active == null))
        if (profiles.isEmpty()) {
            items.add(Item.Empty)
        } else {
            profiles.mapTo(items) { Item.Profile(it, it.id == active?.id) }
        }
        submitList(items)
    }

    override fun getItemViewType(position: Int) =
        when (getItem(position)) {
            is Item.Graph -> VIEW_TYPE_GRAPH
            is Item.None,
            is Item.Profile -> VIEW_TYPE_PROFILE
            is Item.Empty -> VIEW_TYPE_EMPTY
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = parent.context.inflater
        return when (viewType) {
            VIEW_TYPE_GRAPH -> GraphViewHolder(ItemEqGraphBinding.inflate(inflater, parent, false))
            VIEW_TYPE_PROFILE ->
                ProfileViewHolder(ItemEqProfileBinding.inflate(inflater, parent, false))
            VIEW_TYPE_EMPTY -> EmptyViewHolder(ItemEqEmptyBinding.inflate(inflater, parent, false))
            else -> error("Unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Graph -> (holder as GraphViewHolder).binding.eqResponse.setEq(item.eq)
            is Item.None -> (holder as ProfileViewHolder).bindNone(item.selected, listener)
            is Item.Profile ->
                (holder as ProfileViewHolder).bindProfile(item.profile, item.selected, listener)
            is Item.Empty -> (holder as EmptyViewHolder).bind(listener)
        }
    }

    private class GraphViewHolder(val binding: ItemEqGraphBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class ProfileViewHolder(private val binding: ItemEqProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bindNone(selected: Boolean, listener: Listener) {
            binding.root.setOnClickListener { listener.onSelect(null) }
            binding.eqProfileRadio.isChecked = selected
            binding.eqProfileName.setText(R.string.lbl_eq_none)
            binding.eqProfileInfo.isVisible = false
            binding.eqProfileDelete.visibility = View.INVISIBLE
            binding.eqProfileDelete.setOnClickListener(null)
        }

        fun bindProfile(profile: EqProfile, selected: Boolean, listener: Listener) {
            val context = binding.root.context
            binding.root.setOnClickListener { listener.onSelect(profile) }
            binding.eqProfileRadio.isChecked = selected
            binding.eqProfileName.text = profile.name
            binding.eqProfileInfo.isVisible = true
            binding.eqProfileInfo.text = context.describeProfile(profile)
            binding.eqProfileDelete.visibility = View.VISIBLE
            binding.eqProfileDelete.setOnClickListener { listener.onDelete(profile) }
        }
    }

    private class EmptyViewHolder(private val binding: ItemEqEmptyBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(listener: Listener) {
            binding.eqEmptyAutoeq.setOnClickListener { listener.onOpenAutoEq() }
            binding.eqEmptyImport.setOnClickListener { listener.onImportFile() }
        }
    }

    private companion object {
        const val VIEW_TYPE_GRAPH = 0
        const val VIEW_TYPE_PROFILE = 1
        const val VIEW_TYPE_EMPTY = 2

        /** Rebind in place rather than cross-fading, so the graph and radios don't flicker. */
        val PAYLOAD_UPDATE = Any()

        val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<Item>() {
                override fun areItemsTheSame(oldItem: Item, newItem: Item) =
                    when (oldItem) {
                        is Item.Profile ->
                            newItem is Item.Profile && oldItem.profile.id == newItem.profile.id
                        else -> oldItem::class == newItem::class
                    }

                override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem

                override fun getChangePayload(oldItem: Item, newItem: Item) = PAYLOAD_UPDATE
            }
    }
}
