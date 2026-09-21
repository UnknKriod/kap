package me.unknkriod.kapclient.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnConfigDao {
    @Query("SELECT * FROM vpn_configs ORDER BY id DESC")
    fun getAll(): Flow<List<VpnConfig>>

    @Query("SELECT * FROM vpn_configs")
    suspend fun getAllConfigs(): List<VpnConfig>

    @Query("SELECT * FROM vpn_configs WHERE inPool = 1")
    suspend fun getAllInPool(): List<VpnConfig>

    @Query("SELECT * FROM vpn_configs WHERE id = :id")
    suspend fun getById(id: Long): VpnConfig?

    @Query("SELECT * FROM vpn_configs WHERE subscriptionId = :subId AND behindCdn = 0")
    suspend fun getNonCdnBySubscriptionId(subId: Long): List<VpnConfig>

    @Query("SELECT * FROM vpn_configs WHERE subscriptionId = :subId AND inPool = 1")
    suspend fun getInPoolBySubscriptionId(subId: Long): List<VpnConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: VpnConfig): Long

    @Update
    suspend fun update(config: VpnConfig)

    @Delete
    suspend fun delete(config: VpnConfig)

    @Query("DELETE FROM vpn_configs WHERE subscriptionId = :subscriptionId")
    suspend fun deleteBySubscriptionId(subscriptionId: Long)
}
