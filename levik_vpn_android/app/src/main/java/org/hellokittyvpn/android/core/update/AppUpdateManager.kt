package org.hellokittyvpn.android.core.update

import java.io.File
import kotlinx.coroutines.flow.StateFlow

data class AppUpdateDto(
    val packageName: String,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val downloadUrl: String,
    val apkSize: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val titleRu: String? = null,
    val titleEn: String? = null,
    val changelogRu: String? = null,
    val changelogEn: String? = null,
    val forceUpdate: Boolean = false,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: AppUpdateDto) : UpdateState
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateState
    data class ReadyToInstall(val apkFile: File, val info: AppUpdateDto) : UpdateState
    data object UpToDate : UpdateState
    data class Error(val message: String) : UpdateState
}

interface AppUpdateManager {
    val state: StateFlow<UpdateState>

    suspend fun checkForUpdates(silent: Boolean = false): AppUpdateDto?

    /** Verifies update metadata without changing the foreground dialog or download state. */
    suspend fun checkForUpdatesInBackground(): AppUpdateDto? = null

    suspend fun downloadAndInstall(update: AppUpdateDto)

    fun dismiss()
}

class DisabledAppUpdateManager : AppUpdateManager {
    override val state = kotlinx.coroutines.flow.MutableStateFlow<UpdateState>(UpdateState.Idle)
    override suspend fun checkForUpdates(silent: Boolean): AppUpdateDto? = null
    override suspend fun downloadAndInstall(update: AppUpdateDto) { error("Update channel is not configured") }
    override fun dismiss() {}
}
