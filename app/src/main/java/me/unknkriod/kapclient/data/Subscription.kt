package me.unknkriod.kapclient.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val trafficUsed: Long = 0,
    val trafficLimit: Long = 0,
    val trafficRemaining: Long = 0,
    val expiresAt: String? = null,
    val lastUpdated: Long = 0,
    val displayOrder: Int = 0
)
