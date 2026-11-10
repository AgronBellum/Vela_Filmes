package com.streamflixreborn.streamflix.fragments.tv_shows

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.BaseGridView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.adapters.CatalogMenuAdapter
import com.streamflixreborn.streamflix.adapters.CatalogFolderAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsTvBinding
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.CatalogFilterDialog
import com.streamflixreborn.streamflix.utils.CatalogViewModel
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class TvShowsTvFragment : Fragment() {
    private var _binding: FragmentTvShowsTvBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { TvShowsViewModel(database) }
    private val appAdapter = AppAdapter()
    private val folderAdapter = CatalogFolderAdapter(true, { viewModel.openFolder(it) }, { viewModel.requestFolderPreview(it) })
    // Keep ViewModel access lazy: fragments are still detached while fields are initialized.
    private val menuAdapter = CatalogMenuAdapter { filter -> viewModel.select(filter) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentTvShowsTvBinding.inflate(inflater, container, false); return binding.root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        binding.rvCategories.layoutManager = LinearLayoutManager(context); binding.rvCategories.adapter = menuAdapter
        binding.vgvTvShows.adapter = appAdapter.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        binding.vgvTvShows.setItemSpacing(resources.getDimension(R.dimen.tv_shows_spacing).toInt())
        binding.vgvTvShows.setWindowAlignment(BaseGridView.WINDOW_ALIGN_BOTH_EDGE)
        binding.vgvTvShows.setWindowAlignmentOffsetPercent(50f)
        binding.vgvTvShows.setItemAlignmentOffsetPercent(50f)
        binding.vgvTvShows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) viewModel.loadMore()
            }
        })
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (!viewModel.backToFolders()) { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed() }
        }
        binding.btnFilters.setOnClickListener { CatalogFilterDialog.show(requireContext(), false, viewModel.state.value.advanced, viewModel::applyAdvanced) }
        binding.btnClearFilters.setOnClickListener { viewModel.clearAdvanced() }
        binding.etSearch.addTextChangedListener(watcher { viewModel.search(it) })
        viewLifecycleOwner.lifecycleScope.launch { viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect(::render) }
        binding.rvCategories.post { binding.rvCategories.findViewHolderForAdapterPosition(1)?.itemView?.requestFocus() }
    }
    private fun render(state: CatalogViewModel.UiState) {
        menuAdapter.submitList(state.filters, state.selectedId)
        val heading = if (state.query.isBlank()) state.title else "Resultados para “${state.query}”"
        binding.tvTitle.text = state.totalResults?.let { "$heading  •  $it títulos" } ?: heading
        if (binding.etSearch.text.toString() != state.query) binding.etSearch.setText(state.query)
        binding.tvFilterSummary.text = state.advanced.label(); binding.btnClearFilters.visibility = if (state.advanced.isActive) View.VISIBLE else View.GONE
        binding.isLoading.root.visibility = if (state.loading) View.VISIBLE else View.GONE
        val empty = if (state.mode == CatalogViewModel.Mode.FOLDERS) state.folders.isEmpty() else state.items.isEmpty()
        binding.tvEmpty.visibility = if (!state.loading && empty && state.error == null) View.VISIBLE else View.GONE
        if (state.error != null) binding.isLoading.apply {
            root.visibility = View.VISIBLE; pbIsLoading.visibility = View.GONE; gIsLoadingRetry.visibility = View.VISIBLE
            btnIsLoadingRetry.setOnClickListener { viewModel.reload() }; btnIsLoadingClearCache.visibility = View.GONE
        }
        if (state.mode == CatalogViewModel.Mode.FOLDERS) {
            if (binding.vgvTvShows.adapter !== folderAdapter) binding.vgvTvShows.adapter = folderAdapter
            folderAdapter.submitList(state.folders)
        } else {
            if (binding.vgvTvShows.adapter !== appAdapter) binding.vgvTvShows.adapter = appAdapter
            val shows = state.items.filterIsInstance<TvShow>().onEach { it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM }
            appAdapter.submitList(shows)
        }
    }
    private fun watcher(change: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = change(s?.toString().orEmpty())
        override fun afterTextChanged(s: Editable?) = Unit
    }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
