package org.hellokittyvpn.android.core.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Suppress("UNUSED_PARAMETER")
internal fun createAppUpdateManager(context: Context): AppUpdateManager = DisabledAppUpdateManager()

@Suppress("UNUSED_PARAMETER")
internal fun scheduleBackgroundUpdateChecks(context: Context) = Unit

@Suppress("UNUSED_PARAMETER")
internal fun consumeUpdateNotificationIntent(intent: android.content.Intent): Boolean = false
