package me.unknkriod.kapclient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.unknkriod.kapclient.MainActivity
import me.unknkriod.kapclient.R
import me.unknkriod.kapclient.data.KapDatabase
import me.unknkriod.kapclient.data.SecurityManager
import me.unknkriod.kapclient.data.VpnConfig
import me.unknkriod.kapclient.native.KapCore
import me.unknkriod.kapclient.util.VpnState
import me.unknkriod.kapclient.util.VpnStatus
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class KapVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunInterface: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var connectivityJob: Job? = null
    private var activeEndpoints: List<VpnConfig> = emptyList()
    private var currentConfigId: Long = -1
    private var lastNetwork: Network? = null
    private val vpnLock = Mutex()

    private val database by lazy { KapDatabase.getDatabase(this) }
    private val securityManager by lazy { SecurityManager(this) }

    private val baseClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectionPool(okhttpPool)
            .dispatcher(okhttpDispatcher)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val proxyClient by lazy {
        baseClient.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080)))
            .build()
    }

    companion object {
        private const val TAG = "KapVpnService"
        private const val CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 1
        
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"
        const val EXTRA_CONFIG_ID = "CONFIG_ID"

        private val okhttpPool = okhttp3.ConnectionPool()
        private val okhttpDispatcher = okhttp3.Dispatcher()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = getSystemService(ConnectivityManager::class.java)
            val capabilities = cm.getNetworkCapabilities(network) ?: return
            
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

            Log.i(TAG, "Network available: $network, transport: ${getTransportName(capabilities)}")
            
            val isSwitch = lastNetwork != null && lastNetwork != network
            lastNetwork = network
            
            if (VpnState.status.value == VpnStatus.CONNECTED) {
                Log.i(TAG, "Network switched, checking connectivity...")
                serviceScope.launch {
                    val optimized = checkAndOptimizeConnection()
                    if (isSwitch && !optimized && VpnState.status.value == VpnStatus.CONNECTED) {
                        restartVpn()
                    }
                }
            }
        }
        
        override fun onLost(network: Network) {
            Log.i(TAG, "Network lost: $network")
        }
    }

    private fun getTransportName(capabilities: NetworkCapabilities): String {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    private fun restartVpn(newConfigId: Long? = null) {
        val configId = newConfigId ?: currentConfigId
        if (configId == -1L) return
        
        serviceScope.launch {
            vpnLock.withLock {
                Log.i(TAG, "Restarting VPN (configId=$configId)...")
                VpnState.updateStatus(VpnStatus.CONNECTING)
                try {
                    stopVpnInternal(isRestarting = true)
                    delay(1000)
                    runVpnTaskLocked(configId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in restartVpn", e)
                    VpnState.updateStatus(VpnStatus.DISCONNECTED)
                    updateNotification("Connection failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun checkDirectConnectivity(): Boolean = withContext(Dispatchers.IO) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val networks = cm.allNetworks
        val physicalNetwork = networks.find { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: cm.activeNetwork ?: return@withContext false

        try {
            val factory = physicalNetwork.socketFactory
            val client = baseClient.newBuilder()
                .socketFactory(object : javax.net.SocketFactory() {
                    override fun createSocket() = factory.createSocket().also { protect(it) }
                    override fun createSocket(h: String?, p: Int) = factory.createSocket(h, p).also { protect(it) }
                    override fun createSocket(h: String?, p: Int, lh: java.net.InetAddress?, lp: Int) = factory.createSocket(h, p, lh, lp).also { protect(it) }
                    override fun createSocket(a: java.net.InetAddress?, p: Int) = factory.createSocket(a, p).also { protect(it) }
                    override fun createSocket(a: java.net.InetAddress?, p: Int, la: java.net.InetAddress?, lp: Int) = factory.createSocket(a, p, la, lp).also { protect(it) }
                })
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://ifconfig.me/ip")
                .header("User-Agent", "curl/7.68.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim() ?: ""
                body.isNotEmpty()
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun checkAndOptimizeConnection(): Boolean {
        if (VpnState.status.value != VpnStatus.CONNECTED) return false
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("wifi_auto_switch", true)) return false

        val directOk = checkDirectConnectivity()
        val config = database.vpnConfigDao().getById(currentConfigId) ?: return false

        val preferredDirectIds = prefs.getString("preferred_direct_config_ids", "")?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        val preferredCdnIds = prefs.getString("preferred_cdn_config_ids", "")?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

        if (directOk && config.behindCdn) {
            val targetIds = if (preferredDirectIds.isNotEmpty()) {
                preferredDirectIds
            } else {
                val subId = config.subscriptionId
                if (subId != null) {
                    database.vpnConfigDao().getNonCdnBySubscriptionId(subId).map { it.id }.toSet()
                } else emptySet()
            }

            if (targetIds.isNotEmpty() && !targetIds.contains(currentConfigId)) {
                VpnState.updateStatus(VpnStatus.OPTIMIZING)
                updatePoolInDb(database, targetIds)
                restartVpn(targetIds.first())
                return true
            }
        } else if (!directOk && !config.behindCdn) {
            val targetIds = if (preferredCdnIds.isNotEmpty()) {
                preferredCdnIds
            } else {
                val subId = config.subscriptionId
                if (subId != null) {
                    database.vpnConfigDao().getInPoolBySubscriptionId(subId).filter { it.behindCdn }.map { it.id }.toSet()
                } else emptySet()
            }

            if (targetIds.isNotEmpty() && !targetIds.contains(currentConfigId)) {
                VpnState.updateStatus(VpnStatus.OPTIMIZING)
                updatePoolInDb(database, targetIds)
                restartVpn(targetIds.first())
                return true
            }
        }
        return false
    }

    private suspend fun updatePoolInDb(db: KapDatabase, targetIds: Set<Long>) {
        val allConfigs = db.vpnConfigDao().getAllConfigs()
        allConfigs.forEach { cfg ->
            val shouldBeInPool = targetIds.contains(cfg.id)
            if (cfg.inPool != shouldBeInPool) {
                db.vpnConfigDao().update(cfg.copy(inPool = shouldBeInPool))
            }
        }
    }

    private fun startConnectivityJob() {
        connectivityJob?.cancel()
        connectivityJob = serviceScope.launch {
            while (isActive) {
                delay(60 * 1000)
                checkAndOptimizeConnection()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configId = intent.getLongExtra(EXTRA_CONFIG_ID, -1)
                if (configId != -1L) startVpn(configId)
            }
            ACTION_STOP -> stopVpn()
            ACTION_PAUSE -> pauseVpn()
            ACTION_RESUME -> {
                if (currentConfigId != -1L) startVpn(currentConfigId)
            }
        }
        return START_STICKY
    }

    private fun startVpn(configId: Long) {
        serviceScope.launch {
            vpnLock.withLock {
                if (VpnState.status.value == VpnStatus.CONNECTED || VpnState.status.value == VpnStatus.CONNECTING) {
                    if (currentConfigId == configId) return@withLock
                    VpnState.updateStatus(VpnStatus.CONNECTING)
                    stopVpnInternal(isRestarting = true)
                    delay(500)
                }
                try {
                    runVpnTaskLocked(configId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in startVpn", e)
                    VpnState.updateStatus(VpnStatus.DISCONNECTED)
                    updateNotification("Connection failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun runVpnTaskLocked(configId: Long) {
        try {
            if (!KapCore.isLoaded) throw Exception("KapCore not loaded")

            currentConfigId = configId
            VpnState.updateActiveConfigId(configId)
            VpnState.updateStatus(VpnStatus.CONNECTING)
            startForeground(NOTIFICATION_ID, createNotification(getString(R.string.status_connecting)))

            val config = database.vpnConfigDao().getById(configId) ?: throw Exception("Config not found")
            val endpoints = database.vpnConfigDao().getAllInPool()
            val psk = securityManager.getPsk(configId) ?: throw Exception("PSK not found")

            val builder = Builder()
                .addAddress("198.18.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addRoute("100.64.0.0", 10)
                .addDnsServer("8.8.8.8")
                .setMtu(1280)
                .setSession("KAP Client")
                .setBlocking(false)

            val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            val proxyMode = prefs.getString("proxy_mode", "allowed") ?: "allowed"
            val proxiedApps = database.proxyAppDao().getProxiedPackageNames()

            if (proxyMode == "allowed") {
                if (proxiedApps.isNotEmpty()) {
                    proxiedApps.filter { it != packageName }.forEach { builder.addAllowedApplication(it) }
                } else {
                    builder.addDisallowedApplication(packageName)
                }
            } else {
                builder.addDisallowedApplication(packageName)
                proxiedApps.filter { it != packageName }.forEach { builder.addDisallowedApplication(it) }
            }
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            tunInterface = builder.establish() ?: throw Exception("Failed to establish TUN")

            val cm = getSystemService(ConnectivityManager::class.java)
            cm.activeNetwork?.let { setUnderlyingNetworks(arrayOf(it)) }

            val finalEndpoints = if (endpoints.isEmpty()) listOf(config) else endpoints
            val urlsJson = Json.encodeToString(finalEndpoints.map { it.server })
            val behindsJson = Json.encodeToString(finalEndpoints.map { it.behindCdn })
            val cookiesJson = Json.encodeToString(finalEndpoints.map { it.cookieUplink })

            val result = KapCore.startMulti(
                serverUrl = config.server,
                endpointsJson = urlsJson,
                endpointsBehindCdnJson = behindsJson,
                endpointsCookieUplinkJson = cookiesJson,
                psk = psk.toByteArray(),
                uid = config.uid,
                hwid = config.hwid,
                socks5Addr = "127.0.0.1:1080",
                behindCdn = config.behindCdn,
                debug = config.debug,
                downlinkWorkers = config.downlinkWorkers,
                uplinkPipeline = config.uplinkPipeline,
                insecure = config.insecure,
                cookieUplink = config.cookieUplink
            )
            if (result != 0) throw Exception("Failed to start KapCore: $result")
            
            val sbConfig = generateSingBoxConfig(1080, prefs.getBoolean("direct_russian_sites", false), 
                prefs.getBoolean("direct_whitelist", true), 
                prefs.getString("routing_block", "") ?: "", 
                prefs.getString("routing_direct", "") ?: "", 
                prefs.getString("routing_proxy", "") ?: "", 
                prefs.getString("routing_default", "proxy") ?: "proxy")
            
            val sbResult = KapCore.startSingBox(sbConfig, tunInterface!!.fd)
            if (sbResult != 0) throw Exception("Failed to start sing-box: $sbResult")

            delay(500)
            VpnState.updateStatus(VpnStatus.CONNECTED)
            activeEndpoints = finalEndpoints
            val activeNames = finalEndpoints.joinToString(", ") { it.name }
            updateNotification(getString(R.string.status_connected) + ": $activeNames")

            startStatsJob()
            startConnectivityJob()
        } catch (e: Exception) {
            tunInterface?.close()
            tunInterface = null
            throw e
        }
    }

    private suspend fun measureHttpPing(): String? = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val request = okhttp3.Request.Builder().url("https://www.gstatic.com/generate_204").build()
            proxyClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) "${System.currentTimeMillis() - startTime}ms" else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun measureTcpPing(config: VpnConfig): String = withContext(Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(config.server)
            val host = uri.host ?: return@withContext "Err"
            val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
            val startTime = System.currentTimeMillis()
            Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 2000) }
            "${System.currentTimeMillis() - startTime}ms"
        } catch (e: Exception) {
            "Timeout"
        }
    }

    private fun generateSingBoxConfig(
        socksPort: Int, directRu: Boolean, directWhitelist: Boolean,
        blockRules: String, directRules: String, proxyRules: String, defaultOutbound: String
    ): String {
        val rules = mutableListOf<String>()
        val ruleSets = mutableListOf<String>()
        
        fun addRuleSet(tag: String): Boolean {
            val localFile = File(filesDir, "rule-sets/$tag.srs")
            return if (localFile.exists() && localFile.length() > 0L) {
                ruleSets.add("""{ "tag": "$tag", "type": "local", "format": "binary", "path": "${localFile.absolutePath}" }""")
                true
            } else false
        }

        fun parseRules(text: String, outbound: String) {
            text.split("\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val lower = line.lowercase()
                when {
                    lower == "ip_is_private" -> rules.add("""{ "action": "route", "ip_is_private": true, "outbound": "$outbound" }""")
                    lower.startsWith("ruleset:") || lower.startsWith("geosite:") || lower.startsWith("geoip:") -> {
                        val tag = when {
                            lower.startsWith("ruleset:") -> line.substring(8).trim()
                            lower.startsWith("geosite:") -> "geosite-${line.substring(8).trim()}"
                            lower.startsWith("geoip:") -> "geoip-${line.substring(6).trim()}"
                            else -> ""
                        }
                        if (tag.substringAfter("-") == "private" && tag.startsWith("geoip-")) {
                            rules.add("""{ "action": "route", "ip_is_private": true, "outbound": "$outbound" }""")
                        } else if (addRuleSet(tag)) {
                            rules.add("""{ "action": "route", "rule_set": ["$tag"], "outbound": "$outbound" }""")
                        }
                    }
                    lower.startsWith("domain:") -> rules.add("""{ "action": "route", "domain_suffix": ["${line.substring(7).trim()}"], "outbound": "$outbound" }""")
                    lower.startsWith("full:") -> rules.add("""{ "action": "route", "domain": ["${line.substring(5).trim()}"], "outbound": "$outbound" }""")
                    lower.startsWith("cidr:") -> rules.add("""{ "action": "route", "ip_cidr": ["${line.substring(5).trim()}"], "outbound": "$outbound" }""")
                    line.contains("/") -> rules.add("""{ "action": "route", "ip_cidr": ["$line"], "outbound": "$outbound" }""")
                    else -> rules.add("""{ "action": "route", "domain_suffix": ["$line"], "outbound": "$outbound" }""")
                }
            }
        }
        
        rules.add("""{ "inbound": ["tun-in"], "action": "sniff" }""")
        rules.add("""{ "type": "logical", "mode": "or", "rules": [ { "protocol": "dns" }, { "port": 53 } ], "action": "hijack-dns" }""")
        rules.add("""{ "network": ["icmp"], "action": "route", "outbound": "direct" }""")
        rules.add("""{ "ip_is_private": true, "action": "route", "outbound": "direct" }""")

        if (directRu) {
            if (addRuleSet("geosite-category-ru")) rules.add("""{ "action": "route", "rule_set": ["geosite-category-ru"], "outbound": "direct" }""")
            if (addRuleSet("geoip-ru")) rules.add("""{ "action": "route", "rule_set": ["geoip-ru"], "outbound": "direct" }""")
            rules.add("""{ "action": "route", "domain_suffix": [".ru", ".xn--p1ai", ".su", ".am", ".kz", ".by"], "outbound": "direct" }""")
        }
        if (directWhitelist && addRuleSet("geoip-ru-whitelist")) rules.add("""{ "action": "route", "rule_set": ["geoip-ru-whitelist"], "outbound": "direct" }""")

        parseRules(blockRules, "block")
        parseRules(directRules, "direct")
        parseRules(proxyRules, "proxy")

        val ruleSetJson = if (ruleSets.isNotEmpty()) """ "rule_set": [ ${ruleSets.joinToString(",")} ], """ else ""
        val finalRules = mutableListOf("""{ "domain": ["github.com", "raw.githubusercontent.com", "objects.githubusercontent.com"], "action": "route", "outbound": "direct" }""")
        finalRules.addAll(rules)

        return """
        {
          "log": { "level": "info", "timestamp": true },
          "dns": {
            "servers": [
              { "tag": "dns_proxy", "type": "tcp", "server": "8.8.8.8", "server_port": 53, "detour": "proxy" },
              { "tag": "dns_direct", "type": "local" }
            ],
            "rules": [
              { "outbound": "any", "server": "dns_proxy" },
              { "domain": ["github.com", "raw.githubusercontent.com", "objects.githubusercontent.com"], "server": "dns_direct" },
              ${if (directRu) """{ "domain_suffix": [".ru", ".xn--p1ai", ".su", ".am", ".kz", ".by"], "server": "dns_direct" },""" else ""}
              { "query_type": ["A", "AAAA"], "server": "dns_proxy" }
            ],
            "final": "dns_proxy", "strategy": "ipv4_only"
          },
          "inbounds": [
            { "type": "tun", "tag": "tun-in", "address": ["198.18.0.2/24"], "mtu": 1280, "stack": "gvisor", "auto_route": false, "strict_route": false }
          ],
          "outbounds": [
            { "type": "socks", "tag": "proxy", "server": "127.0.0.1", "server_port": $socksPort },
            { "type": "direct", "tag": "direct" },
            { "type": "block", "tag": "block" }
          ],
          "route": {
            "auto_detect_interface": false,
            $ruleSetJson
            "rules": [ ${finalRules.joinToString(",")} ],
            "final": "${if (defaultOutbound == "direct" || defaultOutbound == "block") defaultOutbound else "proxy"}"
          }
        }
        """.trimIndent()
    }

    private fun stopVpn() {
        VpnState.updateStatus(VpnStatus.STOPPING)
        statsJob?.cancel()
        connectivityJob?.cancel()
        serviceScope.launch {
            vpnLock.withLock {
                stopVpnInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun pauseVpn() {
        VpnState.updateStatus(VpnStatus.STOPPING)
        statsJob?.cancel()
        connectivityJob?.cancel()
        serviceScope.launch {
            vpnLock.withLock {
                stopVpnInternal()
                VpnState.updateStatus(VpnStatus.PAUSED)
                updateNotification(getString(R.string.status_paused))
            }
        }
    }

    private suspend fun stopVpnInternal(isRestarting: Boolean = false) {
        KapCore.stopSingBox()
        delay(300)
        KapCore.stop()
        tunInterface?.close()
        tunInterface = null
        setUnderlyingNetworks(null)
        if (!isRestarting) VpnState.updateStatus(VpnStatus.DISCONNECTED)
    }

    private fun startStatsJob() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var lastPingTime = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastPingTime >= 30_000) {
                    if (VpnState.status.value == VpnStatus.CONNECTED) {
                        val tunnelPing = measureHttpPing()
                        val endpointResults = activeEndpoints.associate { it.id to measureTcpPing(it) }
                        val pingInfo = activeEndpoints.joinToString(", ") { "${it.name}: ${endpointResults[it.id] ?: "N/A"}" }
                        updateNotification("${getString(R.string.status_connected)}${if (tunnelPing != null) " (HTTP: $tunnelPing)" else ""}\n$pingInfo")
                    }
                    lastPingTime = now
                }
                delay(1000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "VPN Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPendingIntent = PendingIntent.getService(this, 1, Intent(this, KapVpnService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KAP Client VPN").setContentText(content).setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_vpn_notification).setContentIntent(pendingIntent).setPriority(NotificationCompat.PRIORITY_LOW)

        if (VpnState.status.value == VpnStatus.PAUSED) {
            val resumePendingIntent = PendingIntent.getService(this, 3, Intent(this, KapVpnService::class.java).apply { action = ACTION_RESUME }, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), stopPendingIntent)
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.resume), resumePendingIntent)
        } else {
            val pausePendingIntent = PendingIntent.getService(this, 2, Intent(this, KapVpnService::class.java).apply { action = ACTION_PAUSE }, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), stopPendingIntent)
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.pause), pausePendingIntent)
        }
        return builder.build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runBlocking {
            withContext(Dispatchers.IO) {
                vpnLock.withLock {
                    KapCore.stopSingBox()
                    KapCore.stop()
                    tunInterface?.close()
                    tunInterface = null
                }
            }
        }
        if (VpnState.status.value != VpnStatus.DISCONNECTED) VpnState.updateStatus(VpnStatus.DISCONNECTED)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }
}
