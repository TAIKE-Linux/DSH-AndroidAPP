package com.dsh.android.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.dsh.android.data.protocol.Wire
import java.net.Inet4Address
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class LanScanResult(
    /** Gateway base URL, e.g. https://192.168.1.10:8742 */
    val baseUrl: String,
    val version: String,
    val latencyMs: Long,
    /** Advertised public/WAN base URL when the gateway is configured for remote access. */
    val publicUrl: String? = null,
)

data class LanScanState(
    val active: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val results: List<LanScanResult> = emptyList(),
    val error: String? = null,
)

/**
 * LAN discovery for dsh-remote-gateway.
 *
 * Security posture (reviewed, see docs/SECURITY-REVIEW.md):
 *  - User-triggered only; never runs in the background.
 *  - Sends one unauthenticated GET /ident per candidate IP:port. The endpoint
 *    returns a static minimal JSON ({name, version}) — no upstream/cwd/model
 *    data, no token is ever sent, no state change on the gateway.
 *  - Identification is exact: response must be JSON with
 *    name == "dsh-remote-gateway", so other HTTP services are never matched.
 *  - Bounded resources: max 64 concurrent probes, 300ms connect / 500ms read
 *    timeouts, cancellable; redirects disabled.
 *  - Requires no new Android permissions (INTERNET/ACCESS_NETWORK_STATE are
 *    already declared); local-subnet discovery needs no location permission.
 */
class LanScanner(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val http = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        // /ident is unauthenticated public discovery info. The gateway serves
        // HTTPS with a self-signed certificate by default, so the probe accepts
        // any cert; the real client still validates (or user-opts into trust).
        .apply { trustAllCertificates(this) }
        .build()

    private val _state = MutableStateFlow(LanScanState())
    val state: StateFlow<LanScanState> = _state.asStateFlow()

    private var job: Job? = null

    fun startScan(scope: CoroutineScope, ports: List<Int> = DEFAULT_PORTS) {
        if (_state.value.active) return
        job?.cancel()
        job = scope.launch(Dispatchers.IO) { runScan(ports) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(active = false) }
    }

    /**
     * Enumerate the /24 block containing the phone's IPv4 address.
     * Home LANs are almost always /24; for wider prefixes we still scan the
     * phone's own /24 (the PC must be reachable there for a gateway to make
     * sense). Returns null when no usable WiFi IPv4 exists.
     */
    private fun subnetTargets(): List<String>? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            // Cellular/VPN/Ethernet-unknown: don't spray probes at a WAN.
            return null
        }
        val linkProperties: LinkProperties = connectivity.getLinkProperties(network) ?: return null
        val link = linkProperties.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return null
        val bytes = (link.address as Inet4Address).address
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return (1..254).map { "$a.$b.$c.$it" }
    }

    private suspend fun runScan(ports: List<Int>) {
        val hosts = subnetTargets()
        if (hosts == null) {
            _state.update { it.copy(error = "无法获取局域网信息：请确认手机已连接 WiFi 且与电脑同网段") }
            return
        }
        val targets = hosts.flatMap { host -> ports.map { port -> "$host:$port" } }
        val total = targets.size
        _state.update { LanScanState(active = true, scanned = 0, total = total) }

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val counter = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<LanScanResult>())

        coroutineScope {
            for (target in targets) {
                launch {
                    semaphore.withPermit {
                        probe(target)?.let { results += it }
                        val done = counter.incrementAndGet()
                        if (done % 8 == 0 || done == total) {
                            _state.update {
                                it.copy(scanned = done, results = results.sortedBy { r -> r.latencyMs })
                            }
                        }
                    }
                }
            }
        }
        _state.update { it.copy(active = false, scanned = total, results = results.sortedBy { r -> r.latencyMs }) }
    }

    private fun probe(target: String): LanScanResult? {
        // Probe both schemes: the gateway defaults to HTTPS (self-signed) but
        // still supports plain HTTP for users who disable TLS.
        val start = System.currentTimeMillis()
        for (scheme in listOf("https", "http")) {
            probeOnce("$scheme://$target/ident", start)?.let { return it }
        }
        return null
    }

    private fun probeOnce(url: String, start: Long): LanScanResult? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "dsh-android-scanner/0.1")
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.string() ?: return null
                val obj = runCatching { Wire.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
                val name = obj["gateway"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                if (name != GATEWAY_NAME) return null
                val scheme = obj["scheme"]?.jsonPrimitive?.contentOrNull ?: "http"
                val host = url.substringAfter("://").substringBefore("/ident")
                LanScanResult(
                    baseUrl = "$scheme://$host",
                    version = obj["gateway"]?.jsonObject?.get("version")?.jsonPrimitive?.contentOrNull ?: "",
                    latencyMs = System.currentTimeMillis() - start,
                    publicUrl = obj["public"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
                )
            }
        } catch (_: Exception) {
            null // Unreachable hosts are the expected case; never surface probe errors.
        }
    }

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

    companion object {
        const val GATEWAY_NAME = "dsh-remote-gateway"
        /** Scanned ports: the gateway default plus common neighbouring customs. */
        val DEFAULT_PORTS = listOf(8742, 8743, 8744)
        private const val MAX_CONCURRENCY = 64
    }
}
