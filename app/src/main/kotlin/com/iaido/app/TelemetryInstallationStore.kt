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

/**
 * An installation whose write credential the collector rejected. Its deletion credential is kept
 * so data already uploaded under it can still be deleted from Settings.
 */
data class RetiredInstallation(
    val installationId: String,
    val deletionCredential: String,
) {
    init {
        require(installationId.isNotBlank())
        require(deletionCredential.isNotBlank())
    }
}

internal interface InstallationCredentialStorage {
    fun read(): TelemetryInstallation?
    fun write(installation: TelemetryInstallation)

    /** Removes only the active installation, keeping any retired one. */
    fun clearActive()
    fun readRetired(): RetiredInstallation?
    fun writeRetired(retired: RetiredInstallation)

    /** Removes everything, the retired installation included. */
    fun clear()
}

class TelemetryInstallationStore internal constructor(
    private val storage: InstallationCredentialStorage,
) {
    constructor(context: Context) : this(AndroidKeystoreCredentialStorage(context.applicationContext))

    /**
     * Returns the stored installation or runs [provision] (a network call) and stores its result.
     * Serialised process-wide, not per instance: the worker and the Settings screen each build their
     * own store, and both provisioning at once would orphan one server installation.
     */
    fun getOrCreateInstallation(provision: () -> TelemetryInstallation): TelemetryInstallation =
        synchronized(PROVISIONING_LOCK) {
            storage.read() ?: provision().also(storage::write)
        }

    /** The stored, server-issued installation, or null. Never creates or persists anything. */
    fun installation(): TelemetryInstallation? = storage.read()

    fun writeCredential(plane: TelemetryPlane): String? = when (plane) {
        TelemetryPlane.DIAGNOSTICS, TelemetryPlane.RESEARCH -> storage.read()?.writeCredential
    }

    fun deletionCredential(): String? = storage.read()?.deletionCredential

    /** The installation retired by [invalidateWriteCredential], if any. */
    fun retiredInstallation(): RetiredInstallation? = storage.readRetired()

    /** True while a rejected installation is waiting to be replaced by a freshly provisioned one. */
    fun needsReprovisioning(): Boolean = storage.read() == null && storage.readRetired() != null

    /**
     * Called when the collector rejects the write credential (401). The active installation is
     * retired, not wiped: its id and deletion credential stay available so "Delete uploaded data"
     * can still reach what was uploaded under it, while uploads wait for fresh provisioning. A
     * single retired slot is kept; a later retirement replaces it.
     *
     * Only [rejectedInstallationId] is retired: a late 401 from another plane's upload under an
     * installation that was already replaced is a no-op, so it can neither retire the healthy
     * replacement nor overwrite the retired slot.
     */
    fun invalidateWriteCredential(rejectedInstallationId: String) = synchronized(PROVISIONING_LOCK) {
        val current = storage.read() ?: return@synchronized
        if (current.installationId != rejectedInstallationId) return@synchronized
        storage.writeRetired(RetiredInstallation(current.installationId, current.deletionCredential))
        storage.clearActive()
    }

    fun clear() = synchronized(PROVISIONING_LOCK) { storage.clear() }

    private companion object {
        val PROVISIONING_LOCK = Any()
    }
}

/**
 * Installation to stamp on a newly recorded envelope, or null when none has been issued by the
 * collector yet - the caller then drops the event. Credentials are only ever created by
 * [TelemetryTransport.provision]: a locally invented installation would be persisted, reused by
 * provisioning, and rejected by the collector with 401 forever.
 */
internal fun TelemetryInstallationStore.envelopeInstallation(): TelemetryInstallation? = installation()

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

    override fun clearActive() {
        check(
            preferences.edit()
                .remove(INSTALLATION_ID_KEY)
                .remove(WRITE_CREDENTIAL_KEY)
                .remove(DELETION_CREDENTIAL_KEY)
                .commit(),
        ) { "Unable to clear telemetry installation credentials" }
    }

    override fun readRetired(): RetiredInstallation? {
        val encryptedInstallationId = preferences.getString(RETIRED_INSTALLATION_ID_KEY, null) ?: return null
        val encryptedDeletionCredential = preferences.getString(RETIRED_DELETION_CREDENTIAL_KEY, null) ?: return null
        return try {
            RetiredInstallation(decrypt(encryptedInstallationId), decrypt(encryptedDeletionCredential))
        } catch (_: Exception) {
            null
        }
    }

    override fun writeRetired(retired: RetiredInstallation) {
        val encryptedInstallationId = encrypt(retired.installationId)
        val encryptedDeletionCredential = encrypt(retired.deletionCredential)
        check(
            preferences.edit()
                .putString(RETIRED_INSTALLATION_ID_KEY, encryptedInstallationId)
                .putString(RETIRED_DELETION_CREDENTIAL_KEY, encryptedDeletionCredential)
                .commit(),
        ) { "Unable to persist retired telemetry installation" }
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
        const val RETIRED_INSTALLATION_ID_KEY = "retired_installation_id_ciphertext"
        const val RETIRED_DELETION_CREDENTIAL_KEY = "retired_deletion_credential_ciphertext"
    }
}
