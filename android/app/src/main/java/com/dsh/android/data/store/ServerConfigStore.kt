package com.dsh.android.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dsh.android.data.protocol.Wire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    /** Gateway base URL, e.g. http://192.168.1.10:8742 (no trailing slash). */
    val baseUrl: String,
    /** Bearer token configured on the PC gateway. */
    val token: String,
    /**
     * Safety toggle: whether this phone may answer the harness's
     * approval/question frames (sandbox allow-once / reject, question answers).
     * Defaults to OFF — those frames are still displayed either way.
     */
    val allowRemoteAnswers: Boolean = false,
)

/**
 * Persists the server registry (which contains the gateway bearer token — the
 * credential that grants remote code execution on the PC) encrypted at rest.
 *
 * The token is protected by the Android Keystore (AES-256-GCM master key in
 * hardware-backed storage where available, AES-256-SIV key names + GCM values
 * inside Jetpack security-crypto), so an extracted file backup or a rooted
 * filesystem read no longer yields plaintext tokens. Android auto-backup is
 * additionally disabled in the manifest (see docs/SECURITY-REVIEW.md).
 */
class ServerConfigStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "dsh_servers_secure",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val serversKey = "servers"
    private val activeKey = "active_server_id"

    private val _servers = MutableStateFlow(load())
    val servers: Flow<List<ServerConfig>> = _servers.asStateFlow()

    private val _activeServerId = MutableStateFlow(prefs.getString(activeKey, null))
    val activeServerId: Flow<String?> = _activeServerId.asStateFlow()

    private fun load(): List<ServerConfig> {
        val raw = prefs.getString(serversKey, null) ?: return emptyList()
        return runCatching {
            Wire.json.decodeFromString(ListSerializer(ServerConfig.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun upsert(server: ServerConfig) {
        val current = _servers.value.toMutableList()
        val idx = current.indexOfFirst { it.id == server.id }
        if (idx >= 0) current[idx] = server else current += server
        persist(current)
    }

    fun remove(id: String) {
        val current = _servers.value.filterNot { it.id == id }
        persist(current)
        if (_activeServerId.value == id) {
            prefs.edit().remove(activeKey).apply()
            _activeServerId.value = null
        }
    }

    fun setActive(id: String) {
        prefs.edit().putString(activeKey, id).apply()
        _activeServerId.value = id
    }

    private fun persist(servers: List<ServerConfig>) {
        val json = Wire.json.encodeToString(ListSerializer(ServerConfig.serializer()), servers)
        prefs.edit().putString(serversKey, json).apply()
        _servers.value = servers
    }
}
