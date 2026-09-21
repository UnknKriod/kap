package me.unknkriod.kapclient.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.unknkriod.kapclient.util.Base85
import me.unknkriod.kapclient.util.LinkParser
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json

class SubscriptionManager(private val context: Context) {
    private val db = KapDatabase.getDatabase(context)
    private val securityManager = SecurityManager(context)
    private val client = OkHttpClient()
    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    suspend fun updateSubscription(subscriptionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val sub = db.subscriptionDao().getById(subscriptionId) ?: return@withContext Result.failure(Exception("Subscription not found"))

        try {
            val request = Request.Builder().url(sub.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("KAP_DEBUG", "Update failed: HTTP ${response.code} for ${sub.url}")
                    return@withContext Result.failure(Exception("HTTP error ${response.code}"))
                }

                val body = response.body?.string() ?: ""
                if (body.isBlank()) {
                    Log.e("KAP_DEBUG", "Update failed: Empty body for ${sub.url}")
                    return@withContext Result.failure(Exception("Empty response body"))
                }
                
                val decodedJson = try {
                    String(Base85.decode(body), Charsets.UTF_8).trim { it <= ' ' || it.code == 0 }
                } catch (e: Exception) {
                    Log.e("KAP_DEBUG", "Base85 decode failed for URL: ${sub.url}")
                    Log.e("KAP_DEBUG", "Body preview (first 100): ${body.take(100)}")
                    throw e
                }
                
                val subData = try {
                    json.decodeFromString<SubscriptionResponse>(decodedJson)
                } catch (e: Exception) {
                    Log.e("KAP_DEBUG", "JSON parse failed. Decoded text preview: ${decodedJson.take(100)}")
                    throw e
                }

                db.vpnConfigDao().deleteBySubscriptionId(subscriptionId)

                var count = 0
                subData.servers.asReversed().forEach { server ->
                    Log.i("KAP_DEBUG", "Processing server: ${server.name} with URI: ${server.uri}")
                    LinkParser.parse(server.uri)?.let { result ->
                        count++
                        var configName = result.config.name
                        val subName = subData.name.trim()
                        
                        // Improved removal: check if name starts with subName followed by separator
                        if (configName.startsWith(subName, ignoreCase = true)) {
                            val remainder = configName.substring(subName.length).trimStart { it == ' ' || it == '-' || it == ':' || it == '|' || it == '_' }
                            if (remainder.isNotEmpty()) {
                                configName = remainder
                            }
                        }

                        val config = result.config.copy(
                            name = configName,
                            subscriptionId = subscriptionId,
                            uid = subData.uid,
                            behindCdn = server.behindCdn, // Use value from JSON if available
                            inPool = false,
                            trafficUsed = server.trafficUsed,
                            trafficLimit = server.trafficLimit,
                            trafficRemaining = server.trafficRemaining,
                            cookieUplink = server.cookieUplink
                        )
                        val id = db.vpnConfigDao().insert(config)
                        securityManager.savePsk(id, subData.psk)
                    }
                }
                Log.i("KAP_DEBUG", "Updated subscription ${sub.id}: $count servers added")
                
                db.subscriptionDao().update(sub.copy(
                    name = subData.name,
                    lastUpdated = System.currentTimeMillis(),
                    trafficUsed = subData.trafficUsed,
                    trafficLimit = subData.trafficLimit,
                    trafficRemaining = subData.trafficRemaining,
                    expiresAt = subData.expiresAt
                ))
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e("KAP_DEBUG", "Update failed", e)
            Result.failure(e)
        }
    }
    
    suspend fun addSubscription(name: String, url: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("KAP_DEBUG", "Add failed: HTTP ${response.code} for $url")
                    return@withContext Result.failure(Exception("HTTP error ${response.code}"))
                }

                val body = response.body?.string() ?: ""
                if (body.isBlank()) {
                    Log.e("KAP_DEBUG", "Add failed: Empty body for $url")
                    return@withContext Result.failure(Exception("Empty response body"))
                }
                
                val decodedJson = try {
                    String(Base85.decode(body), Charsets.UTF_8).trim { it <= ' ' || it.code == 0 }
                } catch (e: Exception) {
                    Log.e("KAP_DEBUG", "Base85 decode failed for URL: $url")
                    Log.e("KAP_DEBUG", "Body preview (first 100): ${body.take(100)}")
                    throw e
                }
                
                val subData = try {
                    json.decodeFromString<SubscriptionResponse>(decodedJson)
                } catch (e: Exception) {
                    Log.e("KAP_DEBUG", "JSON parse failed. Decoded text preview: ${decodedJson.take(100)}")
                    throw e
                }

                val sub = Subscription(
                    name = name.ifBlank { subData.name },
                    url = url,
                    lastUpdated = System.currentTimeMillis(),
                    trafficUsed = subData.trafficUsed,
                    trafficLimit = subData.trafficLimit,
                    trafficRemaining = subData.trafficRemaining,
                    expiresAt = subData.expiresAt
                )
                val subscriptionId = db.subscriptionDao().insert(sub)

                var count = 0
                subData.servers.asReversed().forEach { server ->
                    Log.i("KAP_DEBUG", "Processing server: ${server.name} with URI: ${server.uri}")
                    LinkParser.parse(server.uri)?.let { result ->
                        count++
                        var configName = result.config.name
                        val subName = subData.name.trim()
                        
                        // Improved removal: check if name starts with subName followed by separator
                        if (configName.startsWith(subName, ignoreCase = true)) {
                            val remainder = configName.substring(subName.length).trimStart { it == ' ' || it == '-' || it == ':' || it == '|' || it == '_' }
                            if (remainder.isNotEmpty()) {
                                configName = remainder
                            }
                        }

                        val config = result.config.copy(
                            name = configName,
                            subscriptionId = subscriptionId,
                            uid = subData.uid,
                            behindCdn = server.behindCdn,
                            inPool = false,
                            trafficUsed = server.trafficUsed,
                            trafficLimit = server.trafficLimit,
                            trafficRemaining = server.trafficRemaining,
                            cookieUplink = server.cookieUplink
                        )
                        val id = db.vpnConfigDao().insert(config)
                        securityManager.savePsk(id, subData.psk)
                    }
                }
                Log.i("KAP_DEBUG", "Added subscription $subscriptionId: $count servers added")
                Result.success(subscriptionId)
            }
        } catch (e: Exception) {
            Log.e("KAP_DEBUG", "Add failed", e)
            Result.failure(e)
        }
    }
}
