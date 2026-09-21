package me.unknkriod.kapclient.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionResponse(
    val v: Int,
    val name: String,
    val uid: String,
    val psk: String,
    @SerialName("traffic_used_bytes") val trafficUsed: Long,
    @SerialName("traffic_limit_bytes") val trafficLimit: Long,
    @SerialName("traffic_remaining_bytes") val trafficRemaining: Long,
    @SerialName("expires_at") val expiresAt: String? = null,
    val servers: List<ServerInfo>
)

@Serializable
data class ServerInfo(
    val id: Int,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    @SerialName("behind_cdn") val behindCdn: Boolean,
    val uri: String,
    @SerialName("traffic_used_bytes") val trafficUsed: Long = 0,
    @SerialName("traffic_limit_bytes") val trafficLimit: Long = 0,
    @SerialName("traffic_remaining_bytes") val trafficRemaining: Long = -1,
    @SerialName("cookie_uplink") val cookieUplink: Boolean = false
)
