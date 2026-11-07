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
            val query = when (searchFilter) {
                "movies" -> "$searchQuery película completa"
                "series" -> "$searchQuery serie capítulo"
                "live" -> "$searchQuery en vivo"
                else -> searchQuery
            }
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
            val items = if (searchFilter == "live") {
                streams.filter { it.duration <= 0 && it.isShort != true }
            } else streams
            LoadResult.Page(items, null, result.nextpage)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
