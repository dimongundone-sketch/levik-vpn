package org.hellokittyvpn.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import org.hellokittyvpn.android.core.update.consumeUpdateNotificationIntent
import org.hellokittyvpn.android.core.logger.AppLogger
import org.hellokittyvpn.android.core.notification.AppIconArtwork
import org.hellokittyvpn.android.data.AppIcon
import org.hellokittyvpn.android.ui.AppEffect
import org.hellokittyvpn.android.ui.AppViewModel
import org.hellokittyvpn.android.ui.HelloKittyVpnApp
import org.hellokittyvpn.android.ui.theme.KittyTheme
import org.hellokittyvpn.android.ui.theme.KittyAppScale
import kotlinx.coroutines.launch

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// This app uses ComponentActivity directly and has no FragmentActivity or Fragment dependency.
@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as HelloKittyVpnApplication).container
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory(container)
    }
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onLocationPermissionResult(granted)
    }
    private val playLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onLocationPermissionResult(grants[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            KittyAppScale {
                KittyTheme(
                    themeMode = state.themeMode,
                ) {
                    HelloKittyVpnApp(viewModel)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.effects.collect(::handleEffect) }
                launch { container.settings.appIcon.collect(::updateTaskIcon) }
            }
        }
        intent?.let(::handleLaunchIntent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent) {
        if (consumeUpdateNotificationIntent(intent)) {
            viewModel.onAppForegrounded()
        } else {
            // Foreign account activation links are intentionally not accepted.
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForegrounded()
    }

    @Suppress("DEPRECATION") // Bitmap constructor is required on Android 8/8.1.
    private fun updateTaskIcon(icon: AppIcon) {
        runCatching {
            val label = getString(R.string.app_name)
            val description = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(label)
                        .setIcon(icon.launcherResource)
                        .build()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    ActivityManager.TaskDescription(label, icon.launcherResource)
                else -> ActivityManager.TaskDescription(label, AppIconArtwork.largeIcon(this, icon))
            }
            setTaskDescription(description)
        }.onFailure { AppLogger.w("MainActivity", "Could not refresh task icon") }
    }

    private fun handleEffect(effect: AppEffect) {
        when (effect) {
            is AppEffect.ShareText -> {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, effect.text)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, effect.title)
                startActivity(shareIntent)
            }
            AppEffect.RequestBatteryOptimization -> openDistributionBatterySettings()
            AppEffect.RequestVpnPermission -> {
                val permissionIntent = container.vpnController.permissionIntent()
                if (permissionIntent == null) {
                    viewModel.onVpnPermissionResult(granted = true)
                } else {
                    vpnPermissionLauncher.launch(permissionIntent)
                }
            }
            AppEffect.RequestNotificationPermission -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.onNotificationPermissionResult(granted = true)
                }
            }
            AppEffect.RequestLocationPermission -> {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.onLocationPermissionResult(granted = true)
                } else if (BuildConfig.IS_PLAY_DISTRIBUTION) {
                    // Android 12+ requires coarse and fine to be requested together.
                    playLocationPermissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ))
                } else {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
    }

}
