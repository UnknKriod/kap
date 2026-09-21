package me.unknkriod.kapclient.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_apps")
data class ProxyApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isProxied: Boolean = true
)
