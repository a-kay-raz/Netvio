package com.netvio.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netvio.ui.components.NetvioGlassDialog
import com.netvio.ui.settings.components.OpenSettingsSectionHeader
import com.netvio.ui.settings.components.SettingsResetPill
import com.netvio.ui.settings.components.SettingsScaffold
import com.netvio.ui.theme.AppFontFamily

private enum class CatalogManagerTab {
    ON_HOME,
    NOT_ON_HOME
}

/**
 * Catalog Manager (Settings -> Reorder Catalogs), Nuvio-style.
 *
 * On Home: the catalog rows shown on Home, in the order Home draws them.
 * Move them with the arrow buttons, hide a row with the eye button, or
 * rename it with the pencil. Hiding a row never disables or uninstalls
 * its addon, and hidden catalogs stay searchable.
 * Not on Home: the remaining catalogs, grouped by addon.
 */
@Composable
fun CatalogManagerPage(
    onBack: () -> Unit,
    viewModel: CatalogManagerViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val onHomeItems by viewModel.onHomeItems.collectAsState()
    val notOnHomeItems by viewModel.notOnHomeItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedTab by remember { mutableStateOf(CatalogManagerTab.ON_HOME) }
    var searchQuery by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<CatalogManagerItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showResetDialog by remember { mutableStateOf(false) }

    val query = searchQuery.trim()
    val filteredOnHome = remember(onHomeItems, query) {
        if (query.isBlank()) onHomeItems else onHomeItems.filter {
            it.displayTitle.contains(query, ignoreCase = true) ||
                it.definition.addonName.contains(query, ignoreCase = true) ||
                it.definition.catalogName.contains(query, ignoreCase = true)
        }
    }
    val filteredNotOnHome = remember(notOnHomeItems, query) {
        if (query.isBlank()) notOnHomeItems else notOnHomeItems.filter {
            it.displayTitle.contains(query, ignoreCase = true) ||
                it.definition.addonName.contains(query, ignoreCase = true) ||
                it.definition.catalogName.contains(query, ignoreCase = true)
        }
    }

    SettingsScaffold(
        title = "Reorder Catalogs",
        onBack = onBack,
        modifier = modifier
    ) { hazeState ->

        Text(
            text = "Choose which catalog rows appear on Home, put them in order, " +
                "or rename them. Hidden rows stay searchable and their addons stay installed.",
            style = TextStyle(
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color.White.copy(alpha = 0.6f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        // Find & Edit search field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Find a catalog",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // On Home / Not on Home tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CatalogManagerTabButton(
                text = "On Home (${onHomeItems.size})",
                selected = selectedTab == CatalogManagerTab.ON_HOME,
                onClick = { selectedTab = CatalogManagerTab.ON_HOME },
                modifier = Modifier.weight(1f)
            )
            CatalogManagerTabButton(
                text = "Not on Home (${notOnHomeItems.size})",
                selected = selectedTab == CatalogManagerTab.NOT_ON_HOME,
                onClick = { selectedTab = CatalogManagerTab.NOT_ON_HOME },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading && onHomeItems.isEmpty() && notOnHomeItems.isEmpty()) {
            Text(
                text = "Loading catalogs...",
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.6f)
                ),
                modifier = Modifier.padding(vertical = 32.dp)
            )
        } else if (selectedTab == CatalogManagerTab.ON_HOME) {
            // Bulk actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CatalogManagerActionButton(
                    text = "Hide all from Home",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.hideAllFromHome()
                    },
                    modifier = Modifier.weight(1f)
                )
                CatalogManagerActionButton(
                    text = "Refresh everything",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.refreshCatalogs()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredOnHome.isEmpty()) {
                CatalogManagerEmptyState(
                    text = if (query.isBlank()) {
                        "No catalogs on Home yet. Add some from Not on Home."
                    } else {
                        "No catalogs match \"$query\"."
                    }
                )
            } else {
                filteredOnHome.forEachIndexed { index, item ->
                    CatalogManagerRow(
                        item = item,
                        showMoveUp = index > 0 && query.isBlank(),
                        showMoveDown = index < filteredOnHome.size - 1 && query.isBlank(),
                        onMoveUp = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.moveCatalog(item.definition.key, -1)
                        },
                        onMoveDown = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.moveCatalog(item.definition.key, 1)
                        },
                        onRename = {
                            renameTarget = item
                            renameText = item.displayTitle
                        },
                        onToggleVisibility = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.setCatalogOnHome(item.definition.key, false)
                        }
                    )
                }
            }
        } else {
            CatalogManagerActionButton(
                text = "Show all on Home",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.showAllOnHome()
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredNotOnHome.isEmpty()) {
                CatalogManagerEmptyState(
                    text = if (query.isBlank()) {
                        "All catalogs are on Home."
                    } else {
                        "No catalogs match \"$query\"."
                    }
                )
            } else {
                val grouped = filteredNotOnHome.groupBy { it.definition.addonName }
                grouped.keys.sorted().forEach { addonName ->
                    OpenSettingsSectionHeader(title = addonName)
                    grouped.getValue(addonName).forEach { item ->
                        CatalogManagerRow(
                            item = item,
                            showMoveUp = false,
                            showMoveDown = false,
                            onMoveUp = {},
                            onMoveDown = {},
                            onRename = {
                                renameTarget = item
                                renameText = item.displayTitle
                            },
                            onToggleVisibility = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setCatalogOnHome(item.definition.key, true)
                            },
                            onAdd = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setCatalogOnHome(item.definition.key, true)
                            }
                        )
                    }
                }
            }
        }

        SettingsResetPill(
            text = "Reset to defaults",
            onClick = { showResetDialog = true },
            hazeState = hazeState
        )
    }

    if (showResetDialog) {
        NetvioGlassDialog(
            title = "Reset to defaults?",
            message = "This clears every saved catalog order, visibility and rename, " +
                "and restores the default Home rows.",
            confirmText = "Reset",
            onConfirm = {
                showResetDialog = false
                viewModel.resetToDefaults()
            },
            onDismiss = { showResetDialog = false }
        )
    }

    val target = renameTarget
    if (target != null) {
        NetvioGlassDialog(
            title = "Rename catalog",
            message = "This name is shown for the row on Home.",
            confirmText = "Save",
            itemPreview = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.sp,
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            onConfirm = {
                viewModel.renameCatalog(target.definition.key, renameText)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
}

@Composable
private fun CatalogManagerTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = AppFontFamily,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 13.sp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.6f)
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CatalogManagerActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = Color.White
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CatalogManagerEmptyState(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = AppFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Color.White.copy(alpha = 0.6f)
        ),
        modifier = Modifier.padding(vertical = 32.dp)
    )
}

@Composable
private fun CatalogManagerRow(
    item: CatalogManagerItem,
    showMoveUp: Boolean,
    showMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onToggleVisibility: () -> Unit,
    onAdd: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayTitle,
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = Color.White
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.definition.addonName} • ${item.definition.type}",
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.55f)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onAdd != null) {
            CatalogManagerActionButton(
                text = "Add",
                onClick = onAdd,
                modifier = Modifier.padding(end = 4.dp)
            )
        } else {
            IconButton(
                onClick = onMoveUp,
                enabled = showMoveUp,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "Move up",
                    tint = Color.White.copy(alpha = if (showMoveUp) 0.9f else 0.25f),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = showMoveDown,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Move down",
                    tint = Color.White.copy(alpha = if (showMoveDown) 0.9f else 0.25f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Rename catalog",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
        }
        if (onAdd == null) {
            IconButton(onClick = onToggleVisibility, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (item.onHome) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (item.onHome) "Hide from Home" else "Show on Home",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
