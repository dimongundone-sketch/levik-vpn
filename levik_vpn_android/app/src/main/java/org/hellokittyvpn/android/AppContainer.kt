package org.hellokittyvpn.android

import android.app.Application
import org.hellokittyvpn.android.core.network.WhitelistDetector
import org.hellokittyvpn.android.core.security.SecureFileStore
import org.hellokittyvpn.android.core.security.DeviceIdentity
import org.hellokittyvpn.android.data.AppRepository
import org.hellokittyvpn.android.data.AppSettings
import org.hellokittyvpn.android.data.TrafficHistoryStore
import org.hellokittyvpn.android.vpn.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

class AppContainer(application: Application) {
    val appContext = application.applicationContext
    val nativeCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }
    val updateManager = org.hellokittyvpn.android.core.update.DisabledAppUpdateManager()
    val deviceIdentity = DeviceIdentity()
    val secureStore = SecureFileStore(application)
    val settings = AppSettings(application)
    val russianRoutingData = RussianRoutingData(application)
    val lteRoutingData = LteRoutingData(application)
    val xrayRuntime = XrayRuntime(json)
    val tunnelEngineRegistry = createTunnelEngineRegistry(xrayRuntime, application.applicationInfo.nativeLibraryDir.orEmpty(), application)
    val whitelistDetector = WhitelistDetector(application)
    val repository = AppRepository(secureStore, TunnelProfilePreparer(xrayRuntime), json)
    val trafficHistoryStore = TrafficHistoryStore(application, json, nativeCleanupScope)
    val vpnController = VpnController(application, secureStore)
    private val wifiMonitor = WifiAutoConnectMonitor(application, settings, vpnController, nativeCleanupScope)
    init { wifiMonitor.start() }
}
