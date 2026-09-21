package me.unknkriod.kapclient.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyAppDao {
    @Query("SELECT * FROM proxy_apps")
    fun getAll(): Flow<List<ProxyApp>>

    @Query("SELECT packageName FROM proxy_apps WHERE isProxied = 1")
    suspend fun getProxiedPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: ProxyApp)
}
