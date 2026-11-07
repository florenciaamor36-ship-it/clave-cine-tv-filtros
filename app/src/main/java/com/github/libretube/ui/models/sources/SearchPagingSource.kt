package com.github.libretube.ui.models.sources

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.ContentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchPagingSource(
    private val searchQuery: String,
    private val searchFilter: String,
    private val onSearchSuggestion: (Pair<String, Boolean>?) -> Unit
) : PagingSource<String, ContentItem>() {
    override fun getRefreshKey(state: PagingState<String, ContentItem>) = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, ContentItem> {
        return try {
            val parts = searchFilter.split("|", limit = 2)
            val type = parts.firstOrNull().orEmpty()
            val category = parts.getOrNull(1).orEmpty()
            val typeTerm = when (type) {
                "movies" -> "película completa"
                "series" -> "serie capítulo"
                "live" -> "canal TV en vivo transmisión"
                "music" -> "música"
                else -> ""
            }
            val query = listOf(searchQuery, typeTerm, category).filter { it.isNotBlank() }.joinToString(" ")

            val result = withContext(Dispatchers.IO) {
                params.key?.let {
                    MediaServiceRepository.instance.getSearchResultsNextPage(query, "videos", it)
                } ?: MediaServiceRepository.instance.getSearchResults(query, "videos")
                    .also {
                        if (it.suggestion.isNullOrEmpty()) onSearchSuggestion(null)
                        else onSearchSuggestion(it.suggestion to it.corrected)
                    }
            }
            val streams = result.items.filter { it.type == "stream" }
            val items = if (type == "live") {
                streams.filter { it.duration <= 0 && it.isShort != true }
            } else streams
            LoadResult.Page(items, null, result.nextpage)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
