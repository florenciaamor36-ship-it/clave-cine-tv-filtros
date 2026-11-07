package com.github.libretube.ui.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import com.google.android.material.chip.Chip
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.FragmentSearchResultBinding
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.adapters.SearchResultsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.models.SearchResultViewModel
import com.github.libretube.util.TextUtils.toTimeInSeconds
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SearchResultFragment : DynamicLayoutManagerFragment(R.layout.fragment_search_result) {
    private var _binding: FragmentSearchResultBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<SearchResultFragmentArgs>()
    private val viewModel by viewModels<SearchResultViewModel>()

    private val mainActivity get() = activity as MainActivity
    private var recyclerViewState: Parcelable? = null

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.searchRecycler?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSearchResultBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // fixes a bug that the search query will stay the old one when searching for multiple
        // different queries in a row and navigating to the previous ones through back presses
        mainActivity.setQuerySilent(args.query)

        // Main sections and contextual subcategories.
        var selectedType = "movies"
        var selectedCategory = ""
        fun refreshSearchFilter() {
            viewModel.setFilter(if (selectedCategory.isEmpty()) selectedType else "$selectedType|$selectedCategory")
        }
        fun showCategories(type: String) {
            selectedType = type
            selectedCategory = ""
            val options = when (type) {
                "movies" -> listOf("Todos" to "", "Acción" to "acción", "Comedia" to "comedia", "Drama" to "drama", "Terror" to "terror", "Animación" to "animación", "Romance" to "romance", "Documentales" to "documental")
                "series" -> listOf("Todas" to "", "Acción" to "acción", "Comedia" to "comedia", "Drama" to "drama", "Terror" to "terror", "Infantiles" to "infantil", "Documentales" to "documental")
                "live" -> listOf("Todos los países" to "", "Argentina" to "Argentina", "Chile" to "Chile", "Ecuador" to "Ecuador", "Uruguay" to "Uruguay", "Paraguay" to "Paraguay", "Brasil" to "Brasil", "México" to "México", "Internacional" to "internacional")
                else -> listOf("Toda la música" to "", "Pop" to "pop", "Rock" to "rock", "Cumbia" to "cumbia", "Cuarteto" to "cuarteto", "Reggaetón" to "reggaetón", "Folclore" to "folclore", "Clásica" to "música clásica", "Cristiana" to "música cristiana")
            }
            binding.categoryChipGroup.removeAllViews()
            options.forEachIndexed { index, (label, value) ->
                val chip = Chip(requireContext()).apply {
                    text = label
                    isCheckable = true
                    isChecked = index == 0
                    setOnClickListener {
                        selectedCategory = value
                        refreshSearchFilter()
                    }
                }
                binding.categoryChipGroup.addView(chip)
            }
            refreshSearchFilter()
        }
        binding.filterChipGroup.setOnCheckedStateChangeListener { _, _ ->
            val type = when (binding.filterChipGroup.checkedChipId) {
                R.id.chip_movies -> "movies"
                R.id.chip_series -> "series"
                R.id.chip_live -> "live"
                R.id.chip_music -> "music"
                else -> "movies"
            }
            showCategories(type)
        }
        showCategories("movies")

        val timeStamp = args.query.toHttpUrlOrNull()?.queryParameter("t")?.toTimeInSeconds()
        val searchResultsAdapter = SearchResultsAdapter(timeStamp ?: 0)
        binding.searchRecycler.adapter = searchResultsAdapter

        binding.searchRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchResultsAdapter.loadStateFlow.collect {
                    val isLoading = it.source.refresh is LoadState.Loading
                    binding.progress.isVisible = isLoading
                    binding.searchResultsLayout.isGone = isLoading
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchResultsFlow.collectLatest {
                    searchResultsAdapter.submitData(it)
                }
            }
        }

        viewModel.searchSuggestion.observe(viewLifecycleOwner) { suggestion ->
            binding.searchSuggestionContainer.isVisible = suggestion != null
            binding.searchSuggestionContainer.setOnClickListener(null)
            if (suggestion == null) return@observe

            val (suggestion, corrected) = suggestion
            binding.searchSuggestion.text = suggestion
            binding.searchSuggestionLabel.text = if (corrected) {
                getString(R.string.showing_results_for)
            } else {
                binding.searchSuggestionContainer.setOnClickListener {
                    mainActivity.setQuery(suggestion, true)
                }
                getString(R.string.did_you_mean)
            }
        }

        setOnBackPressed {
            findNavController().popBackStack(R.id.searchFragment, true) ||
                    findNavController().popBackStack()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.searchRecycler.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
