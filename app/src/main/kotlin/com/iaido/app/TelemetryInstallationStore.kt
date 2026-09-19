package com.iaido.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TelemetryInstallation(
    val installationId: String,
    val writeCredential: String,
    val deletionCredential: String,
) {
    init {
        require(installationId.isNotBlank())
        require(writeCredential.isNotBlank())
        require(deletionCredential.isNotBlank())
    }
}

internal interface InstallationCredentialStorage {
    fun read(): TelemetryInstallation?
    fun write(installation: TelemetryInstallation)
    fun clear()
}

class TelemetryInstallationStore internal constructor(
    private val storage: InstallationCredentialStorage,
) {
    constructor(context: Context) : this(AndroidKeystoreCredentialStorage(context.applicationContext))

    @Synchronized
    fun getOrCreateInstallation(provision: () -> TelemetryInstallation): TelemetryInstallation {
        storage.read()?.let { return it }
        return provision().also(storage::write)
    }

    fun writeCredential(plane: TelemetryPlane): String? = when (plane) {
        TelemetryPlane.DIAGNOSTICS, TelemetryPlane.RESEARCH -> storage.read()?.writeCredential
    }

    fun deletionCredential(): String? = storage.read()?.deletionCredential

    fun clear() = storage.clear()
}

private class AndroidKeystoreCredentialStorage(
    context: Context,
) : InstallationCredentialStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): TelemetryInstallation? {
        val encryptedInstallationId = preferences.getString(INSTALLATION_ID_KEY, null) ?: return null
        val encryptedWriteCredential = preferences.getString(WRITE_CREDENTIAL_KEY, null) ?: return null
        val encryptedDeletionCredential = preferences.getString(DELETION_CREDENTIAL_KEY, null) ?: return null
        return try {
            TelemetryInstallation(
                installationId = decrypt(encryptedInstallationId),
                writeCredential = decrypt(encryptedWriteCredential),
                deletionCredential = decrypt(encryptedDeletionCredential),
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override fun write(installation: TelemetryInstallation) {
        val encryptedInstallationId = encrypt(installation.installationId)
        val encryptedWriteCredential = encrypt(installation.writeCredential)
        val encryptedDeletionCredential = encrypt(installation.deletionCredential)
        check(
            preferences.edit()
                .putString(INSTALLATION_ID_KEY, encryptedInstallationId)
                .putString(WRITE_CREDENTIAL_KEY, encryptedWriteCredential)
                .putString(DELETION_CREDENTIAL_KEY, encryptedDeletionCredential)
                .commit(),
        ) { "Unable to persist telemetry installation credentials" }
    }

    override fun clear() {
        preferences.edit().clear().commit()
        runCatching {
            keyStore().takeIf { it.containsAlias(KEY_ALIAS) }?.deleteEntry(KEY_ALIAS)
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return listOf(cipher.iv, ciphertext)
            .joinToString(ENCRYPTED_VALUE_SEPARATOR) { Base64.getEncoder().encodeToString(it) }
    }

    private fun decrypt(value: String): String {
        val parts = value.split(ENCRYPTED_VALUE_SEPARATOR, limit = 2)
        require(parts.size == 2) { "Malformed encrypted telemetry credential" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Telemetry credential key is unavailable")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "iaido-telemetry-installation-v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val ENCRYPTED_VALUE_SEPARATOR = ":"
        const val PREFERENCES_NAME = "telemetry-installation"
        const val INSTALLATION_ID_KEY = "installation_id_ciphertext"
        const val WRITE_CREDENTIAL_KEY = "write_credential_ciphertext"
        const val DELETION_CREDENTIAL_KEY = "deletion_credential_ciphertext"
    }
}
