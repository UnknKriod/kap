package me.unknkriod.kapclient.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [VpnConfig::class, Subscription::class, ProxyApp::class], version = 9, exportSchema = false)
abstract class KapDatabase : RoomDatabase() {
    abstract fun vpnConfigDao(): VpnConfigDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun proxyAppDao(): ProxyAppDao

    companion object {
        @Volatile
        private var INSTANCE: KapDatabase? = null

        fun getDatabase(context: Context): KapDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KapDatabase::class.java,
                    "kap_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
