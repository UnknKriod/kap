package me.unknkriod.kapclient.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_configs")
data class VpnConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val server: String,
    val uid: String? = null,
    val hwid: String? = null,
    val behindCdn: Boolean = false,
    val debug: Boolean = false,
    val downlinkWorkers: Int = 4,
    val uplinkPipeline: Int = 4,
    val insecure: Boolean = false,
    val cookieUplink: Boolean = false,
    val inPool: Boolean = false,
    val subscriptionId: Long? = null,
    val trafficUsed: Long = 0,
    val trafficLimit: Long = 0,
    val trafficRemaining: Long = 0,
    val sni: String? = null
)
