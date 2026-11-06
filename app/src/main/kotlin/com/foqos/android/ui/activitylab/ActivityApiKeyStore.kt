package com.foqos.android.ui.activitylab

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Private, per-install encrypted API key. The AES secret never leaves Android Keystore.
 * This is only for the owner's personal test build, not safe for public client distribution.
 */
class ActivityApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = prefs.contains(ENCRYPTED_KEY)

    fun save(key: String) = saveSecret(ENCRYPTED_KEY, key)

    fun saveRelayToken(token: String) = saveSecret(ENCRYPTED_RELAY_TOKEN, token)
    fun loadRelayToken(): String? = loadSecret(ENCRYPTED_RELAY_TOKEN)
    fun deleteRelayToken() { prefs.edit().remove(ENCRYPTED_RELAY_TOKEN).commit() }

    private fun saveSecret(preferenceKey: String, key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "APIキーが空だよ" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(preferenceKey, value).commit()) {
            "APIキーを保存できなかった"
        }
    }

    fun load(): String? = loadSecret(ENCRYPTED_KEY)

    private fun loadSecret(preferenceKey: String): String? {
        val saved = prefs.getString(preferenceKey, null) ?: return null
        return try {
            val pieces = saved.split(":", limit = 2)
            require(pieces.size == 2) { "保存形式が不正" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // A restored backup can contain ciphertext but not its Keystore key.
            // Don't keep unreadable secrets around or fall back to plaintext storage.
            prefs.edit().remove(preferenceKey).commit()
            null
        }
    }

    fun delete() {
        prefs.edit().remove(ENCRYPTED_KEY).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val PREFS = "activity_lab_private_credentials"
        const val ENCRYPTED_KEY = "gemini_api_key_encrypted"
        const val ENCRYPTED_RELAY_TOKEN = "relay_auth_token_encrypted"
        const val KEY_ALIAS = "foqos.activitylab.gemini.aes.v1"
    }
}
