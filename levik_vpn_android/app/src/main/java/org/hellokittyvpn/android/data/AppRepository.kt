package org.hellokittyvpn.android.data

import org.hellokittyvpn.android.core.security.SecureFileStore
import org.hellokittyvpn.android.vpn.PreparedTunnelProfile
import org.hellokittyvpn.android.vpn.TunnelProfilePreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Free local profiles until the independent Hello Kitty backend is provisioned. */
class AppRepository(
    private val secureStore: SecureFileStore,
    private val tunnelProfilePreparer: TunnelProfilePreparer,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val mutableProfile = MutableStateFlow<PreparedTunnelProfile?>(null)
    val tunnelProfile = mutableProfile.asStateFlow()
    suspend fun importProfile(text: String): PreparedTunnelProfile = mutex.withLock {
        val profile = withContext(Dispatchers.Default) {
            tunnelProfilePreparer.prepare(LocalProfileInput(json).parse(text))
        }
        val bytes = json.encodeToString(profile).encodeToByteArray()
        try {
            withContext(Dispatchers.IO) {
                secureStore.put(SecureFileStore.TUNNEL_PROFILE, bytes)
                secureStore.put(SecureFileStore.SELECTED_SERVER, profile.servers.first().id.encodeToByteArray())
            }
        } finally { bytes.fill(0) }
        mutableProfile.value = profile
        profile
    }
    suspend fun cachedTunnel(): PreparedTunnelProfile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            var bytes: ByteArray? = null
            try {
                bytes = secureStore.get(SecureFileStore.TUNNEL_PROFILE)
                val profile = bytes?.let { json.decodeFromString<PreparedTunnelProfile>(it.decodeToString()) }
                mutableProfile.value = profile
                profile
            } catch (_: Exception) {
                secureStore.remove(SecureFileStore.TUNNEL_PROFILE)
                mutableProfile.value = null
                null
            } finally { bytes?.fill(0) }
        }
    }
    suspend fun selectedServerId(): String? = withContext(Dispatchers.IO) {
        val bytes = secureStore.get(SecureFileStore.SELECTED_SERVER)
        try { bytes?.decodeToString() } finally { bytes?.fill(0) }
    }
    suspend fun selectServer(serverId: String) = mutex.withLock {
        require(mutableProfile.value?.servers?.any { it.id == serverId } == true)
        withContext(Dispatchers.IO) { secureStore.put(SecureFileStore.SELECTED_SERVER, serverId.encodeToByteArray()) }
    }
    suspend fun clearProfile() = mutex.withLock {
        withContext(Dispatchers.IO) {
            secureStore.remove(SecureFileStore.TUNNEL_PROFILE)
            secureStore.remove(SecureFileStore.SELECTED_SERVER)
        }
        mutableProfile.value = null
    }
}
