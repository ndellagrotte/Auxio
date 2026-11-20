/*
 * Copyright (c) 2026 Auxio Project
 * EqualizerFragment.kt is part of Auxio.
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
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentEqualizerBinding
import org.oxycblt.auxio.playback.eq.EqProfile
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.showToast
import timber.log.Timber as L

/**
 * Lists saved parametric EQ profiles, letting the user pick which one is applied to playback, and
 * add new ones from the bundled AutoEQ database or from Equalizer APO files.
 */
@AndroidEntryPoint
class EqualizerFragment :
    ViewBindingFragment<FragmentEqualizerBinding>(),
    Toolbar.OnMenuItemClickListener,
    EqProfileAdapter.Listener {
    private val eqModel: EqualizerViewModel by viewModels()
    private val profileAdapter = EqProfileAdapter(this)
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                eqModel.importFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        returnTransition = MaterialFadeThrough()
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentEqualizerBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentEqualizerBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.eqToolbar.apply {
            setNavigationOnClickListener { findNavController().navigateUp() }
            setOnMenuItemClickListener(this@EqualizerFragment)
        }
        binding.eqRecycler.adapter = profileAdapter

        collectImmediately(eqModel.profiles, eqModel.activeProfile, profileAdapter::submit)
        collectImmediately(eqModel.importResult.flow, ::handleImportResult)
    }

    override fun onDestroyBinding(binding: FragmentEqualizerBinding) {
        super.onDestroyBinding(binding)
        binding.eqToolbar.setOnMenuItemClickListener(null)
        binding.eqRecycler.adapter = null
    }

    override fun onMenuItemClick(item: MenuItem) =
        when (item.itemId) {
            R.id.action_eq_autoeq -> {
                onOpenAutoEq()
                true
            }
            R.id.action_eq_import -> {
                onImportFile()
                true
            }
            else -> false
        }

    override fun onSelect(profile: EqProfile?) = eqModel.select(profile)

    override fun onDelete(profile: EqProfile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_delete)
            .setMessage(getString(R.string.fmt_deletion_info, profile.name))
            .setPositiveButton(R.string.lbl_delete) { _, _ -> eqModel.delete(profile) }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    override fun onOpenAutoEq() {
        findNavController().navigateSafe(EqualizerFragmentDirections.autoeqSearch())
    }

    override fun onImportFile() {
        L.d("Launching EQ file picker")
        importLauncher.launch("text/plain")
    }

    private fun handleImportResult(result: ImportResult?) {
        when (result) {
            is ImportResult.Success -> requireContext().showToast(R.string.lng_eq_profile_imported)
            is ImportResult.Invalid ->
                showImportError(requireContext().describeProblem(result.problem))
            is ImportResult.ReadFailed -> showImportError(getString(R.string.err_eq_read_failed))
            null -> return
        }
        eqModel.importResult.consume()
    }

    private fun showImportError(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.err_eq_import_failed)
            .setMessage(message)
            .setPositiveButton(R.string.lbl_ok, null)
            .show()
    }
}
