package com.netvio.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.netvio.ui.settings.SettingsRootScreen

@Composable
fun ProfileScreen(
    onNavigateToPage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsRootScreen(
        onNavigateToPage = onNavigateToPage,
        modifier = modifier
    )
}
