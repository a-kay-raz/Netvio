package com.netvio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.netvio.core.model.CatalogRow
import com.netvio.core.model.MediaItem
import com.netvio.ui.theme.rememberWindowDimensions

/**
 * Modern Apple TV / Netflix-style Content Row with scale-aware poster gaps and rail rhythm.
 */
@Composable
fun ContentRow(
    row: CatalogRow,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    sectionLabelStyle: com.netvio.data.preferences.SectionLabelStyle = com.netvio.data.preferences.SectionLabelStyle.AccentBar,
    onItemLongClick: ((MediaItem) -> Unit)? = null,
    onSeeAllClick: (() -> Unit)? = null
) {
    if (row.items.isEmpty()) return

    val windowDimensions = rememberWindowDimensions()
    val scaleMode = LocalCardScaleMode.current
    val horizontalGap = CardDimensions.posterGap(scaleMode)
    val bottomPadding = CardDimensions.railBottomSpacing(scaleMode)

    val rowListState = androidx.compose.runtime.saveable.rememberSaveable(
        row.title,
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) {
        androidx.compose.foundation.lazy.LazyListState()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding)
    ) {
        SectionHeader(
            title = row.title,
            style = sectionLabelStyle,
            subtitle = row.subtitle,
            eyebrow = row.eyebrow,
            onSeeAll = onSeeAllClick
        )

        LazyRow(
            state = rowListState,
            contentPadding = PaddingValues(horizontal = windowDimensions.horizontalMargin),
            horizontalArrangement = Arrangement.spacedBy(horizontalGap),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(
                items = row.items,
                key = { _, item -> "${item.id}_${item.provider ?: ""}" },
                contentType = { _, _ -> "media_card" }
            ) { _, item ->
                MediaCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongClick = onItemLongClick?.let { { it(item) } }
                )
            }
        }
    }
}
