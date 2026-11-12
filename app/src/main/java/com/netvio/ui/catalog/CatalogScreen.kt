package com.netvio.ui.catalog

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netvio.core.model.MediaItem
import com.netvio.core.model.MediaType
import com.netvio.core.repository.ProviderRepository
import com.netvio.ui.components.NuvioLoadingIndicator
import com.netvio.ui.components.PosterCard
import com.netvio.ui.theme.AppTypography
import com.netvio.ui.theme.Dimens
import com.netvio.ui.theme.TextPrimary
import com.netvio.ui.theme.TextSecondary
import com.netvio.ui.theme.TextTertiary
import com.netvio.ui.theme.WindowWidthClass
import com.netvio.ui.theme.rememberWindowDimensions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Full catalog screen — the Nuvio-style "View all" destination. Home shows
 * only a preview strip of each catalog; this screen shows the whole catalog
 * in a grid and pages in more items as the user scrolls (TMDB feeds by page,
 * Stremio addon catalogs by skip when the addon declares it).
 */
data class CatalogUiState(
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val loadFailed: Boolean = false
)

class CatalogViewModel(
    application: Application,
    private val repository: ProviderRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, ProviderRepository.Default)

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState

    private var catalogKey: String? = null
    private var currentPage = 0

    fun load(key: String) {
        if (catalogKey == key && (_uiState.value.items.isNotEmpty() || _uiState.value.isLoading)) return
        catalogKey = key
        currentPage = 0
        _uiState.value = CatalogUiState(isLoading = true)
        loadPage(1)
    }

    fun retry() {
        val key = catalogKey ?: return
        currentPage = 0
        _uiState.value = CatalogUiState(isLoading = true)
        loadPage(1)
    }

    fun loadNextPage() {
        val state = _uiState.value
        val key = catalogKey ?: return
        if (!state.hasMore || state.isLoading || state.isLoadingMore) return
        _uiState.value = state.copy(isLoadingMore = true)
        loadPage(currentPage + 1)
    }

    private fun loadPage(page: Int) {
        val key = catalogKey ?: return
        viewModelScope.launch {
            val result = try {
                repository.getCatalogPage(key, page)
            } catch (_: Exception) {
                null
            }
            val state = _uiState.value
            if (result == null) {
                _uiState.value = if (page == 1) {
                    state.copy(isLoading = false, isLoadingMore = false, loadFailed = true)
                } else {
                    state.copy(isLoadingMore = false, hasMore = false)
                }
                return@launch
            }
            val existing = if (page == 1) emptyList() else state.items
            val seen = existing.map { it.id }.toHashSet()
            val fresh = result.items.filter { seen.add(it.id) }
            val merged = existing + fresh
            currentPage = page
            _uiState.value = state.copy(
                items = merged,
                isLoading = false,
                isLoadingMore = false,
                // An empty page always ends pagination, even when the
                // previous page was full.
                hasMore = result.hasMore && fresh.isNotEmpty(),
                loadFailed = false
            )
        }
    }
}

@Composable
fun CatalogScreen(
    catalogKey: String,
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val windowDimensions = rememberWindowDimensions()
    val gridColumns = when (windowDimensions.widthClass) {
        WindowWidthClass.Expanded -> 6
        WindowWidthClass.Medium -> 5
        WindowWidthClass.Compact -> 3
    }
    val gridState = rememberLazyGridState()

    LaunchedEffect(catalogKey) {
        viewModel.load(catalogKey)
    }

    // Infinite scroll: load the next page when the user nears the end.
    LaunchedEffect(gridState, uiState.items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                val total = uiState.items.size
                if (lastVisible != null && total > 0 && lastVisible >= total - 6) {
                    viewModel.loadNextPage()
                }
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header: back + title (+ subtitle).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 4.dp,
                    end = windowDimensions.horizontalMargin,
                    top = 8.dp,
                    bottom = 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Same ported Nuvio loading screen as the details page.
                    NuvioLoadingIndicator()
                }
            }
            uiState.loadFailed -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Couldn't load catalog",
                            style = AppTypography.bodyLarge,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.retry() }) {
                            Text(text = "Retry", color = TextPrimary)
                        }
                    }
                }
            }
            uiState.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No items found",
                            style = AppTypography.bodyLarge,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.retry() }) {
                            Text(text = "Retry", color = TextPrimary)
                        }
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(
                        start = windowDimensions.horizontalMargin,
                        end = windowDimensions.horizontalMargin,
                        top = 8.dp,
                        bottom = 32.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.posterGap),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spacing12),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        PosterCard(
                            id = item.id,
                            posterUrl = item.posterUrl ?: item.backdropUrl,
                            title = item.title,
                            year = item.year,
                            type = when (item.type) {
                                MediaType.MOVIE -> "Movie"
                                MediaType.TV_SERIES -> "Series"
                                MediaType.ANIME -> "Anime"
                                MediaType.LIVE_TV -> "Live TV"
                            },
                            onClick = { onMediaClick(item) },
                            width = Dp.Unspecified
                        )
                    }
                    if (uiState.hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.dp,
                                    color = TextTertiary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
