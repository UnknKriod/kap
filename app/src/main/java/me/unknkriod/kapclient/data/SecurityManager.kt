package me.unknkriod.kapclient.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityManager(context: Context) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private val sharedPreferences = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "KapPskKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    init {
        // Initialization can be heavy, but AndroidKeyStore is loaded lazily.
        // We'll trigger key generation in a thread-safe way on first access.
    }

    private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        ensureKeyExists()
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun savePsk(configId: Long, psk: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val encryptedPsk = cipher.doFinal(psk.toByteArray())
        
        val ivString = Base64.encodeToString(iv, Base64.DEFAULT)
        val encryptedPskString = Base64.encodeToString(encryptedPsk, Base64.DEFAULT)
        
        sharedPreferences.edit()
            .putString("psk_iv_$configId", ivString)
            .putString("psk_data_$configId", encryptedPskString)
            .apply()
    }

    fun getPsk(configId: Long): String? {
        val ivString = sharedPreferences.getString("psk_iv_$configId", null) ?: return null
        val encryptedPskString = sharedPreferences.getString("psk_data_$configId", null) ?: return null
        
        return try {
            val iv = Base64.decode(ivString, Base64.DEFAULT)
            val encryptedPsk = Base64.decode(encryptedPskString, Base64.DEFAULT)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            
            String(cipher.doFinal(encryptedPsk))
        } catch (e: Exception) {
            // Decryption failed. This happens if the app was reinstalled and data was restored via Auto Backup,
            // but the KeyStore keys were (correctly) deleted by the system.
            android.util.Log.e("SecurityManager", "Failed to decrypt PSK for $configId. Data might be stale.", e)
            null
        }
    }
    
    fun deletePsk(configId: Long) {
        sharedPreferences.edit()
            .remove("psk_iv_$configId")
            .remove("psk_data_$configId")
            .apply()
    }
}
