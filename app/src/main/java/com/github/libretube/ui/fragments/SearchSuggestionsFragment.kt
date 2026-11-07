package com.github.libretube.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.google.android.material.chip.Chip
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentSearchSuggestionsBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.adapters.SearchSuggestionsAdapter
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.models.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SearchSuggestionsFragment : Fragment(R.layout.fragment_search_suggestions) {
    private var _binding: FragmentSearchSuggestionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchViewModel by activityViewModels()
    private val mainActivity get() = activity as MainActivity

    private val suggestionsAdapter = SearchSuggestionsAdapter(
        onRootClickListener = { suggestion ->
            mainActivity.setQuery(suggestion, true)
        },
        onArrowClickListener = { suggestion ->
            mainActivity.setQuery(suggestion, false)
        },
        onSearchHistoryItemDeleted = { historyItem ->
            lifecycleScope.launch(Dispatchers.IO) {
                DatabaseHolder.Database.searchHistoryDao().delete(historyItem)
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setQuery(arguments?.getString(IntentData.query))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSearchSuggestionsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        binding.suggestionsRecycler.adapter = suggestionsAdapter

        var baseQuery = arguments?.getString(IntentData.query).orEmpty().trim()
        var selectedType = ""
        val categories = linkedMapOf(
            "Películas" to listOf("Acción" to "acción", "Comedia" to "comedia", "Drama" to "drama", "Terror" to "terror", "Animación" to "animación", "Romance" to "romance", "Documentales" to "documental"),
            "Series" to listOf("Acción" to "serie acción", "Comedia" to "serie comedia", "Drama" to "serie drama", "Terror" to "serie terror", "Infantiles" to "serie infantil", "Documentales" to "serie documental"),
            "TV en vivo" to listOf("Argentina" to "TV en vivo Argentina", "Chile" to "TV en vivo Chile", "Ecuador" to "TV en vivo Ecuador", "Uruguay" to "TV en vivo Uruguay", "Paraguay" to "TV en vivo Paraguay", "Brasil" to "TV en vivo Brasil", "México" to "TV en vivo México", "Internacional" to "TV en vivo internacional"),
            "Música" to listOf("Pop" to "música pop", "Rock" to "música rock", "Cumbia" to "música cumbia", "Cuarteto" to "música cuarteto", "Reggaetón" to "música reggaetón", "Folclore" to "música folclore", "Clásica" to "música clásica", "Cristiana" to "música cristiana")
        )
        fun showSubcategories(type: String) {
            selectedType = type
            binding.categorySubcategoryGroup.removeAllViews()
            categories[type].orEmpty().forEach { (label, term) ->
                val chip = Chip(requireContext()).apply {
                    text = label
                    isCheckable = true
                    setOnClickListener {
                        baseQuery = mainActivity.currentSearchQuery().trim()
                        val query = listOf(baseQuery, term).filter { it.isNotBlank() }.joinToString(" ")
                        mainActivity.setQuery(query, true)
                    }
                }
                binding.categorySubcategoryGroup.addView(chip)
            }
        }
        categories.keys.forEach { type ->
            val chip = Chip(requireContext()).apply {
                text = type
                isCheckable = true
                setOnClickListener { showSubcategories(type) }
            }
            binding.categoryTypeGroup.addView(chip)
        }
        showSubcategories("Películas")

        setOnBackPressed {
            if (!mainActivity.clearSearchViewFocus()) findNavController().popBackStack()
        }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.searchSuggestions.collectLatest { result ->
                        suggestionsAdapter.submitSearchSuggestions(
                            result.historyList,
                            result.suggestionList
                        ) {
                            binding.suggestionsRecycler.scrollToPosition(0)
                        }
                    }
                }

                launch {
                    viewModel.shouldShowEmptyHistoryMessage.collectLatest {
                        toggleEmptyHistoryMessageVisibility(it)
                    }
                }
            }
        }
    }

    private fun toggleEmptyHistoryMessageVisibility(show: Boolean) {
        binding.historyEmpty.isVisible = show
        binding.suggestionsRecycler.isGone = show
    }

    override fun onDestroy() {
        super.onDestroy()

        _binding = null
    }
}
