package org.hellokittyvpn.android.ui

import androidx.compose.runtime.Composable
import org.hellokittyvpn.android.core.update.AppUpdateDto
import org.hellokittyvpn.android.core.update.UpdateState

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DistributionUpdateSettingsItem(onCheckForUpdates: () -> Unit) = Unit

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DistributionUpdateDialog(
    updateState: UpdateState,
    onDownload: (AppUpdateDto) -> Unit,
    onDismiss: () -> Unit,
) = Unit
