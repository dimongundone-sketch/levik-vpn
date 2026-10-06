package org.hellokittyvpn.android.ui

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import org.hellokittyvpn.android.AppContainer
import org.hellokittyvpn.android.BuildConfig
import org.hellokittyvpn.android.core.logger.AppLogger
import org.hellokittyvpn.android.core.logger.LogEntry
import org.hellokittyvpn.android.core.network.ApiException
import org.hellokittyvpn.android.core.network.DiagnosticReport
import org.hellokittyvpn.android.core.network.NetworkDiagnostics
import org.hellokittyvpn.android.core.network.WhitelistDetector
import org.hellokittyvpn.android.core.network.WhitelistMode
import org.hellokittyvpn.android.data.AntiDpiPreset
import org.hellokittyvpn.android.data.AppRepository
import org.hellokittyvpn.android.data.AppSettings
import org.hellokittyvpn.android.data.DailyTraffic
import org.hellokittyvpn.android.data.DnsProvider
import org.hellokittyvpn.android.data.RoutingPreset
import org.hellokittyvpn.android.data.SplitTunnelMode
import org.hellokittyvpn.android.data.SplitTunnelPackageList
import org.hellokittyvpn.android.data.AppIcon
import org.hellokittyvpn.android.data.ThemeMode
import org.hellokittyvpn.android.data.TrafficHistoryStore
import org.hellokittyvpn.android.vpn.PreparedTunnelProfile
import org.hellokittyvpn.android.vpn.ServerPinger
import org.hellokittyvpn.android.vpn.TunnelServer
import org.hellokittyvpn.android.vpn.TunnelEngineKind
import org.hellokittyvpn.android.vpn.VpnConnectionState
import org.hellokittyvpn.android.vpn.VpnController
import org.hellokittyvpn.android.vpn.VpnSnapshot
import org.hellokittyvpn.android.vpn.isEligibleForAutomaticSelection
import org.hellokittyvpn.android.vpn.isAllowlistMobileServer
import org.hellokittyvpn.android.vpn.isMobileServer
import org.hellokittyvpn.android.vpn.isStandardMobileServer
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import org.hellokittyvpn.android.core.update.AppUpdateManager
import org.hellokittyvpn.android.core.update.AppUpdateDto
import org.hellokittyvpn.android.core.update.UpdateState


