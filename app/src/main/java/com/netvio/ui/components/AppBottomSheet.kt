package com.netvio.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.netvio.ui.theme.ApplyWindowBlurEffect
import com.netvio.ui.theme.Dimens
import com.netvio.ui.theme.LocalAmoledMode
import com.netvio.ui.theme.LocalUiBlurEnabled
import com.netvio.ui.theme.NetvioGlassTokens
import com.netvio.ui.theme.OverlayScrim
import com.netvio.ui.theme.ShapeL
import com.netvio.ui.theme.TextSecondary

/**
 * Standard Modal Bottom Sheet wrapping Material3 ModalBottomSheet with frosted glass blur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onDismissRequest)
    val isAmoled = LocalAmoledMode.current
    val uiBlurEnabled = LocalUiBlurEnabled.current
    val containerColor = NetvioGlassTokens.sheetWindowFill(isAmoled = isAmoled, blurEnabled = uiBlurEnabled)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = Dimens.maxSheetWidth,
        shape = ShapeL,
        containerColor = containerColor,
        scrimColor = OverlayScrim,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = TextSecondary.copy(alpha = 0.4f)
            )
        },
        content = {
            ApplyWindowBlurEffect(enabled = uiBlurEnabled)
            content()
        }
    )
}
