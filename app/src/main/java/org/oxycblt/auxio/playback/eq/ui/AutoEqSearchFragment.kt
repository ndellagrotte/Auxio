/*
 * Copyright (c) 2026 Auxio Project
 * AutoEqSearchFragment.kt is part of Auxio.
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
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAutoeqSearchBinding
import org.oxycblt.auxio.databinding.ItemAutoeqModelBinding
import org.oxycblt.auxio.playback.eq.autoeq.AutoEqModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getPlural
import org.oxycblt.auxio.util.getSystemServiceCompat
import org.oxycblt.auxio.util.inflater
import org.oxycblt.auxio.util.navigateSafe

/** The first step of adding AutoEQ profiles: finding the user's headphone model. */
@AndroidEntryPoint
class AutoEqSearchFragment : ViewBindingFragment<FragmentAutoeqSearchBinding>() {
    private val searchModel: AutoEqSearchViewModel by viewModels()
    private val modelAdapter = ModelAdapter(::openModel)
    private var launchedKeyboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        launchedKeyboard = savedInstanceState?.getBoolean(KEY_LAUNCHED_KEYBOARD) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED_KEYBOARD, launchedKeyboard)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentAutoeqSearchBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentAutoeqSearchBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.autoeqSearchToolbar.setNavigationOnClickListener {
            hideKeyboard()
            findNavController().navigateUp()
        }
        binding.autoeqSearchRecycler.adapter = modelAdapter
        binding.autoeqSearchEditText.apply {
            addTextChangedListener { text -> searchModel.search(text?.toString()?.trim() ?: "") }
            if (!launchedKeyboard) {
                requestFocus()
                postDelayed(200) {
                    binding.root.context
                        .getSystemServiceCompat(InputMethodManager::class)
                        .showSoftInput(this, 0)
                }
                launchedKeyboard = true
            }
        }

        collectImmediately(searchModel.results, ::updateResults)
    }

    override fun onDestroyBinding(binding: FragmentAutoeqSearchBinding) {
        super.onDestroyBinding(binding)
        binding.autoeqSearchRecycler.adapter = null
    }

    private fun updateResults(results: List<AutoEqModel>?) {
        val binding = requireBinding()
        binding.autoeqSearchProgress.isVisible = results == null
        binding.autoeqSearchEmpty.isVisible = results?.isEmpty() == true
        binding.autoeqSearchRecycler.isInvisible = results.isNullOrEmpty()
        modelAdapter.submitList(results.orEmpty()) {
            // Keep the best match in view as the query changes.
            binding.autoeqSearchRecycler.scrollToPosition(0)
        }
    }

    private fun openModel(model: AutoEqModel) {
        hideKeyboard()
        findNavController()
            .navigateSafe(AutoEqSearchFragmentDirections.autoeqVariants(model.key, model.name))
    }

    private fun hideKeyboard() {
        requireContext()
            .getSystemServiceCompat(InputMethodManager::class)
            .hideSoftInputFromWindow(requireView().windowToken, 0)
    }

    private class ModelAdapter(private val onClick: (AutoEqModel) -> Unit) :
        ListAdapter<AutoEqModel, ModelViewHolder>(DIFF_CALLBACK) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ModelViewHolder(
                ItemAutoeqModelBinding.inflate(parent.context.inflater, parent, false),
            )

        override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
            holder.bind(getItem(position), onClick)
        }
    }

    private class ModelViewHolder(private val binding: ItemAutoeqModelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(model: AutoEqModel, onClick: (AutoEqModel) -> Unit) {
            binding.root.setOnClickListener { onClick(model) }
            binding.autoeqModelName.text = model.name
            binding.autoeqModelInfo.text =
                binding.root.context.getPlural(R.plurals.fmt_eq_variant_count, model.variantCount)
        }
    }

    private companion object {
        const val KEY_LAUNCHED_KEYBOARD = BuildConfig.APPLICATION_ID + ".key.launched_keyboard"

        val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AutoEqModel>() {
                override fun areItemsTheSame(oldItem: AutoEqModel, newItem: AutoEqModel) =
                    oldItem.key == newItem.key

                override fun areContentsTheSame(oldItem: AutoEqModel, newItem: AutoEqModel) =
                    oldItem == newItem
            }
    }
}
