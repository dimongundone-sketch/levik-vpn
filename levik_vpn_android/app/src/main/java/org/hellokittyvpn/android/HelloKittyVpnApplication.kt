package org.hellokittyvpn.android

import android.app.Application

class HelloKittyVpnApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Eagerly initialize the container so the Wi-Fi auto-connect monitor
        // runs even before the first activity.
        container
    }
}
