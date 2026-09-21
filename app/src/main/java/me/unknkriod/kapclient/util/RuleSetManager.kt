package me.unknkriod.kapclient.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URL
import me.unknkriod.kapclient.R
import me.unknkriod.kapclient.native.KapCore

data class UpdateProgress(
    val status: String = "",
    val progress: Float = 0f,
    val speed: String = ""
)

object RuleSetManager {
    private const val TAG = "RuleSetManager"

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _updateProgress = MutableStateFlow(UpdateProgress())
    val updateProgress: StateFlow<UpdateProgress> = _updateProgress.asStateFlow()

    private val _isDownloaded = MutableStateFlow(false)
    val isDownloadedFlow: StateFlow<Boolean> = _isDownloaded.asStateFlow()

    // We don't need to download individual SRS files anymore 
    // because we convert them from DAT files locally.
    val RULE_SETS = emptyList<Pair<String, String>>()

    val DAT_FILES = listOf(
        "geosite.dat" to "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat",
        "geoip.dat" to "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat"
    )

    fun init(context: Context) {
        _isDownloaded.value = isDownloaded(context)
    }

    suspend fun updateAll(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (_isUpdating.value) return@withContext false
        _isUpdating.value = true
        _updateProgress.value = UpdateProgress(status = context.getString(R.string.downloading) + "...")
        
        val dir = File(context.filesDir, "rule-sets")
        if (!dir.exists()) dir.mkdirs()

        var success = true
        
        // Download DAT files
        for ((name, url) in DAT_FILES) {
            val file = File(context.filesDir, name)
            val downloadOk = downloadFile(file, url) { bytesRead, totalBytes, speed ->
                val progress = if (totalBytes > 0) bytesRead.toFloat() / totalBytes else 0f
                _updateProgress.value = UpdateProgress(
                    status = context.getString(R.string.downloading) + ": $name",
                    progress = progress,
                    speed = speed
                )
            }
            
            if (!downloadOk) {
                success = false
                if (file.exists()) file.delete()
                break
            } else if (file.length() < 100 * 1024) {
                Log.e(TAG, "$name is too small (${file.length()} bytes), deleting")
                file.delete()
                success = false
                break
            }
        }
        
        if (success) {
            _updateProgress.value = UpdateProgress(status = context.getString(R.string.converting) + "...", progress = 0f)
            
            // Cleanup old rule-sets
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            } else {
                dir.mkdirs()
            }
            
            // Convert ALL geosite entries
            val geositeFile = File(context.filesDir, "geosite.dat")
            if (geositeFile.exists()) {
                val count = KapCore.convertDatToSrsAll(true, geositeFile.absolutePath, dir.absolutePath, "geosite-")
                Log.i(TAG, "Converted $count geosite rule-sets")
            }
            
            _updateProgress.value = UpdateProgress(status = context.getString(R.string.converting) + "...", progress = 0.5f)
            
            // Convert ALL geoip entries
            val geoipFile = File(context.filesDir, "geoip.dat")
            if (geoipFile.exists()) {
                val count = KapCore.convertDatToSrsAll(false, geoipFile.absolutePath, dir.absolutePath, "geoip-")
                Log.i(TAG, "Converted $count geoip rule-sets")
            }
            
            _updateProgress.value = UpdateProgress(status = context.getString(R.string.status_connected), progress = 1f)

            val now = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
            context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_database_update", now)
                .apply()
            
            _isDownloaded.value = true
        }
        
        _isUpdating.value = false
        success
    }

    private fun downloadFile(file: File, urlStr: String, onProgress: (Long, Long, String) -> Unit): Boolean {
        var currentUrl = urlStr
        var connection: java.net.HttpURLConnection? = null
        try {
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                
                val status = connection.responseCode
                if (status == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                    status == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == 307 || status == 308) {
                    val newUrl = connection.getHeaderField("Location")
                    if (newUrl != null) {
                        currentUrl = newUrl
                        redirects++
                        connection.disconnect()
                        continue
                    }
                }
                break
            }
            
            if (connection!!.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned HTTP ${connection.responseCode} for $urlStr")
                return false
            }

            val totalBytes = connection.contentLength.toLong()
            var bytesRead = 0L
            val startTime = System.currentTimeMillis()
            var lastUpdate = 0L

            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200) {
                            val elapsed = (now - startTime) / 1000.0
                            val speedBps = if (elapsed > 0) bytesRead / elapsed else 0.0
                            val speedText = formatSpeed(speedBps)
                            onProgress(bytesRead, totalBytes, speedText)
                            lastUpdate = now
                        }
                    }
                }
            }
            Log.i(TAG, "Downloaded ${file.name}, size: ${file.length()} bytes")
            return file.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download ${file.name}", e)
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB/s", bytesPerSecond / (1024 * 1024))
            bytesPerSecond >= 1024 -> String.format(java.util.Locale.US, "%.2f KB/s", bytesPerSecond / 1024)
            else -> String.format(java.util.Locale.US, "%d B/s", bytesPerSecond.toLong())
        }
    }

    fun isDownloaded(context: Context): Boolean {
        val dir = File(context.filesDir, "rule-sets")
        val datOk = DAT_FILES.all { (name, _) -> File(context.filesDir, name).exists() }
        val srsOk = dir.exists() && (dir.list()?.isNotEmpty() ?: false)
        return datOk && srsOk
    }
}
