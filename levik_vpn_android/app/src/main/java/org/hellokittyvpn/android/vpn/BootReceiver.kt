package org.hellokittyvpn.android.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.hellokittyvpn.android.HelloKittyVpnApplication

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext as? HelloKittyVpnApplication ?: return
            val container = app.container

            if (container.settings.autoConnectOnBoot.value) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val cached = container.repository.cachedTunnel()
                        val isExpired = cached?.subscriptionExpiresAt?.let { value ->
                            runCatching { java.time.Instant.parse(value).isBefore(java.time.Instant.now()) }.getOrDefault(false)
                        } ?: false

                        if (cached != null && !isExpired &&
                            container.vpnController.hasDisclosureConsent() &&
                            container.vpnController.permissionIntent() == null
                        ) {
                            runCatching { container.vpnController.connect() }
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
