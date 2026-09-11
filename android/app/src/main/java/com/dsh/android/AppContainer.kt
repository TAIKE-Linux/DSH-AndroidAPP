package com.dsh.android

import android.app.Application
import android.net.Uri
import com.dsh.android.data.remote.ConnectionManager
import com.dsh.android.data.remote.DshApiClient
import com.dsh.android.data.store.BackgroundStore
import com.dsh.android.data.store.ServerConfig
import com.dsh.android.data.store.ServerConfigStore
import com.dsh.android.data.store.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manual DI container. One Binding = one connected DSH server (gateway),
 * holding its API client, downlink connection manager, and session store.
 */
class AppContainer(private val app: Application) {

    val serverStore = ServerConfigStore(app)
    val backgroundStore = BackgroundStore(app)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Shared clients for normal HTTP/HTTPS (public CA) targets. Self-signed
     * gateways get per-binding clients built in [buildClients] so the trust-all
     * policy is scoped to exactly the server the user opted into.
     */
    private val httpClient: OkHttpClient = baseClientBuilder()
        .build()

    private val rpcClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val uploadClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    data class Binding(
        val config: ServerConfig,
        val api: DshApiClient,
        val connection: ConnectionManager,
        val store: SessionStore,
    )

    private val _active = MutableStateFlow<Binding?>(null)
    val active: StateFlow<Binding?> = _active.asStateFlow()

    /** Deep link received from an external QR scan; consumed once by AppRoot. */
    private val _pendingDeepLink = MutableStateFlow<String?>(null)
    val pendingDeepLink: StateFlow<String?> = _pendingDeepLink.asStateFlow()

    fun setPendingDeepLink(raw: String?) {
        _pendingDeepLink.value = raw
    }

    /** Bind to a gateway: builds the API client, connection manager and store, then starts them. */
    fun bind(server: ServerConfig) {
        unbind()
        val (http, rpc, upload) = clientsFor(server)
        val api = DshApiClient(server.baseUrl.trimEnd('/'), server.token, rpc, upload)
        val connection = ConnectionManager(api, http)
        val store = SessionStore(api, connection, appScope)
        val binding = Binding(server, api, connection, store)
        _active.value = binding
        store.start()
        connection.start(appScope)
    }

    fun unbind() {
        val binding = _active.value ?: return
        binding.connection.stop()
        binding.store.stop()
        _active.value = null
    }

    /**
     * Parse a `dsh-gateway://connect?u=<baseUrl>&t=<token>` deep link (from the
     * PC panel's QR code) into a [ServerConfig]. HTTPS self-signed trust is
     * enabled automatically so the gateway's default certificate works out of
     * the box. The caller decides when to persist/bind (so it can navigate).
     */
    fun parseDeepLink(uri: Uri): ServerConfig? {
        val baseUrl = uri.getQueryParameter("u")?.trim()?.trimEnd('/') ?: return null
        val token = uri.getQueryParameter("t")?.trim() ?: return null
        if (!baseUrl.startsWith("http://", true) && !baseUrl.startsWith("https://", true)) return null
        if (token.isBlank()) return null
        return ServerConfig(
            id = java.util.UUID.randomUUID().toString(),
            name = baseUrl,
            baseUrl = baseUrl,
            token = token,
            allowRemoteAnswers = false,
            allowSelfSigned = baseUrl.startsWith("https://", true),
        )
    }

    // -- OkHttp client construction ------------------------------------------

    private fun baseClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        // LAN targets either answer fast or are gone: a short connect timeout
        // means quick failure feedback and a faster reconnect cycle.
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)

    /** (http, rpc, upload) for one server; trust-all only when opted into self-signed HTTPS. */
    private fun clientsFor(server: ServerConfig): Triple<OkHttpClient, OkHttpClient, OkHttpClient> {
        val selfSigned = server.allowSelfSigned && server.baseUrl.startsWith("https://", ignoreCase = true)
        if (!selfSigned) return Triple(httpClient, rpcClient, uploadClient)
        val base = baseClientBuilder().apply { trustAllCertificates(this) }.build()
        val rpc = base.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
        val upload = base.newBuilder().callTimeout(10, TimeUnit.MINUTES).build()
        return Triple(base, rpc, upload)
    }

    /** Accept any server certificate and hostname for a user-approved self-signed gateway. */
    private fun trustAllCertificates(builder: OkHttpClient.Builder) {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        builder.sslSocketFactory(context.socketFactory, trustAll)
        builder.hostnameVerifier { _, _ -> true }
    }
}