class AppViewModel(
    private val repository: AppRepository,
    private val vpnController: VpnController,
    private val settings: AppSettings,
    private val trafficHistoryStore: TrafficHistoryStore? = null,
    private val appContext: Context? = null,
    private val whitelistDetector: WhitelistDetector? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    private val effectChannel = Channel<AppEffect>(Channel.BUFFERED)
    private var connectionPending = false
    private var pendingWifiAutoConnect = false
    private val serverPingMutex = Mutex()
    private val appIconSelectionMutex = Mutex()
    private val perAppBaselineMutex = Mutex()
    @Volatile private var perAppTrafficBaseline: Map<Int, Pair<Long, Long>> = emptyMap()
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.tunnelProfile.collect { profile ->
                val selected = repository.selectedServerId()?.takeIf { id -> profile?.servers?.any { it.id == id } == true }
                    ?: profile?.servers?.firstOrNull()?.id
                mutableState.update { it.copy(profile = profile, selectedServerId = selected) }
            }
        }
        viewModelScope.launch {
            var previous: VpnConnectionState? = null
            vpnController.state.collect { vpn ->
                if (previous != VpnConnectionState.CONNECTED && vpn.state == VpnConnectionState.CONNECTED) capturePerAppTrafficBaseline()
                previous = vpn.state
                mutableState.update { it.copy(vpn = vpn, liveSpeedHistory = (it.liveSpeedHistory + SpeedSample(vpn.downloadBytesPerSecond, vpn.uploadBytesPerSecond)).takeLast(30)) }
            }
        }
        viewModelScope.launch {
            settings.routingPreset.collect { preset ->
                mutableState.update { it.copy(routingPreset = preset) }
            }
        }
        viewModelScope.launch {
            settings.bypassRussianTraffic.collect { enabled ->
                mutableState.update { it.copy(bypassRussianTraffic = enabled) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiEnabled.collect { enabled ->
                mutableState.update { it.copy(antiDpiEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiPreset.collect { preset ->
                mutableState.update { it.copy(antiDpiPreset = preset) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiPackets.collect { packets ->
                mutableState.update { it.copy(antiDpiPackets = packets) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiLength.collect { length ->
                mutableState.update { it.copy(antiDpiLength = length) }
            }
        }
        viewModelScope.launch {
            settings.antiDpiInterval.collect { interval ->
                mutableState.update { it.copy(antiDpiInterval = interval) }
            }
        }
        viewModelScope.launch {
            settings.autoHealingEnabled.collect { enabled ->
                mutableState.update { it.copy(autoHealingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.killSwitchEnabled.collect { enabled ->
                mutableState.update { it.copy(killSwitchEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.autoConnectUntrustedWifi.collect { enabled ->
                mutableState.update { it.copy(autoConnectUntrustedWifi = enabled) }
            }
        }
        viewModelScope.launch {
            settings.trustedWifiSsids.collect { ssids ->
                mutableState.update { it.copy(trustedWifiSsids = ssids) }
            }
        }
        viewModelScope.launch {
            settings.useDoh.collect { enabled ->
                mutableState.update { it.copy(useDoh = enabled) }
            }
        }
        viewModelScope.launch {
            settings.customDohUrl.collect { url ->
                mutableState.update { it.copy(customDohUrl = url) }
            }
        }
        viewModelScope.launch {
            settings.automaticServer.collect { enabled ->
                mutableState.update { it.copy(automaticServer = enabled) }
            }
        }
        viewModelScope.launch {
            settings.splitTunnelMode.collect { mode ->
                mutableState.update { it.copy(splitTunnelMode = mode) }
            }
        }
        viewModelScope.launch {
            settings.splitTunnelPackages.collect { pkgs ->
                mutableState.update { it.copy(splitTunnelPackages = pkgs) }
            }
        }
        viewModelScope.launch {
            settings.dnsProvider.collect { dns ->
                mutableState.update { it.copy(dnsProvider = dns) }
            }
        }
        viewModelScope.launch {
            settings.customDnsIpv4.collect { ip ->
                mutableState.update { it.copy(customDnsIpv4 = ip) }
            }
        }
        viewModelScope.launch {
            settings.appIcon.collect { icon ->
                mutableState.update { it.copy(appIcon = icon) }
            }
        }
        viewModelScope.launch {
            settings.themeMode.collect { theme ->
                mutableState.update { it.copy(themeMode = theme) }
            }
        }
        viewModelScope.launch {
            settings.useDynamicColors.collect { dynamic ->
                mutableState.update { it.copy(useDynamicColors = dynamic) }
            }
        }
        viewModelScope.launch {
            settings.autoConnectOnBoot.collect { autoBoot ->
                mutableState.update { it.copy(autoConnectOnBoot = autoBoot) }
            }
        }
        viewModelScope.launch {
            settings.autoFallbackServer.collect { fallback ->
                mutableState.update { it.copy(autoFallbackServer = fallback) }
            }
        }
        viewModelScope.launch {
            settings.favoriteServerIds.collect { favs ->
                mutableState.update { it.copy(favoriteServerIds = favs) }
            }
        }
        viewModelScope.launch {
            settings.customDirectDomains.collect { domains ->
                mutableState.update { it.copy(customDirectDomains = domains) }
            }
        }
        viewModelScope.launch {
            settings.customProxyDomains.collect { domains ->
                mutableState.update { it.copy(customProxyDomains = domains) }
            }
        }
        trafficHistoryStore?.let { store -> viewModelScope.launch { store.history.collect { history -> mutableState.update { it.copy(trafficHistory = history) } } } }
        viewModelScope.launch { repository.cachedTunnel() }
        viewModelScope.launch { serverPingLoop() }
    }
    fun acceptVpnDisclosure() {
        viewModelScope.launch {
            try {
                vpnController.acceptDisclosure()
                mutableState.update { it.copy(showVpnDisclosure = false) }
                if (connectionPending) {
                    effectChannel.send(AppEffect.RequestVpnPermission)
                }
            } catch (error: Throwable) {
                connectionPending = false
                mutableState.update {
                    it.copy(
                        showVpnDisclosure = false,
                        problem = error.toAppProblem(),
                    )
                }
            }
        }
    }

    fun declineVpnDisclosure() {
        connectionPending = false
        mutableState.update { it.copy(showVpnDisclosure = false) }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (!connectionPending) return
        if (!granted) {
            connectionPending = false
            mutableState.update { it.copy(message = UiMessage.VPN_PERMISSION_DENIED) }
            return
        }
        viewModelScope.launch { effectChannel.send(AppEffect.RequestNotificationPermission) }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!connectionPending) return
        connectionPending = false
        viewModelScope.launch {
            runCatching { vpnController.connect() }
                .onFailure { error ->
                    mutableState.update { it.copy(problem = error.toAppProblem()) }
                }
        }
    }

    fun setRoutingPreset(preset: RoutingPreset) {
        settings.setRoutingPreset(preset)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setBypassRussianTraffic(enabled: Boolean) {
        if (mutableState.value.vpn.state in setOf(
                VpnConnectionState.CONNECTING,
                VpnConnectionState.RECONNECTING,
                VpnConnectionState.STOPPING,
            )
        ) {
            return
        }
        runCatching { settings.setBypassRussianTraffic(enabled) }
            .onSuccess {
                if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
                    vpnController.reconfigure()
                }
            }
            .onFailure {
                mutableState.update { state -> state.copy(message = UiMessage.GENERIC_ERROR) }
            }
    }

    fun setAntiDpiPreset(preset: AntiDpiPreset) {
        settings.setAntiDpiPreset(preset)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setAntiDpiCustomParams(packets: String, length: String, interval: String) {
        settings.setAntiDpiCustomParams(packets, length, interval)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setAntiDpiEnabled(enabled: Boolean) {
        settings.setAntiDpiEnabled(enabled)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setAutoHealingEnabled(enabled: Boolean) {
        settings.setAutoHealingEnabled(enabled)
    }

    fun setKillSwitchEnabled(enabled: Boolean) {
        settings.setKillSwitchEnabled(enabled)
    }

    private fun requestWifiPermission() {
        pendingWifiAutoConnect = true
        viewModelScope.launch {
            effectChannel.send(AppEffect.RequestLocationPermission)
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!pendingWifiAutoConnect) return
        pendingWifiAutoConnect = false
        if (granted) {
            settings.setAutoConnectUntrustedWifi(true)
        } else {
            mutableState.update { it.copy(message = UiMessage.LOCATION_PERMISSION_DENIED) }
        }
    }

    fun addTrustedWifi(ssid: String) {
        settings.addTrustedWifiSsid(ssid)
    }

    fun removeTrustedWifi(ssid: String) {
        settings.removeTrustedWifiSsid(ssid)
    }

    fun setUseDoh(enabled: Boolean) {
        settings.setUseDoh(enabled)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setCustomDohUrl(url: String) {
        settings.setCustomDohUrl(url)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    private suspend fun selectServerForProfile(profile: PreparedTunnelProfile): String {
        if (settings.automaticServer.value) return selectBestServer(profile)
        val selected = repository.selectedServerId()
            ?.takeIf { id -> profile.servers.any { it.id == id } }
            ?: profile.servers.firstOrNull(TunnelServer::isEligibleForAutomaticSelection)?.id
            ?: error("No server is eligible for automatic selection")
        repository.selectServer(selected)
        viewModelScope.launch {
            runCatching {
                val measured = measureServers(profile.servers)
                mutableState.update { current ->
                    if (current.profile === profile) {
                        current.copy(
                            pingMs = current.selectedServerId?.let(measured::get),
                            serverPings = measured,
                            pingingServers = false,
                        )
                    } else current
                }
            }
        }
        return selected
    }

    private suspend fun selectBestServer(profile: PreparedTunnelProfile): String {
        val measured = measureServers(profile.servers)
        val eligibleServerIds = profile.servers
            .filter(TunnelServer::isEligibleForAutomaticSelection)
            .mapTo(mutableSetOf(), TunnelServer::id)
        require(eligibleServerIds.isNotEmpty()) {
            "No server is eligible for automatic selection"
        }
        val regularServerIds = profile.servers
            .filter { it.id in eligibleServerIds && !it.isMobileServer() }
            .mapTo(mutableSetOf(), TunnelServer::id)
        val preferredServerIds = regularServerIds.ifEmpty { eligibleServerIds }
        val selected = measured.entries
            .filter { it.key in preferredServerIds && it.value != null }
            .minByOrNull { requireNotNull(it.value) }
            ?.key
            ?: repository.selectedServerId()
                ?.takeIf(preferredServerIds::contains)
            ?: profile.servers.first { it.id in preferredServerIds }.id
        repository.selectServer(selected)
        mutableState.update { state ->
            state.copy(
                selectedServerId = selected,
                pingMs = measured[selected],
                serverPings = measured,
                pingingServers = false,
                message = if (measured.values.none { it != null }) {
                    UiMessage.SERVER_PING_UNAVAILABLE
                } else {
                    state.message
                },
            )
        }
        return selected
    }

    private suspend fun measureServers(servers: List<TunnelServer>): Map<String, Long?> =
        serverPingMutex.withLock {
            mutableState.update { it.copy(pingingServers = true) }
            supervisorScope {
                servers.map { server ->
                    async(Dispatchers.IO) {
                        server.id to ServerPinger.measure(server)
                    }
                }.awaitAll().toMap()
            }
        }

    private suspend fun serverPingLoop() {
        state.map { it.tab in setOf(AppTab.HOME, AppTab.SERVERS) }
            .distinctUntilChanged()
            .collectLatest { tabActive ->
                if (!tabActive) return@collectLatest
                while (true) {
                    val snapshot = mutableState.value
                    val profile = snapshot.profile
                    if (profile != null && !snapshot.refreshing) {
                        val measured = measureServers(profile.servers)
                        val eligibleServerIds = profile.servers
                            .filter(TunnelServer::isEligibleForAutomaticSelection)
                            .mapTo(mutableSetOf(), TunnelServer::id)
                        val regularServerIds = profile.servers
                            .filter { it.id in eligibleServerIds && !it.isMobileServer() }
                            .mapTo(mutableSetOf(), TunnelServer::id)
                        val preferredServerIds = regularServerIds.ifEmpty { eligibleServerIds }
                        val bestServerId = measured.entries
                            .filter { it.key in preferredServerIds && it.value != null }
                            .minByOrNull { requireNotNull(it.value) }
                            ?.key
                        if (snapshot.automaticServer &&
                            bestServerId != null &&
                            snapshot.vpn.state in PINGABLE_STATES
                        ) {
                            repository.selectServer(bestServerId)
                        }
                        mutableState.update { current ->
                            if (current.profile === profile) {
                                val selected = if (current.automaticServer && bestServerId != null &&
                                    current.vpn.state in PINGABLE_STATES
                                ) {
                                    bestServerId
                                } else {
                                    current.selectedServerId
                                }
                                current.copy(
                                    selectedServerId = selected,
                                    pingMs = selected?.let(measured::get),
                                    serverPings = measured,
                                    pingingServers = false,
                                )
                            } else {
                                current.copy(pingingServers = false)
                            }
                        }
                    }
                    delay(SERVER_PING_INTERVAL_MS)
                }
            }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        settings.setSplitTunnelMode(mode)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun toggleSplitTunnelPackage(packageName: String) {
        val current = settings.splitTunnelPackages.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        settings.setSplitTunnelPackages(current)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun importSplitTunnelPackages(text: String): Int {
        val current = settings.splitTunnelPackages.value
        val additions = SplitTunnelPackageList.parse(text) - current - BuildConfig.APPLICATION_ID
        if (additions.isEmpty()) return 0

        settings.setSplitTunnelPackages(current + additions)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
        return additions.size
    }

    fun setDnsProvider(provider: DnsProvider) {
        settings.setDnsProvider(provider)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setCustomDnsIpv4(ip: String) {
        settings.setCustomDnsIpv4(ip)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setAppIcon(icon: AppIcon) {
        viewModelScope.launch {
            appIconSelectionMutex.withLock {
                try {
                    withContext(Dispatchers.IO) { settings.setAppIcon(icon) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: RuntimeException) {
                    mutableState.update { it.copy(message = UiMessage.GENERIC_ERROR) }
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.setThemeMode(mode)
    }

    fun setUseDynamicColors(enabled: Boolean) {
        settings.setUseDynamicColors(enabled)
    }

    fun setAutoConnectOnBoot(enabled: Boolean) {
        settings.setAutoConnectOnBoot(enabled)
    }

    fun setAutoFallbackServer(enabled: Boolean) {
        settings.setAutoFallbackServer(enabled)
    }

    fun toggleFavoriteServer(serverId: String) {
        settings.toggleFavoriteServer(serverId)
    }

    fun addCustomDirectDomain(domain: String) {
        val trimmed = domain.trim().lowercase()
        if (trimmed.isNotBlank()) {
            val current = settings.customDirectDomains.value.toMutableSet()
            current.add(trimmed)
            settings.setCustomDirectDomains(current)
            if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
                vpnController.reconfigure()
            }
        }
    }

    fun removeCustomDirectDomain(domain: String) {
        val current = settings.customDirectDomains.value.toMutableSet()
        current.remove(domain)
        settings.setCustomDirectDomains(current)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun addCustomProxyDomain(domain: String) {
        val trimmed = domain.trim().lowercase()
        if (trimmed.isNotBlank()) {
            val current = settings.customProxyDomains.value.toMutableSet()
            current.add(trimmed)
            settings.setCustomProxyDomains(current)
            if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
                vpnController.reconfigure()
            }
        }
    }

    fun removeCustomProxyDomain(domain: String) {
        val current = settings.customProxyDomains.value.toMutableSet()
        current.remove(domain)
        settings.setCustomProxyDomains(current)
        if (mutableState.value.vpn.state == VpnConnectionState.CONNECTED) {
            vpnController.reconfigure()
        }
    }

    fun setServerSearchQuery(query: String) {
        mutableState.update { it.copy(serverSearchQuery = query) }
    }

    fun setServerFilter(filter: ServerFilterType) {
        mutableState.update { it.copy(serverFilter = filter) }
    }

    fun loadInstalledApps(packageManager: PackageManager) {
        if (!settings.hasInstalledAppsConsent()) return
        viewModelScope.launch(Dispatchers.IO) {
            // Include installed system packages and services without launcher activities.
            val applications = try {
                packageManager.getInstalledApplications(0)
            } catch (_: RuntimeException) {
                AppLogger.w("AppViewModel", "Unable to load installed applications")
                return@launch
            }
            val apps = applications.mapNotNull { info ->
                val pkg = info.packageName
                if (pkg == BuildConfig.APPLICATION_ID) return@mapNotNull null
                // A package can disappear while its label/icon is being loaded.
                val name = runCatching { info.loadLabel(packageManager).toString() }
                    .getOrDefault(pkg)
                val icon = runCatching { info.loadIcon(packageManager) }.getOrNull()
                InstalledAppItem(packageName = pkg, label = name, icon = icon)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
            mutableState.update { it.copy(installedApps = apps) }
        }
    }

    private suspend fun capturePerAppTrafficBaseline() {
        val baseline = perAppBaselineMutex.withLock {
            val snapshot = collectUidTraffic() ?: return@withLock emptyMap()
            snapshot
        }
        if (baseline.isNotEmpty()) {
            perAppTrafficBaseline = baseline
        }
    }

    /** uid -> (rx, tx) totals for every launcher-visible package. */

    private fun collectUidTraffic(): Map<Int, Pair<Long, Long>>? {
        if (!settings.hasInstalledAppsConsent()) return null
        val pm = appContext?.packageManager ?: return null
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            buildMap {
                for (info in resolveInfos) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == "org.hellokittyvpn.android") continue
                    val uid = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getPackageUid(pkg, PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getPackageUid(pkg, 0)
                        }
                    }.getOrNull() ?: continue
                    put(uid, Pair(
                        TrafficStats.getUidRxBytes(uid).coerceAtLeast(0),
                        TrafficStats.getUidTxBytes(uid).coerceAtLeast(0),
                    ))
                }
            }
        } catch (error: Throwable) {
            AppLogger.w("AppViewModel", "Failed to capture per-app baseline: ${error.message}")
            null
        }
    }

    fun loadPerAppTraffic(packageManager: PackageManager, context: Context) {
        if (!settings.hasInstalledAppsConsent()) return
        viewModelScope.launch(Dispatchers.IO) {
            val baseline = perAppTrafficBaseline
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            val allApps = resolveInfos.mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == "org.hellokittyvpn.android") return@mapNotNull null
                val uid = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageUid(pkg, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageUid(pkg, 0)
                    }
                }.getOrNull() ?: return@mapNotNull null

                val rxTotal = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                val txTotal = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                val base = baseline[uid]
                val rx = if (base != null) (rxTotal - base.first).coerceAtLeast(0) else rxTotal
                val tx = if (base != null) (txTotal - base.second).coerceAtLeast(0) else txTotal

                val name = info.loadLabel(packageManager).toString()
                val icon = info.loadIcon(packageManager)
                AppTrafficUsage(packageName = pkg, label = name, icon = icon, rxBytes = rx, txBytes = tx)
            }

            val listWithTraffic = allApps.filter { it.rxBytes > 0 || it.txBytes > 0 }
                .sortedByDescending { it.rxBytes + it.txBytes }
            val list = if (listWithTraffic.isNotEmpty()) {
                listWithTraffic
            } else {
                allApps.take(10)
            }
            mutableState.update { it.copy(perAppTraffic = list) }
        }
    }

    fun resetPerAppTrafficBaseline(packageManager: PackageManager, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            perAppTrafficBaseline = emptyMap()
            capturePerAppTrafficBaseline()
            mutableState.update { it.copy(perAppTraffic = emptyList()) }
        }
    }

    fun clearTrafficHistory() {
        trafficHistoryStore?.clearHistory()
        mutableState.update { it.copy(message = UiMessage.TRAFFIC_HISTORY_CLEARED) }
    }

    fun exportTrafficHistory(shareTitle: String) {
        val csv = trafficHistoryStore?.exportHistoryCsv() ?: return
        shareText(shareTitle, csv)
        mutableState.update { it.copy(message = UiMessage.TRAFFIC_HISTORY_EXPORTED) }
    }

    fun dismissDiagnostics() {
        mutableState.update { it.copy(diagnosticReport = null, runningDiagnostics = false) }
    }

    fun pauseVpn(minutes: Int) {
        vpnController.pause(minutes)
    }

    fun resumeVpn() {
        vpnController.resume()
    }

    fun hasInstalledAppsConsent(): Boolean = settings.hasInstalledAppsConsent()

    fun acceptInstalledAppsConsent() = settings.acceptInstalledAppsConsent()

    fun shareText(title: String, text: String) {
        viewModelScope.launch {
            effectChannel.send(AppEffect.ShareText(title = title, text = text))
        }
    }

    fun requestIgnoreBatteryOptimization() {
        viewModelScope.launch {
            effectChannel.send(AppEffect.RequestBatteryOptimization)
        }
    }

    fun getLogs(): List<LogEntry> = AppLogger.getLogs()

    fun getFormattedLogs(): String = AppLogger.getFormattedLogs()

    fun clearLogs() {
        AppLogger.clear()
    }
    fun selectTab(tab: AppTab) { mutableState.update { it.copy(tab = tab) } }
    fun importProfile(text: String) {
        if (state.value.refreshing || state.value.vpn.state !in PINGABLE_STATES) return
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true, problem = null) }
            try {
                val profile = repository.importProfile(text)
                selectServerForProfile(profile)
                mutableState.update { it.copy(tab = AppTab.SERVERS) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(problem = error.toAppProblem()) } }
            finally { mutableState.update { it.copy(refreshing = false) } }
        }
    }
    fun removeProfile() {
        if (state.value.vpn.state !in PINGABLE_STATES) return
        viewModelScope.launch { repository.clearProfile() }
    }
    fun connectOrDisconnect() {
        if (state.value.vpn.state in setOf(VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING, VpnConnectionState.RECONNECTING)) {
            vpnController.disconnect()
        } else prepareConnection()
    }
    private fun prepareConnection() {
        viewModelScope.launch {
            val profile = repository.cachedTunnel()
            if (profile == null || !cachedProfileIsUsable(profile.subscriptionExpiresAt, Instant.now())) {
                mutableState.update { it.copy(message = UiMessage.PROFILE_UNAVAILABLE) }; return@launch
            }
            connectionPending = true
            if (vpnController.hasDisclosureConsent()) effectChannel.send(AppEffect.RequestVpnPermission)
            else mutableState.update { it.copy(showVpnDisclosure = true) }
        }
    }
    fun selectServer(serverId: String) {
        if (state.value.profile?.servers?.none { it.id == serverId } != false) return
        viewModelScope.launch {
            settings.setAutomaticServer(false)
            repository.selectServer(serverId)
            mutableState.update { it.copy(selectedServerId = serverId) }
            if (state.value.vpn.state == VpnConnectionState.CONNECTED) vpnController.switchServer(serverId)
        }
    }
    fun selectAutomaticServer() {
        settings.setAutomaticServer(true)
        viewModelScope.launch { state.value.profile?.let { selectBestServer(it) } }
    }
    fun setAutoConnectUntrustedWifi(enabled: Boolean) {
        if (enabled) requestWifiPermission() else settings.setAutoConnectUntrustedWifi(false)
    }
    fun runDiagnostics() {
        if (state.value.runningDiagnostics) return
        viewModelScope.launch {
            mutableState.update { it.copy(runningDiagnostics = true) }
            try {
                val report = NetworkDiagnostics.runDiagnostics(vpnController.state.value)
                mutableState.update { it.copy(diagnosticReport = report) }
            } finally { mutableState.update { it.copy(runningDiagnostics = false) } }
        }
    }
    fun onAppForegrounded() { viewModelScope.launch { repository.cachedTunnel() } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    fun dismissProblem() { mutableState.update { it.copy(problem = null) } }
    fun shareDiagnosticReport() { state.value.diagnosticReport?.let { shareText("Hello Kitty VPN", it.toFormattedString()) } }
    companion object {
        private const val SERVER_PING_INTERVAL_MS = 30_000L
        private val PINGABLE_STATES = setOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR)
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AppViewModel::class.java))
                return AppViewModel(container.repository, container.vpnController, container.settings,
                    container.trafficHistoryStore, container.appContext, container.whitelistDetector) as T
            }
        }
    }


}

data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

data class AppTrafficUsage(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val rxBytes: Long,
    val txBytes: Long,
)

data class SpeedSample(
    val downloadBps: Long,
    val uploadBps: Long,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class ServerFilterType {
    ALL,
    REGULAR,
    MOBILE_ALLOWLIST,
    FAVORITES,
    FASTEST;

    fun matches(server: TunnelServer, favorites: Set<String>): Boolean = when (this) {
        ALL, FASTEST -> true
        REGULAR -> !server.isMobileServer()
        MOBILE_ALLOWLIST -> server.isMobileServer()
        FAVORITES -> server.id in favorites
    }
}



data class AppUiState(
    val profile: PreparedTunnelProfile? = null,
    val selectedServerId: String? = null,
    val vpn: VpnSnapshot = VpnSnapshot(),
    val pingMs: Long? = null,
    val serverPings: Map<String, Long?> = emptyMap(),
    val pingingServers: Boolean = false,
    val tab: AppTab = AppTab.HOME,
    val refreshing: Boolean = false,
    val showVpnDisclosure: Boolean = false,
    val whitelistMode: WhitelistMode = WhitelistMode.UNKNOWN,
    val routingPreset: RoutingPreset = RoutingPreset.BYPASS_RU,
    val bypassRussianTraffic: Boolean = true,
    val antiDpiPreset: AntiDpiPreset = AntiDpiPreset.OFF,
    val antiDpiPackets: String = "tlshello",
    val antiDpiLength: String = "100-200",
    val antiDpiInterval: String = "10-20",
    val antiDpiEnabled: Boolean = false,
    val autoHealingEnabled: Boolean = true,
    val killSwitchEnabled: Boolean = false,
    val autoConnectUntrustedWifi: Boolean = false,
    val trustedWifiSsids: Set<String> = emptySet(),
    val useDoh: Boolean = true,
    val customDohUrl: String = "",
    val automaticServer: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val splitTunnelPackages: Set<String> = emptySet(),
    val installedApps: List<InstalledAppItem> = emptyList(),
    val perAppTraffic: List<AppTrafficUsage> = emptyList(),
    val liveSpeedHistory: List<SpeedSample> = emptyList(),
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val customDnsIpv4: String = "1.1.1.1",
    val appIcon: AppIcon = AppIcon.LIGHT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColors: Boolean = false,
    val autoConnectOnBoot: Boolean = false,
    val autoFallbackServer: Boolean = true,
    val favoriteServerIds: Set<String> = emptySet(),
    val customDirectDomains: Set<String> = emptySet(),
    val customProxyDomains: Set<String> = emptySet(),
    val serverSearchQuery: String = "",
    val serverFilter: ServerFilterType = ServerFilterType.ALL,
    val trafficHistory: List<DailyTraffic> = emptyList(),
    val diagnosticReport: DiagnosticReport? = null,
    val runningDiagnostics: Boolean = false,
    val message: UiMessage? = null,
    val problem: AppProblem? = null,
)

internal fun displayedServerId(state: AppUiState): String? =
    state.vpn.serverId.takeIf {
        state.vpn.state !in setOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR)
    } ?: state.selectedServerId

enum class AppTab {
    HOME,
    SERVERS,
    STATS,
    PROFILE,
}


enum class UiMessage(val resource: Int) {
    GENERIC_ERROR(org.hellokittyvpn.android.R.string.problem_unknown_body),
    PROFILE_UNAVAILABLE(org.hellokittyvpn.android.R.string.local_profile_missing),
    VPN_PERMISSION_DENIED(org.hellokittyvpn.android.R.string.problem_permission_body),
    NOTIFICATION_PERMISSION_DENIED(org.hellokittyvpn.android.R.string.problem_notifications_body),
    LOCATION_PERMISSION_DENIED(org.hellokittyvpn.android.R.string.problem_location_body),
    SERVER_PING_UNAVAILABLE(org.hellokittyvpn.android.R.string.server_ping_unavailable),
    TRAFFIC_HISTORY_CLEARED(org.hellokittyvpn.android.R.string.traffic_history_cleared),
    TRAFFIC_HISTORY_EXPORTED(org.hellokittyvpn.android.R.string.traffic_history_exported),
}
sealed interface AppEffect {
    data class ShareText(val title: String, val text: String) : AppEffect
    data object RequestBatteryOptimization : AppEffect
    data object RequestVpnPermission : AppEffect
    data object RequestNotificationPermission : AppEffect
    data object RequestLocationPermission : AppEffect
}
internal fun cachedProfileIsUsable(expiry: String?, now: Instant): Boolean = expiry?.let {
    runCatching { Instant.parse(it).isAfter(now) }.getOrDefault(false)
} ?: true
