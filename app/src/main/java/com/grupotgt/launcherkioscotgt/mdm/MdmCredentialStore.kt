package com.grupotgt.launcherkioscotgt.mdm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class MdmCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun getOrCreateSecret(): String {
        ensureDeviceBinding()
        readSecret()?.let { return it }
        val secret = MdmCrypto.newDeviceSecret()
        persistSecret(secret)
        return secret
    }

    fun fingerprint(): String = MdmCrypto.fingerprint(getOrCreateSecret())

    @Synchronized
    fun requireExistingSecret(): String {
        ensureDeviceBinding()
        return readSecret() ?: throw IllegalStateException("MDM credential is not enrolled yet")
    }

    private fun ensureDeviceBinding() {
        val currentDeviceId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val boundDeviceId = preferences.getString(KEY_DEVICE_ID, null)
        val containsCredential = preferences.contains(KEY_CIPHERTEXT) || preferences.contains(KEY_IV)
        val reset = MdmDeviceBindingPolicy.mustReset(boundDeviceId, currentDeviceId, containsCredential)
        if (reset) {
            check(preferences.edit().clear().putString(KEY_DEVICE_ID, currentDeviceId).commit()) {
                "MDM credential binding could not be reset safely"
            }
        } else if (boundDeviceId != currentDeviceId) {
            check(preferences.edit().putString(KEY_DEVICE_ID, currentDeviceId).commit()) {
                "MDM credential binding could not be persisted"
            }
        }
    }

    private fun readSecret(): String? {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null)
            ?: throw IllegalStateException("MDM credential metadata is incomplete")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateEncryptionKey(),
                GCMParameterSpec(GCM_TAG_BITS, decode(iv))
            )
            String(cipher.doFinal(decode(encrypted)), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("MDM credential cannot be decrypted", error)
        }
    }

    private fun persistSecret(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
        val encrypted = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        val persisted = preferences.edit()
            .putString(KEY_CIPHERTEXT, encode(encrypted))
            .putString(KEY_IV, encode(cipher.iv))
            .commit()
        check(persisted) { "MDM credential could not be persisted" }
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val PREFERENCES = "MdmSecureCredentials"
        const val KEY_CIPHERTEXT = "device_secret_ciphertext_v1"
        const val KEY_IV = "device_secret_iv_v1"
        const val KEY_DEVICE_ID = "bound_device_id_v1"
        const val KEY_ALIAS = "com.grupotgt.launcherkioscotgt.mdm.credentials.v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
