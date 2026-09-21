package me.unknkriod.kapclient.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.unknkriod.kapclient.data.*
import me.unknkriod.kapclient.service.KapVpnService
import me.unknkriod.kapclient.util.*

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val db = KapDatabase.getDatabase(application)
    private val securityManager = SecurityManager(application)
    private val subscriptionManager = SubscriptionManager(application)
    private val prefs = application.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

    val configs: StateFlow<List<VpnConfig>> = db.vpnConfigDao().getAll()
        .combine(db.subscriptionDao().getAll()) { configs, subscriptions ->
            configs.map { config ->
                val sub = subscriptions.find { it.id == config.subscriptionId }
                if (sub != null) {
                    var cleanedName = config.name
                    val subName = sub.name.trim()
                    
                    if (cleanedName.startsWith(subName, ignoreCase = true)) {
                        val remainder = cleanedName.substring(subName.length).trimStart { it == ' ' || it == '-' || it == ':' || it == '|' || it == '_' }
                        if (remainder.isNotEmpty()) {
                            cleanedName = remainder
                        }
                    }
                    
                    config.copy(name = cleanedName)
                } else {
                    config
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<Subscription>> = db.subscriptionDao().getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val proxyApps: StateFlow<List<ProxyApp>> = db.proxyAppDao().getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vpnStatus: StateFlow<VpnStatus> = VpnState.status
    val upSpeed: StateFlow<Long> = VpnState.upSpeed
    val downSpeed: StateFlow<Long> = VpnState.downSpeed
    val connectionStartTime: StateFlow<Long> = VpnState.connectionStartTime
    val activeConfigId: StateFlow<Long> = VpnState.activeConfigId



    private val _proxyMode = MutableStateFlow(prefs.getString("proxy_mode", "allowed") ?: "allowed")
    val proxyMode: StateFlow<String> = _proxyMode

    private val _showSystemApps = MutableStateFlow(prefs.getBoolean("show_system_apps", false))
    val showSystemApps: StateFlow<Boolean> = _showSystemApps

    private val _directRussianSites = MutableStateFlow(prefs.getBoolean("direct_russian_sites", false))
    val directRussianSites: StateFlow<Boolean> = _directRussianSites

    private val _directWhitelist = MutableStateFlow(prefs.getBoolean("direct_whitelist", true))
    val directWhitelist: StateFlow<Boolean> = _directWhitelist

    private val _preferredDirectConfigIds = MutableStateFlow(prefs.getString("preferred_direct_config_ids", "")?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet())
    val preferredDirectConfigIds: StateFlow<Set<Long>> = _preferredDirectConfigIds

    private val _preferredCdnConfigIds = MutableStateFlow(prefs.getString("preferred_cdn_config_ids", "")?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet())
    val preferredCdnConfigIds: StateFlow<Set<Long>> = _preferredCdnConfigIds

    private val _blockRules = MutableStateFlow(prefs.getString("routing_block", "") ?: "")
    val blockRules: StateFlow<String> = _blockRules

    private val _directRules = MutableStateFlow(prefs.getString("routing_direct", "") ?: "")
    val directRules: StateFlow<String> = _directRules

    private val _proxyRules = MutableStateFlow(prefs.getString("routing_proxy", "") ?: "")
    val proxyRules: StateFlow<String> = _proxyRules

    private val _defaultOutbound = MutableStateFlow(prefs.getString("routing_default", "proxy") ?: "proxy")
    val defaultOutbound: StateFlow<String> = _defaultOutbound

    val isRulesReady: StateFlow<Boolean> = combine(
        combine(directRussianSites, directWhitelist) { drs, dw -> drs || dw },
        combine(blockRules, directRules, proxyRules) { b, d, p -> b.isNotBlank() || d.isNotBlank() || p.isNotBlank() },
        RuleSetManager.isDownloadedFlow
    ) { hasToggles, hasRules, downloaded ->
        if (hasToggles || hasRules) downloaded else true
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        if (directRussianSites.value || directWhitelist.value || blockRules.value.isNotBlank() || directRules.value.isNotBlank() || proxyRules.value.isNotBlank()) {
            RuleSetManager.isDownloaded(application)
        } else {
            true
        }
    )

    private val _latency = MutableStateFlow<String?>(null)
    val latency: StateFlow<String?> = _latency

    private val _configLatencies = MutableStateFlow<Map<Long, String>>(emptyMap())
    val configLatencies: StateFlow<Map<Long, String>> = _configLatencies

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    private val _pingResults = MutableStateFlow<String?>(null)
    val pingResults: StateFlow<String?> = _pingResults

    fun clearImportError() {
        _importError.value = null
    }

    fun clearPingResults() {
        _pingResults.value = null
    }

    fun selectConfig(id: Long) {
        // Not used with new toggle logic, but kept for legacy
    }

    fun measureLatency() {
        if (vpnStatus.value != VpnStatus.CONNECTED) return
        
        viewModelScope.launch(Dispatchers.IO) {
            _latency.value = "..."
            try {
                val startTime = System.currentTimeMillis()
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 1080))
                val client = okhttp3.OkHttpClient.Builder()
                    .proxy(proxy)
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val request = okhttp3.Request.Builder()
                    .url("http://connectivitycheck.gstatic.com/generate_204")
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 204) {
                        val duration = System.currentTimeMillis() - startTime
                        _latency.value = "${duration}ms"
                    } else {
                        _latency.value = "Error"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VpnViewModel", "Latency measure failed", e)
                _latency.value = "Timeout"
            } finally {
                viewModelScope.launch {
                    kotlinx.coroutines.delay(15000)
                    _latency.value = null
                }
            }
        }
    }

    fun toggleVpn(context: Context, prepareLauncher: ActivityResultLauncher<Intent>?) {
        if (vpnStatus.value == VpnStatus.CONNECTED || vpnStatus.value == VpnStatus.CONNECTING) {
            val intent = Intent(context, KapVpnService::class.java).apply {
                action = KapVpnService.ACTION_STOP
            }
            context.startService(intent)
        } else if (vpnStatus.value == VpnStatus.PAUSED) {
            val intent = Intent(context, KapVpnService::class.java).apply {
                action = KapVpnService.ACTION_RESUME
            }
            context.startService(intent)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val poolConfigs = configs.value.filter { it.inPool }
                if (poolConfigs.isEmpty()) {
                    _importError.value = "Please select at least one server"
                    return@launch
                }

                // Ping selected backends
                val results = StringBuilder()
                poolConfigs.forEach { config ->
                    try {
                        val uri = android.net.Uri.parse(config.server)
                        val host = uri.host ?: return@forEach
                        val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
                        
                        val startTime = System.currentTimeMillis()
                        java.net.Socket().use { socket ->
                            socket.connect(java.net.InetSocketAddress(host, port), 2000)
                        }
                        val duration = System.currentTimeMillis() - startTime
                        results.append("${config.name}: ${duration}ms\n")
                    } catch (e: Exception) {
                        results.append("${config.name}: Timeout\n")
                    }
                }
                _pingResults.value = results.toString().trim()

                // Start with the first selected config (service will use all in pool)
                val configId = poolConfigs.first().id

                val vpnIntent = VpnService.prepare(context.applicationContext)
                if (vpnIntent != null) {
                    prepareLauncher?.launch(vpnIntent)
                } else {
                    startVpnService(context, configId)
                }
            }
        }
    }

    fun startVpnService(context: Context, configId: Long) {
        val intent = Intent(context, KapVpnService::class.java).apply {
            action = KapVpnService.ACTION_START
            putExtra(KapVpnService.EXTRA_CONFIG_ID, configId)
        }
        context.startService(intent)
    }

    fun deleteConfig(config: VpnConfig) {
        viewModelScope.launch {
            db.vpnConfigDao().delete(config)
            securityManager.deletePsk(config.id)
        }
    }

    fun updateConfigInPool(config: VpnConfig, inPool: Boolean) {
        viewModelScope.launch {
            db.vpnConfigDao().update(config.copy(inPool = inPool))
        }
    }

    fun getPsk(configId: Long): String? {
        return securityManager.getPsk(configId)
    }
    
    fun saveConfig(config: VpnConfig, psk: String) {
        viewModelScope.launch {
            val id = db.vpnConfigDao().insert(config)
            securityManager.savePsk(id, psk)
        }
    }

    fun importConfig(link: String) {
        android.util.Log.i("KAP_DEBUG", "Importing config from link")
        LinkParser.parse(link)?.let { result ->
            saveConfig(result.config, result.psk)
        } ?: run {
            android.util.Log.e("KAP_DEBUG", "Failed to parse link: $link")
        }
    }

    fun handleQrCode(code: String) {
        val trimmed = code.trim()
        android.util.Log.i("KAP_DEBUG", "Handling QR code, length: ${trimmed.length}")
        if (trimmed.startsWith("kap-proxy://") || trimmed.startsWith("cap-proxy://")) {
            importConfig(trimmed)
        } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            addSubscription("", trimmed)
        } else {
            _importError.value = "Unknown QR code format"
        }
    }

    fun addSubscription(name: String, url: String) {
        android.util.Log.i("KAP_DEBUG", "Adding subscription: $url")
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            val result = subscriptionManager.addSubscription(name, url)
            if (result.isFailure) {
                _importError.value = result.exceptionOrNull()?.message ?: "Unknown error"
            }
            _isImporting.value = false
        }
    }

    fun updateSubscription(id: Long) {
        viewModelScope.launch {
            subscriptionManager.updateSubscription(id)
        }
    }

    fun deleteSubscription(subscription: Subscription) {
        viewModelScope.launch {
            db.vpnConfigDao().deleteBySubscriptionId(subscription.id)
            db.subscriptionDao().delete(subscription)
        }
    }

    fun saveSubscription(subscription: Subscription) {
        viewModelScope.launch {
            db.subscriptionDao().update(subscription)
        }
    }

    fun measureSubscriptionLatencies(subscriptionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val configsToTest = configs.value.filter { it.subscriptionId == subscriptionId }
            configsToTest.forEach { config ->
                _configLatencies.value = _configLatencies.value + (config.id to "...")
                try {
                    val uri = android.net.Uri.parse(config.server)
                    val host = uri.host ?: throw Exception("Invalid host")
                    val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
                    
                    val startTime = System.currentTimeMillis()
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(host, port), 2000)
                    }
                    val duration = System.currentTimeMillis() - startTime
                    _configLatencies.value = _configLatencies.value + (config.id to "${duration}ms")
                } catch (e: Exception) {
                    _configLatencies.value = _configLatencies.value + (config.id to "Timeout")
                }
            }
        }
    }

    fun moveSubscriptionToTop(subscription: Subscription) {
        viewModelScope.launch {
            val minOrder = db.subscriptionDao().getMinOrder() ?: 0
            db.subscriptionDao().update(subscription.copy(displayOrder = minOrder - 1))
        }
    }

    fun updateProxyApp(app: ProxyApp) {
        viewModelScope.launch {
            db.proxyAppDao().insert(app)
        }
    }

    fun setProxyMode(mode: String) {
        _proxyMode.value = mode
        prefs.edit().putString("proxy_mode", mode).apply()
    }

    fun setShowSystemApps(show: Boolean) {
        _showSystemApps.value = show
        prefs.edit().putBoolean("show_system_apps", show).apply()
    }

    fun setRoutingRules(block: String, direct: String, proxy: String, default: String) {
        _blockRules.value = block
        _directRules.value = direct
        _proxyRules.value = proxy
        _defaultOutbound.value = default
        
        prefs.edit()
            .putString("routing_block", block)
            .putString("routing_direct", direct)
            .putString("routing_proxy", proxy)
            .putString("routing_default", default)
            .apply()
    }

    fun setDirectRussianSites(enabled: Boolean) {
        _directRussianSites.value = enabled
        prefs.edit().putBoolean("direct_russian_sites", enabled).apply()
    }

    fun setDirectWhitelist(enabled: Boolean) {
        _directWhitelist.value = enabled
        prefs.edit().putBoolean("direct_whitelist", enabled).apply()
    }

    fun setPreferredDirectConfigs(ids: Set<Long>) {
        _preferredDirectConfigIds.value = ids
        prefs.edit().putString("preferred_direct_config_ids", ids.joinToString(",")).apply()
    }

    fun setPreferredCdnConfigs(ids: Set<Long>) {
        _preferredCdnConfigIds.value = ids
        prefs.edit().putString("preferred_cdn_config_ids", ids.joinToString(",")).apply()
    }

    private val _isIfconfigReachable = MutableStateFlow(true)
    val isIfconfigReachable: StateFlow<Boolean> = _isIfconfigReachable

    fun checkWhitelists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder().url("https://ifconfig.me").build()
                client.newCall(request).execute().use { response ->
                    _isIfconfigReachable.value = response.isSuccessful
                }
            } catch (e: Exception) {
                _isIfconfigReachable.value = false
            }
        }
    }

    fun disableAllRouting() {
        setDirectRussianSites(false)
        setDirectWhitelist(false)
        setRoutingRules("", "", "", "proxy")
    }

    fun updateRules(context: Context) {
        viewModelScope.launch {
            RuleSetManager.updateAll(context)
        }
    }
}
