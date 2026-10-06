package org.hellokittyvpn.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.hellokittyvpn.android.BuildConfig
import org.hellokittyvpn.android.R
import org.hellokittyvpn.android.core.logger.LogEntry
import org.hellokittyvpn.android.core.logger.AppLogger
import org.hellokittyvpn.android.core.network.DiagnosticReport
import org.hellokittyvpn.android.core.network.WhitelistMode
import org.hellokittyvpn.android.data.AntiDpiPreset
import org.hellokittyvpn.android.data.DailyTraffic
import org.hellokittyvpn.android.data.DnsProvider
import org.hellokittyvpn.android.data.RoutingPreset
import org.hellokittyvpn.android.data.SplitTunnelMode
import org.hellokittyvpn.android.data.SplitTunnelPackageList
import org.hellokittyvpn.android.data.AppIcon
import org.hellokittyvpn.android.data.ThemeMode
import org.hellokittyvpn.android.ui.theme.*
import org.hellokittyvpn.android.vpn.PreparedTunnelProfile
import org.hellokittyvpn.android.vpn.EffectiveRoutingProfile
import org.hellokittyvpn.android.vpn.TunnelServer
import org.hellokittyvpn.android.vpn.TunnelServerCategory
import org.hellokittyvpn.android.vpn.VpnConnectionState
import org.hellokittyvpn.android.vpn.VpnFailure
import org.hellokittyvpn.android.vpn.VpnSnapshot
import org.hellokittyvpn.android.vpn.countryFlag
import org.hellokittyvpn.android.vpn.effectiveCategory
import org.hellokittyvpn.android.vpn.TunnelEngineKind
import org.hellokittyvpn.android.vpn.isMobileServer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter


