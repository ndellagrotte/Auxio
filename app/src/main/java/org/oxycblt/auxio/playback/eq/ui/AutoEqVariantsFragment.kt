/*
 * Copyright (c) 2026 Auxio Project
 * AutoEqVariantsFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAutoeqVariantsBinding
import org.oxycblt.auxio.databinding.ItemAutoeqVariantBinding
import org.oxycblt.auxio.playback.eq.autoeq.AutoEqEntry
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getDimenPixels
import org.oxycblt.auxio.util.inflater
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat

/** Lists every measurement of a headphone model, letting the user save some as EQ profiles. */
@AndroidEntryPoint
class AutoEqVariantsFragment : ViewBindingFragment<FragmentAutoeqVariantsBinding>() {
    private val args: AutoEqVariantsFragmentArgs by navArgs()
    private val variantsModel: AutoEqVariantsViewModel by viewModels()
    private val variantAdapter = VariantAdapter { variantsModel.toggle(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentAutoeqVariantsBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentAutoeqVariantsBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.autoeqVariantsToolbar.apply {
            title = args.modelName
            setNavigationOnClickListener { findNavController().navigateUp() }
        }
        binding.autoeqVariantsRecycler.adapter = variantAdapter

        val fabMargin = requireContext().getDimenPixels(R.dimen.spacing_medium)
        binding.autoeqVariantsSave.apply {
            setOnApplyWindowInsetsListener { view, insets ->
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = fabMargin + insets.systemBarInsetsCompat.bottom
                }
                insets
            }
            setOnClickListener { variantsModel.save() }
        }

        collectImmediately(variantsModel.variants, variantsModel.selected, ::updateVariants)
        collectImmediately(variantsModel.selected, variantsModel.saving, ::updateSaveButton)
        collectImmediately(variantsModel.saveResult.flow, ::handleSaveResult)
    }

    override fun onDestroyBinding(binding: FragmentAutoeqVariantsBinding) {
        super.onDestroyBinding(binding)
        binding.autoeqVariantsRecycler.adapter = null
    }

    private fun updateVariants(variants: List<AutoEqEntry>, selected: Set<AutoEqEntry>) {
        variantAdapter.submitList(variants.map { VariantItem(it, it in selected) })
    }

    private fun updateSaveButton(selected: Set<AutoEqEntry>, saving: Boolean) {
        requireBinding().autoeqVariantsSave.isEnabled = selected.isNotEmpty() && !saving
    }

    private fun handleSaveResult(saved: Boolean?) {
        when (saved) {
            true -> {
                requireContext().showToast(R.string.lng_eq_profiles_saved)
                findNavController().popBackStack(R.id.equalizer_fragment, false)
            }
            false -> requireContext().showToast(R.string.err_eq_save_failed)
            null -> return
        }
        variantsModel.saveResult.consume()
    }

    private data class VariantItem(val entry: AutoEqEntry, val selected: Boolean)

    private class VariantAdapter(private val onClick: (AutoEqEntry) -> Unit) :
        ListAdapter<VariantItem, VariantViewHolder>(DIFF_CALLBACK) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VariantViewHolder(
                ItemAutoeqVariantBinding.inflate(parent.context.inflater, parent, false),
            )

        override fun onBindViewHolder(holder: VariantViewHolder, position: Int) {
            holder.bind(getItem(position), onClick)
        }
    }

    private class VariantViewHolder(private val binding: ItemAutoeqVariantBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VariantItem, onClick: (AutoEqEntry) -> Unit) {
            val entry = item.entry
            binding.root.setOnClickListener { onClick(entry) }
            binding.autoeqVariantCheck.isChecked = item.selected
            binding.autoeqVariantName.text = entry.label
            binding.autoeqVariantInfo.text =
                binding.root.context.joinEqInfo(listOfNotNull(entry.source, knownRig(entry.rig)))
        }
    }

    private companion object {
        val PAYLOAD_UPDATE = Any()

        val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<VariantItem>() {
                override fun areItemsTheSame(oldItem: VariantItem, newItem: VariantItem) =
                    oldItem.entry == newItem.entry

                override fun areContentsTheSame(oldItem: VariantItem, newItem: VariantItem) =
                    oldItem == newItem

                // Rebind in place so that toggling a checkbox animates rather than cross-fading.
                override fun getChangePayload(oldItem: VariantItem, newItem: VariantItem) =
                    PAYLOAD_UPDATE
            }
    }
}
