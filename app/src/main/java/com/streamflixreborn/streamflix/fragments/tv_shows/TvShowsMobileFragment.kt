package com.streamflixreborn.streamflix.fragments.tv_shows

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.adapters.CatalogMenuAdapter
import com.streamflixreborn.streamflix.adapters.CatalogFolderAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentTvShowsMobileBinding
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.CatalogFilterDialog
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.CatalogViewModel
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class TvShowsMobileFragment : Fragment() {
    private var _binding: FragmentTvShowsMobileBinding? = null
    private val binding get() = _binding!!
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { TvShowsViewModel(database) }
    private val appAdapter = AppAdapter()
    private val folderAdapter = CatalogFolderAdapter(false, { viewModel.openFolder(it) }, { viewModel.requestFolderPreview(it) })
    private val menuAdapter = CatalogMenuAdapter { filter -> viewModel.select(filter); binding.drawerLayout.closeDrawer(GravityCompat.START) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentTvShowsMobileBinding.inflate(inflater, container, false); return binding.root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        binding.rvCategories.layoutManager = LinearLayoutManager(context); binding.rvCategories.adapter = menuAdapter
        binding.rvTvShows.layoutManager = GridLayoutManager(context, 3)
        binding.rvTvShows.adapter = appAdapter.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        binding.rvTvShows.addItemDecoration(SpacingItemDecoration(10.dp(requireContext())))
        binding.rvTvShows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) viewModel.loadMore()
            }
        })
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (!viewModel.backToFolders()) { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed() }
        }
        binding.btnCategories.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.btnFilters.setOnClickListener { CatalogFilterDialog.show(requireContext(), false, viewModel.state.value.advanced, viewModel::applyAdvanced) }
        binding.btnClearFilters.setOnClickListener { viewModel.clearAdvanced() }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { viewModel.search(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        viewLifecycleOwner.lifecycleScope.launch { viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect(::render) }
    }
    private fun render(state: com.streamflixreborn.streamflix.utils.CatalogViewModel.UiState) {
        menuAdapter.submitList(state.filters, state.selectedId)
        val heading = if (state.query.isBlank()) state.title else "Resultados para “${state.query}”"
        binding.tvTitle.text = state.totalResults?.let { "$heading  •  $it títulos" } ?: heading
        if (binding.etSearch.text.toString() != state.query) binding.etSearch.setText(state.query)
        binding.tvFilterSummary.text = state.advanced.label()
        binding.btnClearFilters.visibility = if (state.advanced.isActive) View.VISIBLE else View.GONE
        binding.isLoading.root.visibility = if (state.loading) View.VISIBLE else View.GONE
        val empty = if (state.mode == CatalogViewModel.Mode.FOLDERS) state.folders.isEmpty() else state.items.isEmpty()
        binding.tvEmpty.visibility = if (!state.loading && empty && state.error == null) View.VISIBLE else View.GONE
        if (state.error != null) binding.isLoading.apply {
            root.visibility = View.VISIBLE; pbIsLoading.visibility = View.GONE; gIsLoadingRetry.visibility = View.VISIBLE
            btnIsLoadingRetry.setOnClickListener { viewModel.reload() }; btnIsLoadingClearCache.visibility = View.GONE
        }
        if (state.mode == CatalogViewModel.Mode.FOLDERS) {
            if (binding.rvTvShows.adapter !== folderAdapter) {
                binding.rvTvShows.layoutManager = GridLayoutManager(context, 2)
                binding.rvTvShows.adapter = folderAdapter
            }
            folderAdapter.submitList(state.folders)
        } else {
            if (binding.rvTvShows.adapter !== appAdapter) {
                binding.rvTvShows.layoutManager = GridLayoutManager(context, 3)
                binding.rvTvShows.adapter = appAdapter
            }
            val shows = state.items.filterIsInstance<TvShow>().onEach { it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM }
            appAdapter.submitList(shows)
        }
    }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