@Composable
private fun TvNavigationRail(
    selected: AppTab,
    onSelected: (AppTab) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        initialFocus.requestFocus()
    }
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .padding(end = 12.dp),
        shape = RoundedCornerShape(KittyDimensions.CardRadius),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline,
        ),
        shadowElevation = 4.dp,
    ) {
        NavigationRail(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            header = { Spacer(Modifier.height(12.dp)) },
        ) {
            NavigationDestination.entries.forEach { destination ->
                val isSelected = selected == destination.tab
                NavigationRailItem(
                    selected = isSelected,
                    onClick = { onSelected(destination.tab) },
                    modifier = if (isSelected) {
                        Modifier.focusRequester(initialFocus)
                    } else {
                        Modifier
                    },
                    icon = {
                        Icon(
                            painter = painterResource(destination.icon),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(destination.label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = KittyBlue,
                        selectedTextColor = KittyBlue,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = KittyBlue.copy(alpha = 0.12f),
                    ),
                )
            }
        }
    }
}
@Composable
private fun AppNavigationBar(
    selected: AppTab,
    onSelected: (AppTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(KittyDimensions.CardRadius),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavigationDestination.entries.forEach { destination ->
                    val isSelected = selected == destination.tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                onClick = { onSelected(destination.tab) },
                            )
                            .padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val color = if (isSelected) KittyBlue else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(
                            painter = painterResource(destination.icon),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = color,
                        )
                        Text(
                            text = stringResource(destination.label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            textAlign = TextAlign.Center,
                            color = color,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun HomeScreen(
    modifier: Modifier,
    state: AppUiState,
    onConnect: () -> Unit,
    onOpenPauseVpn: () -> Unit,
    onResumeVpn: () -> Unit,
    onProfile: () -> Unit,
    onServers: () -> Unit,
    onOpenRoutingPreset: () -> Unit,
    onOpenAntiDpi: () -> Unit,
) {
    val selectedServer = state.profile?.servers?.firstOrNull {
        it.id == displayedServerId(state)
    }
    val isConnected = state.vpn.state == VpnConnectionState.CONNECTED
    val isMobileServer = (isConnected && state.vpn.effectiveRoutingProfile == EffectiveRoutingProfile.LTE) ||
        selectedServer?.isMobileServer() == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandTitle(isConnected = isConnected)
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onProfile,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_profile),
                        contentDescription = stringResource(R.string.profile_title),
                        modifier = Modifier.size(24.dp),
                        tint = KittyBlue,
                    )
                }
            }
        }
        if (state.whitelistMode == WhitelistMode.ACTIVE) {
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    KittyBlue.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = KittyBlue,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.whitelist_active_banner,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        // Quick feature badges row
        Spacer(Modifier.height(18.dp))
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                onClick = onOpenRoutingPreset,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isMobileServer && state.routingPreset == RoutingPreset.BYPASS_RU) {
                            "LTE"
                        } else {
                            when (state.routingPreset) {
                                RoutingPreset.GLOBAL -> "Global"
                                RoutingPreset.BYPASS_RU -> "Обход РФ"
                                RoutingPreset.BLOCKED_ONLY -> "Anti-Block"
                            }
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (state.useDoh) {
                Surface(
                    onClick = onProfile,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_servers),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = buildAnnotatedString {
                                append("DoH: ")
                                withStyle(SpanStyle(color = if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A))) { append("ON") }
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (state.antiDpiEnabled) {
                Surface(
                    onClick = onOpenAntiDpi,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_anti_dpi),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = KittyBlue,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (state.antiDpiPreset) {
                                AntiDpiPreset.TLS_HELLO -> "Anti-DPI: TLS Hello"
                                AntiDpiPreset.MICRO -> "Anti-DPI: Micro"
                                AntiDpiPreset.BALANCED -> "Anti-DPI: Balanced"
                                AntiDpiPreset.DEEP -> "Anti-DPI: Deep"
                                AntiDpiPreset.CUSTOM -> "Anti-DPI: Custom"
                                AntiDpiPreset.OFF -> "Anti-DPI"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }



        // Central Power Button with Glowing Ring and Status
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PowerButton(
                vpn = state.vpn,
                busy = state.refreshing,
                onClick = onConnect,
            )
        }

        // Action Button (Pause / Resume)
        if (state.vpn.state == VpnConnectionState.PAUSED) {
            Spacer(Modifier.height(22.dp))
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val formatted = String.format(
                        java.util.Locale.US,
                        "%02d:%02d",
                        state.vpn.pausedRemainingSeconds / 60,
                        state.vpn.pausedRemainingSeconds % 60,
                    )
                    Text(
                        text = stringResource(R.string.status_paused, formatted),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pause_vpn_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onResumeVpn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = KittyDimensions.ButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) KittyGreen else Color(0xFF16A34A),
                        ),
                    ) {
                        Text(
                            stringResource(R.string.resume_vpn_btn),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        } else if (state.vpn.state == VpnConnectionState.CONNECTED) {
            Spacer(Modifier.height(22.dp))
            Surface(
                onClick = onOpenPauseVpn,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = KittyDimensions.ButtonHeight),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = null,
                        tint = KittyBlue,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.pause_vpn_btn),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Server Summary and Traffic Card
        Spacer(Modifier.height(24.dp))
        ServerSummaryCard(
            server = selectedServer,
            vpn = state.vpn,
            pingMs = state.pingMs,
            automaticServer = state.automaticServer,
            onClick = onServers,
        )

        state.vpn.failure?.let { failure ->
            Spacer(Modifier.height(14.dp))
            Text(
                text = failure.localized(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            state.vpn.failureDetail?.let { detail ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.failure_detail_label, detail),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
@Composable
private fun BrandTitle(isConnected: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Hello Kitty",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            letterSpacing = (-0.5).sp,
        )
        Text(
            text = " VPN",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            letterSpacing = (-0.5).sp,
        )
    }
}
@Composable
private fun SignalBarsIndicator(
    pingMs: Long?,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bars, color) = when {
        pingMs == null -> 0 to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        pingMs < 70 -> 4 to (if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A))
        pingMs < 140 -> 3 to (if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A))
        pingMs < 250 -> 2 to (if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706))
        else -> 1 to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val heights = listOf(6.dp, 10.dp, 14.dp, 18.dp)
        heights.forEachIndexed { index, height ->
            val isBarActive = index < bars
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(height)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (isBarActive) color
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
            )
        }
    }
}
@Composable
private fun PowerButton(
    vpn: VpnSnapshot,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val isConnected = vpn.state == VpnConnectionState.CONNECTED
    val isTransitioning = busy || vpn.state in setOf(
        VpnConnectionState.CONNECTING,
        VpnConnectionState.RECONNECTING,
        VpnConnectionState.STOPPING,
    )
    val isPaused = vpn.state == VpnConnectionState.PAUSED
    val isError = vpn.state in setOf(VpnConnectionState.ERROR, VpnConnectionState.LOCKDOWN)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val localizedState = vpn.state.localized()
    val primaryAccent = when {
        isConnected -> if (isDark) KittyBrightBlue else KittyBlue
        isPaused -> if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
        isError -> MaterialTheme.colorScheme.error
        isTransitioning -> KittyBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    val glowAlpha by animateFloatAsState(
        targetValue = if (isConnected) (if (isDark) 0.35f else 0.16f) else if (isTransitioning) (if (isDark) 0.2f else 0.10f) else 0.04f,
        animationSpec = tween(500),
        label = "glowAlpha",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            val sizeScale = maxWidth.value / 240f
            // Ambient outer glow
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAccent.copy(alpha = glowAlpha),
                            primaryAccent.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }

            // Outer circular ring with neon/vibrant stroke
            Box(
                modifier = Modifier
                    .size(200.dp * sizeScale)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) {
                            if (isDark) Color(0xFF0C162A) else Color(0xFFEFF6FF)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                    .border(
                        width = if (isConnected) 2.5.dp else 1.5.dp,
                        brush = if (isConnected) {
                            if (isDark) {
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF38BDF8),
                                        Color(0xFF22D3EE),
                                        Color(0xFF3B82F6),
                                        Color(0xFF38BDF8),
                                    )
                                )
                            } else {
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF2563EB),
                                        Color(0xFF06B6D4),
                                        Color(0xFF3B82F6),
                                        Color(0xFF2563EB),
                                    )
                                )
                            }
                        } else {
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.outline,
                                    MaterialTheme.colorScheme.outlineVariant,
                                )
                            )
                        },
                        shape = CircleShape,
                    )
                    .clickable(
                        enabled = !isTransitioning,
                        role = Role.Button,
                        onClick = onClick,
                    )
                    .semantics {
                        role = Role.Button
                        stateDescription = localizedState
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Inner button surface
                Surface(
                    modifier = Modifier.size(150.dp * sizeScale),
                    shape = CircleShape,
                    color = if (isConnected) {
                        if (isDark) Color(0xFF0F172A) else Color.White
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        if (isConnected && !isDark) 1.5.dp else 1.dp,
                        if (isConnected) {
                            if (isDark) Color(0xFF1E293B) else Color(0xFFBFDBFE)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        },
                    ),
                    shadowElevation = if (!isDark && isConnected) 2.dp else 0.dp,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isTransitioning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(54.dp * sizeScale),
                                color = KittyBlue,
                                strokeWidth = 4.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_power),
                                contentDescription = stringResource(R.string.content_power_button),
                                modifier = Modifier.size(54.dp * sizeScale),
                                tint = if (isConnected) {
                                    if (isDark) Color(0xFF38BDF8) else Color(0xFF2563EB)
                                } else {
                                    primaryAccent
                                },
                            )
                        }
                    }
                }
            }

            // Shield check badge at bottom center of the circular border
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(30.dp * sizeScale)
                        .clip(CircleShape)
                        .background(Color(0xFF2563EB))
                        .border(2.dp, if (isDark) Color(0xFF0B0F19) else Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield_check),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp * sizeScale),
                        tint = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Status title & subtitle
        val statusTitle = when {
            isConnected -> stringResource(R.string.status_connected)
            isTransitioning -> stringResource(R.string.status_connecting)
            isPaused -> stringResource(R.string.status_paused, "")
            isError -> stringResource(R.string.status_error)
            else -> stringResource(R.string.status_disconnected)
        }
        val statusTitleColor = when {
            isConnected -> if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
            isPaused -> if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
            isError -> MaterialTheme.colorScheme.error
            isTransitioning -> KittyBlue
            else -> MaterialTheme.colorScheme.onSurface
        }
        val statusSubtitle = when {
            isConnected -> stringResource(R.string.status_connected_desc)
            isTransitioning -> stringResource(R.string.status_connecting_desc)
            isPaused -> stringResource(R.string.pause_vpn_desc)
            isError -> stringResource(R.string.status_error_desc)
            else -> stringResource(R.string.status_disconnected_desc)
        }

        Text(
            text = statusTitle,
            color = statusTitleColor,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = statusSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
@Composable
private fun ServerSummaryCard(
    server: TunnelServer?,
    vpn: VpnSnapshot,
    pingMs: Long?,
    automaticServer: Boolean,
    onClick: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            val flagDescriptionText = flagDescription(server?.countryCode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = countryFlag(server?.countryCode),
                    fontSize = 32.sp,
                    modifier = Modifier.semantics {
                        contentDescription = flagDescriptionText
                    },
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val isAllowlist = server?.isMobileServer() == true && !automaticServer
                    val subtitleColor = when {
                        isAllowlist -> if (isDark) Color(0xFF60A5FA) else KittyBlue
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = when {
                            automaticServer -> stringResource(R.string.selected_server_automatic)
                            server?.isMobileServer() == true -> {
                                stringResource(R.string.selected_server_mobile_allowlist)
                            }
                            else -> stringResource(R.string.selected_server_regular)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server?.name?.displayName() ?: stringResource(R.string.select_server),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!automaticServer && server?.isMobileServer() == true) {
                            val badgeColor = if (isDark) Color(0xFF60A5FA) else KittyBlue
                            val badgeBg = badgeColor.copy(alpha = 0.15f)
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = badgeBg,
                            ) {
                                Text(
                                    text = stringResource(R.string.server_badge_mobile_allowlist),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_down),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(54.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(14.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.ping),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pingMs?.let { stringResource(R.string.ping_ms, it.toInt()) }
                                ?: stringResource(R.string.not_available),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = pingMs?.let { ping ->
                                when {
                                    ping < 100 -> if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
                                    ping < 250 -> if (isDark) Color(0xFF38BDF8) else Color(0xFF2563EB)
                                    else -> MaterialTheme.colorScheme.error
                                }
                            } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        SignalBarsIndicator(pingMs = pingMs)
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outline,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_usage),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = stringResource(
                                R.string.profile_traffic,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    val sessionBytes = vpn.downloadedBytes + vpn.uploadedBytes
                    Text(
                        text = dataUsageValue(sessionBytes),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(58.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1.2f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_speed),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = stringResource(R.string.speed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = speedValue(
                            vpn.downloadBytesPerSecond,
                            vpn.uploadBytesPerSecond,
                        ),
                    )
                }
            }

        }
    }
}
@Composable
private fun dataUsageValue(bytes: Long): AnnotatedString {
    val formatted = formatBytes(bytes)
    val separator = formatted.lastIndexOf(' ')
    if (separator <= 0) return AnnotatedString(formatted)
    val numberStyle = SpanStyle(
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val unitStyle = SpanStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return buildAnnotatedString {
        withStyle(numberStyle) {
            append(formatted.substring(0, separator))
        }
        append(" ")
        withStyle(unitStyle) {
            append(formatted.substring(separator + 1))
        }
    }
}
@Composable
private fun speedValue(downloadBytesPerSecond: Long, uploadBytesPerSecond: Long): AnnotatedString {
    val useMegabits = maxOf(downloadBytesPerSecond, uploadBytesPerSecond) * 8.0 >= 1_000_000
    val unit = if (useMegabits) {
        stringResource(R.string.rate_unit_mbps)
    } else {
        stringResource(R.string.rate_unit_kbps)
    }
    val divisor = if (useMegabits) 1_000_000.0 else 1_000.0
    val numberStyle = speedNumberStyle()
    val symbolStyle = speedSymbolStyle()
    return buildAnnotatedString {
        withStyle(numberStyle) {
            append(formatRateNumber(downloadBytesPerSecond * 8.0 / divisor))
        }
        withStyle(symbolStyle) { append(" ↓ / ") }
        withStyle(numberStyle) {
            append(formatRateNumber(uploadBytesPerSecond * 8.0 / divisor))
        }
        withStyle(symbolStyle) { append(" ↑ ") }
        withStyle(symbolStyle) { append(unit) }
    }
}
@Composable
private fun speedNumberStyle() = SpanStyle(
    fontSize = 21.sp,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurface,
)
@Composable
private fun speedSymbolStyle() = SpanStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
private fun formatRateNumber(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 9.95 -> "%.0f".format(Locale.US, bitsPerSecond)
    bitsPerSecond >= 0.05 -> "%.1f".format(Locale.US, bitsPerSecond)
    else -> "0"
}
private fun String.displayName(): String = removePrefix("🚀").trimStart().ifBlank { this }
@Composable
private fun flagDescription(countryCode: String?): String =
    stringResource(R.string.content_server_code, countryCode ?: "XX")
@Composable
private fun ServersScreen(
    modifier: Modifier,
    profile: PreparedTunnelProfile?,
    selectedServerId: String?,
    connectionState: VpnConnectionState,
    loading: Boolean,
    onServerSelected: (String) -> Unit,
    automaticServer: Boolean,
    onAutomaticServer: () -> Unit,
    serverPings: Map<String, Long?>,
    pingingServers: Boolean,
    favoriteServerIds: Set<String>,
    onToggleFavorite: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    filterType: ServerFilterType,
    onFilterChanged: (ServerFilterType) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.servers_title),
            subtitle = stringResource(R.string.servers_description),
        )



        // Search and Filter Bar
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_servers)) },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_servers),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                buildList {
                    add(ServerFilterType.ALL to stringResource(R.string.filter_all))
                    add(ServerFilterType.REGULAR to stringResource(R.string.filter_regular))
                    add(ServerFilterType.MOBILE_ALLOWLIST to stringResource(R.string.filter_mobile_allowlist))
                    add(ServerFilterType.FAVORITES to stringResource(R.string.filter_favorites))
                    add(ServerFilterType.FASTEST to stringResource(R.string.filter_fastest))
                }.forEach { (type, label) ->
                    val isSelected = filterType == type
                    Surface(
                        onClick = { onFilterChanged(type) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) KittyBlue else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) KittyBlue else MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            profile == null || profile.servers.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.servers_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val query = searchQuery.trim().lowercase()
                var filtered = profile.servers.filter { server ->
                    val matchesQuery = query.isEmpty() ||
                        server.name.lowercase().contains(query) ||
                        server.countryCode.lowercase().contains(query)
                    val matchesFilter = filterType.matches(server, favoriteServerIds)
                    matchesQuery && matchesFilter
                }

                if (filterType == ServerFilterType.FASTEST) {
                    filtered = filtered.sortedBy { serverPings[it.id] ?: Long.MAX_VALUE }
                }

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (filterType == ServerFilterType.ALL && searchQuery.isEmpty()) {
                        item(key = "automatic") {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = automaticServer,
                                        role = Role.RadioButton,
                                        onClick = onAutomaticServer,
                                    ),
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (automaticServer) 1.5.dp else 1.dp,
                                    if (automaticServer) KittyBlue else MaterialTheme.colorScheme.outline,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = KittyBlue.copy(alpha = 0.15f),
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_stats),
                                                contentDescription = null,
                                                tint = KittyBlue,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.server_automatic),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = if (pingingServers) {
                                                stringResource(R.string.server_ping_checking)
                                             } else {
                                                stringResource(R.string.server_automatic_description)
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    RadioButton(
                                        selected = automaticServer,
                                        onClick = null,
                                    )
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (filterType == ServerFilterType.FAVORITES) {
                                        stringResource(R.string.no_favorite_servers)
                                    } else {
                                        stringResource(R.string.servers_empty)
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else if (filterType == ServerFilterType.ALL && searchQuery.isEmpty()) {
                        val regularServers = filtered.filter {
                            it.effectiveCategory() == TunnelServerCategory.REGULAR
                        }
                        val allowlistServers = filtered.filter {
                            it.isMobileServer()
                        }
                        val categoryCount = listOf(
                            regularServers,
                            allowlistServers,
                        ).count(List<TunnelServer>::isNotEmpty)

                        if (categoryCount > 1) {
                            if (regularServers.isNotEmpty()) {
                                item(key = "section_regular_header") {
                                    ServerCategoryHeader(
                                        title = R.string.servers_category_regular,
                                        description = R.string.servers_category_regular_desc,
                                        topPadding = 8,
                                    )
                                }
                                items(regularServers, key = TunnelServer::id) { server ->
                                    ServerItemCard(
                                        server = server,
                                        selected = !automaticServer && server.id == selectedServerId,
                                        isFav = favoriteServerIds.contains(server.id),
                                        pingValue = serverPings[server.id],
                                        pingingServers = pingingServers,
                                        isDark = isDark,
                                        onServerSelected = onServerSelected,
                                        onToggleFavorite = onToggleFavorite,
                                    )
                                }
                            }
                            if (allowlistServers.isNotEmpty()) {
                                item(key = "section_mobile_allowlist_header") {
                                    ServerCategoryHeader(
                                        title = R.string.servers_category_mobile_allowlist,
                                        description = R.string.servers_category_mobile_allowlist_desc,
                                        badge = R.string.server_badge_mobile_allowlist,
                                        accent = if (isDark) Color(0xFF60A5FA) else KittyBlue,
                                    )
                                }
                                items(allowlistServers, key = TunnelServer::id) { server ->
                                    ServerItemCard(
                                        server = server,
                                        selected = !automaticServer && server.id == selectedServerId,
                                        isFav = favoriteServerIds.contains(server.id),
                                        pingValue = serverPings[server.id],
                                        pingingServers = pingingServers,
                                        isDark = isDark,
                                        onServerSelected = onServerSelected,
                                        onToggleFavorite = onToggleFavorite,
                                    )
                                }
                            }
                        } else {
                            items(filtered, key = TunnelServer::id) { server ->
                                ServerItemCard(
                                    server = server,
                                    selected = !automaticServer && server.id == selectedServerId,
                                    isFav = favoriteServerIds.contains(server.id),
                                    pingValue = serverPings[server.id],
                                    pingingServers = pingingServers,
                                    isDark = isDark,
                                    onServerSelected = onServerSelected,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                        }
                    } else {
                        items(filtered, key = TunnelServer::id) { server ->
                            ServerItemCard(
                                server = server,
                                selected = !automaticServer && server.id == selectedServerId,
                                isFav = favoriteServerIds.contains(server.id),
                                pingValue = serverPings[server.id],
                                pingingServers = pingingServers,
                                isDark = isDark,
                                onServerSelected = onServerSelected,
                                onToggleFavorite = onToggleFavorite,
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ServerCategoryHeader(
    @StringRes title: Int,
    @StringRes description: Int,
    @StringRes badge: Int? = null,
    accent: Color? = null,
    topPadding: Int = 16,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = topPadding.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent ?: MaterialTheme.colorScheme.onSurface,
            )
            if (badge != null && accent != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accent.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = stringResource(badge),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
        }
        Text(
            text = stringResource(description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
@Composable
private fun ServerItemCard(
    server: TunnelServer,
    selected: Boolean,
    isFav: Boolean,
    pingValue: Long?,
    pingingServers: Boolean,
    isDark: Boolean,
    onServerSelected: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    var showGuide by remember(server.id) { mutableStateOf(false) }
    if (showGuide) {
        RelayConnectionGuideDialog(onDismiss = { showGuide = false })
    }
    val flagDescriptionText = flagDescription(server.countryCode)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onServerSelected(server.id) },
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) KittyBlue else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = countryFlag(server.countryCode),
                    fontSize = 24.sp,
                    modifier = Modifier.semantics {
                        contentDescription = flagDescriptionText
                    },
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = server.name.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (server.isMobileServer()) {
                        val badgeColor = if (isDark) Color(0xFF60A5FA) else KittyBlue
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = stringResource(R.string.server_badge_mobile_allowlist),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { onToggleFavorite(server.id) },
                    modifier = Modifier.size(KittyDimensions.IconButtonSize),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isFav) R.drawable.ic_crown else R.drawable.ic_shield,
                        ),
                        contentDescription = stringResource(R.string.favorite_toggle),
                        tint = if (isFav) KittyBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                RadioButton(selected = selected, onClick = null)
            }
            if (server.engine == TunnelEngineKind.LEVIK_RELAY) {
                TextButton(onClick = { showGuide = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_web),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.relay_guide_open))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        pingValue == null && pingingServers ->
                            stringResource(R.string.server_ping_checking_short)
                        pingValue != null -> stringResource(
                            R.string.ping_ms,
                            pingValue.toInt(),
                        )
                        else -> stringResource(R.string.not_available)
                    },
                    color = pingValue?.let { ping ->
                        when {
                            ping < 100 -> if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
                            ping < 250 -> if (isDark) Color(0xFF38BDF8) else Color(0xFF2563EB)
                            else -> MaterialTheme.colorScheme.error
                        }
                    } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                SignalBarsIndicator(pingMs = pingValue)
            }
        }
    }
}
@Composable
private fun StatsScreen(
    modifier: Modifier,
    vpn: VpnSnapshot,
    liveSpeedHistory: List<SpeedSample>,
    trafficHistory: List<DailyTraffic>,
    perAppTraffic: List<AppTrafficUsage>,
    onRunDiagnostics: () -> Unit,
    onAnalyzeAppTraffic: () -> Unit,
    onResetPerAppTrafficBaseline: () -> Unit,
    onClearTrafficHistory: () -> Unit,
    onExportTrafficHistory: () -> Unit,
) {
    var historyDaysMode by remember { mutableStateOf(7) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = stringResource(R.string.stats_title))
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Live Real-Time Speed Graph
            Text(
                text = stringResource(R.string.live_traffic_chart_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            LiveSpeedChartCard(
                history = liveSpeedHistory,
                currentDown = vpn.downloadBytesPerSecond,
                currentUp = vpn.uploadBytesPerSecond,
            )

            Text(
                text = stringResource(R.string.stats_session),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            StatsCard(
                icon = painterResource(R.drawable.ic_stats),
                label = stringResource(R.string.stats_downloaded),
                value = formatBytes(vpn.downloadedBytes),
            )
            StatsCard(
                icon = painterResource(R.drawable.ic_servers),
                label = stringResource(R.string.stats_uploaded),
                value = formatBytes(vpn.uploadedBytes),
            )
            StatsCard(
                icon = painterResource(R.drawable.ic_home),
                label = stringResource(R.string.stats_duration),
                value = formatDuration(vpn.connectedDurationSeconds),
            )

            // Per-App Network Activity Breakdown
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.per_app_traffic_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.per_app_traffic_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (perAppTraffic.isNotEmpty()) {
                    TextButton(
                        onClick = onResetPerAppTrafficBaseline,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.per_app_reset_baseline_btn),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(18.dp)) {
                    if (perAppTraffic.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.per_app_traffic_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Justify,
                            )
                            Button(
                                onClick = onAnalyzeAppTraffic,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = KittyDimensions.ButtonHeight),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(stringResource(R.string.per_app_load_btn), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        perAppTraffic.take(8).forEach { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon.toBitmap(32, 32).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shield),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = KittyBlue,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = formatBytes(app.rxBytes + app.txBytes),
                                    fontWeight = FontWeight.Bold,
                                    color = KittyBlue,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            // Usage History Card (7d / 30d toggle, export, clear)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.traffic_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (trafficHistory.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = onExportTrafficHistory,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.traffic_history_export_btn),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        TextButton(
                            onClick = onClearTrafficHistory,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text(
                                text = stringResource(R.string.traffic_history_clear_btn),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(18.dp)) {
                    if (trafficHistory.isEmpty()) {
                        Text(
                            text = stringResource(R.string.traffic_history_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = historyDaysMode == 7,
                                onClick = { historyDaysMode = 7 },
                                label = { Text(stringResource(R.string.traffic_history_7d)) },
                            )
                            FilterChip(
                                selected = historyDaysMode == 30,
                                onClick = { historyDaysMode = 30 },
                                label = { Text(stringResource(R.string.traffic_history_30d)) },
                            )
                        }

                        val displayedHistory = if (historyDaysMode == 7) {
                            trafficHistory.takeLast(7).reversed()
                        } else {
                            trafficHistory.takeLast(30).reversed()
                        }

                        displayedHistory.forEach { item ->
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = item.date,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${formatBytes(item.downloadedBytes)} ↓ / ${formatBytes(item.uploadedBytes)} ↑",
                                    fontWeight = FontWeight.Bold,
                                    color = KittyBlue,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            // Diagnostics Button Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.diagnostics_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.diagnostics_running),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = onRunDiagnostics,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = KittyDimensions.ButtonHeight),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.diagnostics_btn), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Text(
                text = stringResource(R.string.stats_privacy_note),
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
@Composable
private fun LiveSpeedChartCard(
    history: List<SpeedSample>,
    currentDown: Long,
    currentUp: Long,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(KittyGreen))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Down: ${formatBytes(currentDown)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = KittyGreen,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(KittyBlue))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Up: ${formatBytes(currentUp)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = KittyBlue,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val samples = if (history.isEmpty()) listOf(SpeedSample(0, 0)) else history
            val maxSpeed = samples.maxOf { maxOf(it.downloadBps, it.uploadBps) }.coerceAtLeast(10_000L).toFloat()

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp),
            ) {
                val w = size.width
                val h = size.height
                val step = if (samples.size > 1) w / (samples.size - 1) else w

                val downPath = Path()
                val upPath = Path()
                val downFillPath = Path()

                samples.forEachIndexed { i, sample ->
                    val x = i * step
                    val yDown = h - (sample.downloadBps.toFloat() / maxSpeed) * (h * 0.85f)
                    val yUp = h - (sample.uploadBps.toFloat() / maxSpeed) * (h * 0.85f)
                    if (i == 0) {
                        downPath.moveTo(x, yDown)
                        upPath.moveTo(x, yUp)
                        downFillPath.moveTo(x, h)
                        downFillPath.lineTo(x, yDown)
                    } else {
                        downPath.lineTo(x, yDown)
                        upPath.lineTo(x, yUp)
                        downFillPath.lineTo(x, yDown)
                    }
                }
                downFillPath.lineTo(w, h)
                downFillPath.close()

                drawPath(
                    path = downFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            KittyGreen.copy(alpha = 0.2f),
                            Color.Transparent,
                        ),
                    ),
                )
                drawPath(
                    path = downPath,
                    color = KittyGreen,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
                drawPath(
                    path = upPath,
                    color = KittyBlue,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}
@Composable
private fun StatsCard(icon: Painter, label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = KittyBlue.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = KittyBlue,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
@Composable
private fun AntiDpiDialog(
    enabled: Boolean,
    currentPreset: AntiDpiPreset,
    customPackets: String,
    customLength: String,
    customInterval: String,
    onPresetSelected: (AntiDpiPreset) -> Unit,
    onCustomParamsChanged: (String, String, String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var isEnabled by remember(enabled) { mutableStateOf(enabled) }
    var selectedPreset by remember(currentPreset) { mutableStateOf(currentPreset) }
    var packetsText by remember(customPackets) { mutableStateOf(customPackets) }
    var lengthText by remember(customLength) { mutableStateOf(customLength) }
    var intervalText by remember(customInterval) { mutableStateOf(customInterval) }
    var isCustomFormatError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = stringResource(R.string.anti_dpi_dialog_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.anti_dpi_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )

                // Master Toggle Switch
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.anti_dpi_enable_toggle),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                isEnabled = checked
                                if (checked && selectedPreset == AntiDpiPreset.OFF) {
                                    selectedPreset = AntiDpiPreset.TLS_HELLO
                                }
                            },
                            colors = KittySwitchDefaults.colors(),
                        )
                    }
                }

                if (isEnabled) {
                    Text(
                        text = stringResource(R.string.anti_dpi_presets_header),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    AntiDpiPreset.entries.filter { it != AntiDpiPreset.OFF }.forEach { preset ->
                        val isSelected = selectedPreset == preset
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedPreset = preset },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedPreset = preset },
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = preset.titleRu,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = preset.descriptionRu,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (preset != AntiDpiPreset.CUSTOM) {
                                        Spacer(Modifier.height(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                        ) {
                                            Text(
                                                text = "packets: ${preset.defaultPackets} | length: ${preset.defaultLength} | interval: ${preset.defaultInterval} ms",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (selectedPreset == AntiDpiPreset.CUSTOM) {
                        Text(
                            text = stringResource(R.string.anti_dpi_custom_header),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        OutlinedTextField(
                            value = packetsText,
                            onValueChange = {
                                packetsText = it
                                isCustomFormatError = false
                            },
                            label = { Text(stringResource(R.string.anti_dpi_packets_label)) },
                            placeholder = { Text(stringResource(R.string.anti_dpi_packets_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = lengthText,
                            onValueChange = {
                                lengthText = it
                                isCustomFormatError = false
                            },
                            label = { Text(stringResource(R.string.anti_dpi_length_label)) },
                            placeholder = { Text(stringResource(R.string.anti_dpi_length_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = {
                                intervalText = it
                                isCustomFormatError = false
                            },
                            label = { Text(stringResource(R.string.anti_dpi_interval_label)) },
                            placeholder = { Text(stringResource(R.string.anti_dpi_interval_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (isCustomFormatError) {
                            Text(
                                text = stringResource(R.string.anti_dpi_custom_invalid),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isEnabled) {
                        onEnabledChanged(false)
                    } else {
                        if (selectedPreset == AntiDpiPreset.CUSTOM) {
                            val cleanPackets = packetsText.trim()
                            val cleanLength = lengthText.trim()
                            val cleanInterval = intervalText.trim()
                            val safeRegex = Regex("^[a-zA-Z0-9,-]+$")
                            if (!cleanPackets.matches(safeRegex) || !cleanLength.matches(safeRegex) || !cleanInterval.matches(safeRegex)) {
                                isCustomFormatError = true
                                return@Button
                            }
                            onCustomParamsChanged(cleanPackets, cleanLength, cleanInterval)
                        } else {
                            onPresetSelected(selectedPreset)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.anti_dpi_apply_btn), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun RoutingPresetDialog(
    currentPreset: RoutingPreset,
    isMobileServer: Boolean = false,
    onPresetSelected: (RoutingPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.routing_preset_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RoutingPreset.entries.forEach { preset ->
                    val title = if (isMobileServer && preset == RoutingPreset.BYPASS_RU) {
                        "LTE (${preset.titleRu})"
                    } else {
                        preset.titleRu
                    }
                    val description = if (isMobileServer && preset == RoutingPreset.BYPASS_RU) {
                        "Белый список мобильных операторов РФ (LTE)"
                    } else {
                        preset.descriptionRu
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentPreset == preset,
                                onClick = { onPresetSelected(preset) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentPreset == preset, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, fontWeight = FontWeight.SemiBold)
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun WifiProtectionDialog(
    autoConnect: Boolean,
    trustedSsids: Set<String>,
    onAutoConnectChanged: (Boolean) -> Unit,
    onAddTrusted: (String) -> Unit,
    onRemoveTrusted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newSsidText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.wifi_protection_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.untrusted_wifi_auto_connect),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.untrusted_wifi_auto_connect_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoConnect,
                        onCheckedChange = onAutoConnectChanged,
                        colors = KittySwitchDefaults.colors(),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.trusted_wifi_list_title),
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newSsidText,
                        onValueChange = { newSsidText = it },
                        placeholder = { Text(stringResource(R.string.add_trusted_wifi_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = {
                            if (newSsidText.isNotBlank()) {
                                onAddTrusted(newSsidText)
                                newSsidText = ""
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = KittyDimensions.ButtonHeight),
                    ) {
                        Text(stringResource(R.string.add), fontWeight = FontWeight.SemiBold)
                    }
                }
                val list = trustedSsids.toList()
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.no_trusted_wifi),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(list) { ssid ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ssid,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onRemoveTrusted(ssid) }) {
                                    Text(
                                        stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun KillSwitchDialog(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.kill_switch_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.kill_switch_enable),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.kill_switch_app_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                        colors = KittySwitchDefaults.colors(),
                    )
                }
                HorizontalDivider()
                Text(stringResource(R.string.kill_switch_dialog_body))
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.open_vpn_settings), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun LogsViewerDialog(
    logs: List<LogEntry>,
    formattedLogs: String,
    onClear: () -> Unit,
    onShare: (String) -> Unit,
    onSendSupport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.logs_viewer_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(logs) { entry ->
                            Text(
                                text = entry.toFormattedString(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = onClear,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
                ) {
                    Text(stringResource(R.string.logs_clear_btn), fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { onShare(formattedLogs) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
                ) {
                    Text(stringResource(R.string.logs_share_btn), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onSendSupport,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
                ) {
                    Text(stringResource(R.string.logs_send_support), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun SplitTunnelModeDialog(
    currentMode: SplitTunnelMode,
    selectedCount: Int,
    onModeSelected: (SplitTunnelMode) -> Unit,
    onSelectApps: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.split_tunneling_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SplitTunnelMode.entries.forEach { mode ->
                    val label = when (mode) {
                        SplitTunnelMode.OFF -> stringResource(R.string.split_tunnel_mode_off)
                        SplitTunnelMode.DISALLOWED -> stringResource(R.string.split_tunnel_mode_disallowed)
                        SplitTunnelMode.ALLOWED -> stringResource(R.string.split_tunnel_mode_allowed)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentMode == mode,
                                onClick = { onModeSelected(mode) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentMode == mode, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (currentMode != SplitTunnelMode.OFF) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onSelectApps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = KittyDimensions.ButtonHeight),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.split_tunnel_select_apps, selectedCount), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun AppSelectorDialog(
    apps: List<InstalledAppItem>,
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    onImportPackages: (String) -> Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var searchQuery by remember { mutableStateOf("") }
    val selectableApps = remember(apps, selectedPackages) {
        val installedPackages = apps.mapTo(mutableSetOf(), InstalledAppItem::packageName)
        // Keep imported packages removable even when they are not installed on this device.
        apps + (selectedPackages - installedPackages).sorted().map { packageName ->
            InstalledAppItem(packageName, packageName, null)
        }
    }
    val filteredApps = remember(selectableApps, searchQuery) {
        if (searchQuery.isBlank()) selectableApps
        else selectableApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.split_tunnel_select_apps, selectedPackages.size), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = selectedPackages.isNotEmpty(),
                        onClick = {
                            val message = try {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    ?: error("Clipboard unavailable")
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        resources.getString(R.string.split_tunneling_title),
                                        SplitTunnelPackageList.format(selectedPackages),
                                    ),
                                )
                                resources.getString(R.string.split_tunnel_copied, selectedPackages.size)
                            } catch (_: RuntimeException) {
                                AppLogger.w("AppSelectorDialog", "Unable to copy or import split tunnel packages")
                                resources.getString(R.string.split_tunnel_clipboard_error)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.split_tunnel_copy))
                    }
                    TextButton(
                        onClick = {
                            val message = try {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    ?: error("Clipboard unavailable")
                                val clip = clipboard.primaryClip
                                // Read text only: do not resolve clipboard URIs or launch intents.
                                val text = if (clip == null) "" else (0 until clip.itemCount)
                                    .mapNotNull { clip.getItemAt(it).text }
                                    .joinToString("\n")
                                val addedCount = onImportPackages(text)
                                if (addedCount == 0) {
                                    resources.getString(R.string.split_tunnel_nothing_to_import)
                                } else {
                                    resources.getString(R.string.split_tunnel_imported, addedCount)
                                }
                            } catch (_: RuntimeException) {
                                AppLogger.w("AppSelectorDialog", "Unable to copy or import split tunnel packages")
                                resources.getString(R.string.split_tunnel_clipboard_error)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(painterResource(R.drawable.ic_paste), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.split_tunnel_paste))
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.split_tunnel_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                if (filteredApps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.split_tunnel_no_apps),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredApps, key = InstalledAppItem::packageName) { app ->
                            val isSelected = selectedPackages.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTogglePackage(app.packageName) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon.toBitmap(40, 40).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_shield),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = KittyBlue,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = isSelected,
                                    onCheckedChange = { onTogglePackage(app.packageName) },
                                    colors = KittySwitchDefaults.colors(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun DnsProviderDialog(
    currentProvider: DnsProvider,
    customIp: String,
    useDoh: Boolean,
    customDohUrl: String,
    onProviderSelected: (DnsProvider) -> Unit,
    onCustomIpChanged: (String) -> Unit,
    onUseDohChanged: (Boolean) -> Unit,
    onCustomDohUrlChanged: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customText by remember { mutableStateOf(customIp) }
    var customDohText by remember { mutableStateOf(customDohUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.dns_settings_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // DoH Global Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.doh_enable_toggle),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.doh_enable_toggle_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = useDoh,
                        onCheckedChange = onUseDohChanged,
                        colors = KittySwitchDefaults.colors(),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                DnsProvider.entries.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentProvider == provider,
                                onClick = { onProviderSelected(provider) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentProvider == provider, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(provider.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                provider.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (currentProvider == DnsProvider.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customText,
                        onValueChange = {
                            customText = it
                            onCustomIpChanged(it)
                        },
                        label = { Text(stringResource(R.string.dns_custom_ip_label)) },
                        placeholder = { Text(stringResource(R.string.dns_custom_ip_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    if (useDoh) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customDohText,
                            onValueChange = {
                                customDohText = it
                                onCustomDohUrlChanged(it)
                            },
                            label = { Text(stringResource(R.string.doh_custom_url_label)) },
                            placeholder = { Text(stringResource(R.string.doh_custom_url_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun AppIconDialog(
    currentIcon: AppIcon,
    onIconSelected: (AppIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_icon_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon.entries.forEach { icon ->
                    val label = when (icon) {
                        AppIcon.LIGHT -> R.string.app_icon_light
                        AppIcon.DARK -> R.string.app_icon_dark
                        AppIcon.MONOCHROME -> R.string.app_icon_monochrome
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = currentIcon == icon,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { onIconSelected(icon) },
                        ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentIcon == icon, onClick = null)
                        Image(painterResource(icon.previewResource), null, Modifier.padding(horizontal = 12.dp).size(48.dp))
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.ic_close), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.close))
            }
        },
    )
}
@Composable
private fun ThemeDialog(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.theme_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                        ThemeMode.AMOLED -> stringResource(R.string.theme_amoled)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentTheme == mode,
                                onClick = { onThemeSelected(mode) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentTheme == mode, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun CustomRoutingDialog(
    directDomains: Set<String>,
    proxyDomains: Set<String>,
    onAddDirect: (String) -> Unit,
    onRemoveDirect: (String) -> Unit,
    onAddProxy: (String) -> Unit,
    onRemoveProxy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var newDomainText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.custom_routing_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("Direct (${directDomains.size})" to 0, "Proxy (${proxyDomains.size})" to 1).forEach { (label, tab) ->
                        val isSelected = selectedTab == tab
                        Surface(
                            onClick = { selectedTab = tab },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) KittyBlue else MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) KittyBlue else MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newDomainText,
                        onValueChange = { newDomainText = it },
                        placeholder = { Text(stringResource(R.string.add_domain_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = {
                            if (newDomainText.isNotBlank()) {
                                if (selectedTab == 0) onAddDirect(newDomainText)
                                else onAddProxy(newDomainText)
                                newDomainText = ""
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = KittyDimensions.ButtonHeight),
                    ) {
                        Text(stringResource(R.string.add), fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val currentList = if (selectedTab == 0) directDomains.toList() else proxyDomains.toList()
                if (currentList.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.no_custom_domains),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(currentList) { domain ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    domain,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        if (selectedTab == 0) onRemoveDirect(domain)
                                        else onRemoveProxy(domain)
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun DiagnosticsDialog(report: DiagnosticReport, running: Boolean, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.diagnostics_report_title)) },
        text = { Text(report.toFormattedString(), modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onShare) { Text(stringResource(R.string.share_report)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

@Composable
private fun PauseVpnDialog(
    onDismiss: () -> Unit,
    onPause: (Int) -> Unit,
) {
    val options = listOf(5, 15, 60)
    var selectedMinutes by remember { mutableStateOf(15) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.pause_vpn_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.pause_vpn_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedMinutes == minutes,
                                onClick = { selectedMinutes = minutes },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedMinutes == minutes, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = when (minutes) {
                                5 -> "5 минут"
                                15 -> "15 минут"
                                else -> "1 час"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPause(selectedMinutes) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.pause_vpn_btn), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = KittyDimensions.ButtonHeight),
            ) {
                Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
@Composable
private fun QrCameraPreview(
    onCodeScanned: (String) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                cameraProviderFuture.addListener({
                    if (disposed.get() || delivered.get()) return@addListener
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analyzerExecutor) { image ->
                            try {
                                if (!disposed.get() && !delivered.get()) {
                                    decodeQrCode(image)?.let { code ->
                                        if (delivered.compareAndSet(false, true)) {
                                            ContextCompat.getMainExecutor(context).execute {
                                                if (!disposed.get()) onCodeScanned(code)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                image.close()
                            }
                        }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (_: Exception) {
                        if (!disposed.get() && delivered.compareAndSet(false, true)) {
                            onCameraError()
                        }
                    }
                }, ContextCompat.getMainExecutor(viewContext))
            }
        },
    )

    DisposableEffect(cameraProviderFuture, lifecycleOwner) {
        onDispose {
            disposed.set(true)
            delivered.set(true)
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
            analyzerExecutor.shutdownNow()
        }
    }
}
private fun decodeQrCode(image: androidx.camera.core.ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    val buffer = plane.buffer
    val data = ByteArray(width * height)
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val row = ByteArray(rowStride)
    buffer.rewind()
    for (y in 0 until height) {
        val bytesToRead = minOf(rowStride, buffer.remaining())
        if (bytesToRead <= 0) break
        buffer.get(row, 0, bytesToRead)
        for (x in 0 until width) {
            val sourceIndex = x * pixelStride
            if (sourceIndex < bytesToRead) data[y * width + x] = row[sourceIndex]
        }
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    return runCatching {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull().also { reader.reset() }
}
@Composable
private fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        subtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val GIB = 1024.0 * 1024.0 * 1024.0
private const val MIB = 1024.0 * 1024.0
private const val KIB = 1024.0
@Composable
private fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val remSec = s % 60
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.duration_minutes_seconds, minutes, remSec)
        else -> stringResource(R.string.duration_seconds, remSec)
    }
}

private val DATE_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
@Composable
private fun VpnConnectionState.localized(): String = stringResource(
    when (this) {
        VpnConnectionState.DISCONNECTED -> R.string.status_disconnected
        VpnConnectionState.CONNECTING -> R.string.status_connecting
        VpnConnectionState.CONNECTED -> R.string.status_connected
        VpnConnectionState.PAUSED -> R.string.status_paused
        VpnConnectionState.RECONNECTING -> R.string.status_reconnecting
        VpnConnectionState.STOPPING -> R.string.status_stopping
        VpnConnectionState.ERROR -> R.string.status_error
        VpnConnectionState.LOCKDOWN -> R.string.status_lockdown
    },
)
@Composable
private fun VpnFailure.localized(): String = stringResource(
    when (this) {
        VpnFailure.CORE_UNAVAILABLE -> R.string.core_unavailable
        VpnFailure.INVALID_PROFILE -> R.string.core_rejected_config
        VpnFailure.PERMISSION_REVOKED -> R.string.vpn_permission_denied
        VpnFailure.NETWORK -> R.string.problem_vpn_network_body
        VpnFailure.NETWORK_REQUIREMENT -> R.string.network_requirement_unavailable
    },
)
@Composable
internal fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0).toDouble()
    return when {
        value >= GIB -> stringResource(R.string.bytes_gb, value / GIB)
        value >= MIB -> stringResource(R.string.bytes_mb, value / MIB)
        value >= KIB -> stringResource(R.string.bytes_kb, value / KIB)
        else -> stringResource(R.string.bytes_b, value)
    }
}

enum class NavigationDestination(
    val tab: AppTab,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
) {
    HOME(AppTab.HOME, R.drawable.ic_home, R.string.nav_home),
    SERVERS(AppTab.SERVERS, R.drawable.ic_servers, R.string.nav_servers),
    STATS(AppTab.STATS, R.drawable.ic_stats, R.string.nav_stats),
    PROFILE(AppTab.PROFILE, R.drawable.ic_profile, R.string.nav_profile),
}
@Composable
fun HelloKittyVpnApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    var dialog by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }
    var cameraAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> cameraAllowed = granted; if (granted) dialog = "scan" }
    LaunchedEffect(state.message) {
        state.message?.let {
            viewModel.clearMessage()
            snackbar.showSnackbar(resources.getString(it.resource))
        }
    }
    LaunchedEffect(state.profile?.profileId) { if (state.profile != null) { dialog = null; importText = "" } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (!isTv) AppNavigationBar(state.tab, viewModel::selectTab) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Row(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
        if (isTv) TvNavigationRail(state.tab, viewModel::selectTab)
        Box(Modifier.weight(1f)) {
        val modifier = Modifier.fillMaxSize()
        when (state.tab) {
            AppTab.HOME -> HomeScreen(modifier, state, viewModel::connectOrDisconnect,
                { dialog = "pause" }, viewModel::resumeVpn, { viewModel.selectTab(AppTab.PROFILE) },
                { viewModel.selectTab(AppTab.SERVERS) }, { dialog = "routing" }, { dialog = "dpi" })
            AppTab.SERVERS -> Column(modifier) {
                OutlinedButton(onClick = { dialog = "import" }, modifier = Modifier.fillMaxWidth().padding(20.dp),
                    enabled = !state.refreshing && state.vpn.state in setOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR)) {
                    Text(stringResource(R.string.import_profile))
                }
                ServersScreen(Modifier.weight(1f), state.profile, displayedServerId(state), state.vpn.state,
                    state.refreshing, viewModel::selectServer, state.automaticServer, viewModel::selectAutomaticServer,
                    state.serverPings, state.pingingServers, state.favoriteServerIds, viewModel::toggleFavoriteServer,
                    state.serverSearchQuery, viewModel::setServerSearchQuery, state.serverFilter, viewModel::setServerFilter)
            }
            AppTab.STATS -> StatsScreen(modifier, state.vpn, state.liveSpeedHistory, state.trafficHistory,
                state.perAppTraffic, viewModel::runDiagnostics,
                { dialog = "traffic-consent" }, { viewModel.resetPerAppTrafficBaseline(context.packageManager, context) },
                { dialog = "clear-history" }, { viewModel.exportTrafficHistory(resources.getString(R.string.traffic_history_export_title)) })
            AppTab.PROFILE -> KittySettingsScreen(modifier, state, viewModel) { dialog = it }
        }
        }
        }
    }
    when (dialog) {
        "scan" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.activation_scan_title)) },
            text = { if (cameraAllowed) QrCameraPreview({ importText = it; dialog = "import" }, { dialog = "import" }, Modifier.fillMaxWidth().height(300.dp)) },
            confirmButton = { TextButton(onClick = { dialog = "import" }) { Text(stringResource(R.string.cancel)) } })
        "import" -> AlertDialog(onDismissRequest = { if (!state.refreshing) dialog = null },
            title = { Text(stringResource(R.string.import_profile)) },
            text = { Column { Text(stringResource(R.string.local_profile_description))
                TextButton(onClick = { if (cameraAllowed) dialog = "scan" else cameraPermission.launch(Manifest.permission.CAMERA) }, enabled = !state.refreshing) { Text(stringResource(R.string.activation_scan_title)) }
                OutlinedTextField(value = importText, onValueChange = { if (it.length <= 1_048_576) importText = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, enabled = !state.refreshing) } },
            confirmButton = { TextButton(onClick = { viewModel.importProfile(importText) }, enabled = !state.refreshing && importText.isNotBlank()) { Text(stringResource(R.string.import_profile)) } },
            dismissButton = { TextButton(onClick = { dialog = null; importText = "" }, enabled = !state.refreshing) { Text(stringResource(R.string.cancel)) } })
        "routing" -> RoutingPresetDialog(state.routingPreset, state.profile?.servers?.firstOrNull { it.id == displayedServerId(state) }?.isMobileServer() == true,
            { viewModel.setRoutingPreset(it); dialog = null }, { dialog = null })
        "dpi" -> AntiDpiDialog(state.antiDpiEnabled, state.antiDpiPreset, state.antiDpiPackets, state.antiDpiLength, state.antiDpiInterval,
            { viewModel.setAntiDpiPreset(it) }, viewModel::setAntiDpiCustomParams, viewModel::setAntiDpiEnabled, { dialog = null })
        "split" -> SplitTunnelModeDialog(state.splitTunnelMode, state.splitTunnelPackages.size,
            viewModel::setSplitTunnelMode, { dialog = "apps-consent" }, { dialog = null })
        "apps-consent", "traffic-consent" -> AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.split_tunneling_title)) }, text = { Text(stringResource(R.string.local_apps_consent)) },
            confirmButton = { TextButton(onClick = {
                viewModel.acceptInstalledAppsConsent()
                if (dialog == "traffic-consent") { viewModel.loadPerAppTraffic(context.packageManager, context); dialog = null }
                else { viewModel.loadInstalledApps(context.packageManager); dialog = "apps" }
            }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
        "apps" -> AppSelectorDialog(state.installedApps, state.splitTunnelPackages, viewModel::toggleSplitTunnelPackage,
            viewModel::importSplitTunnelPackages, { dialog = null })
        "dns" -> DnsProviderDialog(state.dnsProvider, state.customDnsIpv4, state.useDoh, state.customDohUrl,
            viewModel::setDnsProvider, viewModel::setCustomDnsIpv4, viewModel::setUseDoh, viewModel::setCustomDohUrl, { dialog = null })
        "wifi" -> WifiProtectionDialog(state.autoConnectUntrustedWifi, state.trustedWifiSsids,
            viewModel::setAutoConnectUntrustedWifi, viewModel::addTrustedWifi, viewModel::removeTrustedWifi, { dialog = null })
        "kill" -> KillSwitchDialog(state.killSwitchEnabled, viewModel::setKillSwitchEnabled,
            { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)); dialog = null }, { dialog = null })
        "theme" -> ThemeDialog(state.themeMode, viewModel::setThemeMode, { dialog = null })
        "icon" -> AppIconDialog(state.appIcon, viewModel::setAppIcon, { dialog = null })
        "custom" -> CustomRoutingDialog(state.customDirectDomains, state.customProxyDomains,
            viewModel::addCustomDirectDomain, viewModel::removeCustomDirectDomain,
            viewModel::addCustomProxyDomain, viewModel::removeCustomProxyDomain, { dialog = null })
        "logs" -> LogsViewerDialog(viewModel.getLogs(), viewModel.getFormattedLogs(), viewModel::clearLogs,
            { viewModel.shareText("Hello Kitty VPN", it) }, { viewModel.shareText("Hello Kitty VPN", viewModel.getFormattedLogs()) }, { dialog = null })
        "pause" -> PauseVpnDialog({ dialog = null }, { viewModel.pauseVpn(it); dialog = null })
        "clear-history" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.traffic_history_clear_confirm_title)) },
            confirmButton = { TextButton(onClick = { viewModel.clearTrafficHistory(); dialog = null }) { Text(stringResource(R.string.confirm)) } })
        "remove" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.remove_profile)) },
            confirmButton = { TextButton(onClick = { viewModel.removeProfile(); dialog = null }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
        "privacy" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.profile_privacy_policy)) },
            text = { Text(stringResource(R.string.local_privacy)) }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } })
    }
    if (state.showVpnDisclosure) AlertDialog(onDismissRequest = viewModel::declineVpnDisclosure,
        title = { Text(stringResource(R.string.vpn_disclosure_title)) }, text = { Text(stringResource(R.string.vpn_disclosure_body)) },
        confirmButton = { TextButton(onClick = viewModel::acceptVpnDisclosure) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = viewModel::declineVpnDisclosure) { Text(stringResource(R.string.cancel)) } })
    state.problem?.let { problem -> AlertDialog(onDismissRequest = viewModel::dismissProblem,
        title = { Text(stringResource(R.string.problem_profile_title)) }, text = { Text(stringResource(problem.reason.resource)) },
        confirmButton = { TextButton(onClick = viewModel::dismissProblem) { Text("OK") } }) }
    state.diagnosticReport?.let { report -> DiagnosticsDialog(report, state.runningDiagnostics,
        viewModel::shareDiagnosticReport, viewModel::dismissDiagnostics) }
}

@Composable
private fun KittySettingsScreen(modifier: Modifier, state: AppUiState, viewModel: AppViewModel, open: (String) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.profile_title))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.clean_app_description), style = MaterialTheme.typography.bodyMedium)
            KittySetting(stringResource(R.string.import_profile)) { open("import") }
            KittySetting(stringResource(R.string.routing_preset_title)) { open("routing") }
            KittySetting(stringResource(R.string.anti_dpi_title)) { open("dpi") }
            KittyToggle(stringResource(R.string.auto_healing_title), state.autoHealingEnabled, viewModel::setAutoHealingEnabled)
            KittySetting(stringResource(R.string.split_tunneling_title)) { open("split") }
            KittySetting(stringResource(R.string.dns_settings_title)) { open("dns") }
            KittySetting(stringResource(R.string.custom_routing_title)) { open("custom") }
            KittySetting(stringResource(R.string.kill_switch_title)) { open("kill") }
            KittySetting(stringResource(R.string.wifi_protection_title)) { open("wifi") }
            KittyToggle(stringResource(R.string.auto_fallback), state.autoFallbackServer, viewModel::setAutoFallbackServer)
            KittyToggle(stringResource(R.string.auto_connect_boot), state.autoConnectOnBoot, viewModel::setAutoConnectOnBoot)
            KittySetting(stringResource(R.string.theme_title)) { open("theme") }
            KittySetting(stringResource(R.string.app_icon_title)) { open("icon") }
            KittySetting(stringResource(R.string.battery_optimization_title), viewModel::requestIgnoreBatteryOptimization)
            KittySetting(stringResource(R.string.logs_viewer_btn)) { open("logs") }
            KittySetting(stringResource(R.string.profile_privacy_policy)) { open("privacy") }
            if (state.profile != null && state.vpn.state in setOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR)) {
                KittySetting(stringResource(R.string.remove_profile)) { open("remove") }
            }
            Text("Hello Kitty VPN ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun KittySetting(title: String, action: () -> Unit) {
    Surface(onClick = action, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Text(title, Modifier.padding(18.dp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun KittyToggle(title: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(checked, changed, colors = KittySwitchDefaults.colors())
        }
    }
}
