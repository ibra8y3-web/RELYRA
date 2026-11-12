package org.relyra.app.core.storage

import android.content.Context
import org.relyra.app.core.crypto.KeystoreVault
import java.security.SecureRandom
import java.util.Base64

/** Holds the database passphrase and identity seed, both wrapped by the Keystore master key. */
class SecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("relyra_secure", Context.MODE_PRIVATE)

    private fun read(name: String): ByteArray? =
        prefs.getString(name, null)?.let { KeystoreVault.unwrap(Base64.getDecoder().decode(it), name.toByteArray()) }

    private fun write(name: String, value: ByteArray) {
        val blob = Base64.getEncoder().encodeToString(KeystoreVault.wrap(value, name.toByteArray()))
        check(prefs.edit().putString(name, blob).commit()) { "secure store write failed" }
    }

    fun databaseKey(): ByteArray {
        read(DB_KEY)?.let { return it }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        write(DB_KEY, key)
        return key
    }

    /** Private X3DH prekeys are individually wrapped with Android Keystore and never stored as plaintext in Room. */
    fun savePreKeyPrivate(keyId: String, privateKey: ByteArray) {
        require(keyId.matches(Regex("[a-z-]+:[0-9]+")) && privateKey.size == 32)
        write("prekey_" + keyId.replace(':', '_'), privateKey)
    }

    fun loadPreKeyPrivate(keyId: String): ByteArray? {
        if (!keyId.matches(Regex("[a-z-]+:[0-9]+"))) return null
        return read("prekey_" + keyId.replace(':', '_'))
    }

    fun deletePreKeyPrivate(keyId: String) {
        if (keyId.matches(Regex("[a-z-]+:[0-9]+"))) {
            prefs.edit().remove("prekey_" + keyId.replace(':', '_')).commit()
        }
    }

    /** Stable random identifier for this app installation; encrypted with the Android Keystore key. */
    fun deviceId(): String {
        val current = read(DEVICE_ID)?.toString(Charsets.UTF_8)
        if (current != null && runCatching { java.util.UUID.fromString(current).toString() == current }.getOrDefault(false)) {
            return current
        }
        val created = java.util.UUID.randomUUID().toString()
        write(DEVICE_ID, created.toByteArray(Charsets.UTF_8))
        return created
    }


    /** Device-scoped Ed25519 seed. Wrapped by this installation's non-exportable Keystore key. */
    fun saveDeviceSigningSeed(deviceId: String, seed: ByteArray) {
        require(java.util.UUID.fromString(deviceId).toString() == deviceId && seed.size == 32)
        write("device_sign_" + deviceId, seed)
    }

    fun loadDeviceSigningSeed(deviceId: String): ByteArray? {
        if (runCatching { java.util.UUID.fromString(deviceId).toString() == deviceId }.getOrDefault(false).not()) return null
        return read("device_sign_" + deviceId)?.takeIf { it.size == 32 }
    }

    /** Device-scoped X25519 private key. It is never exported or written to Room. */
    fun saveDeviceEncryptionSecret(deviceId: String, secret: ByteArray) {
        require(java.util.UUID.fromString(deviceId).toString() == deviceId && secret.size == 32)
        write("device_enc_" + deviceId, secret)
    }

    fun loadDeviceEncryptionSecret(deviceId: String): ByteArray? {
        if (!runCatching { java.util.UUID.fromString(deviceId).toString() == deviceId }.getOrDefault(false)) return null
        return read("device_enc_" + deviceId)?.takeIf { it.size == 32 }
    }

    fun deleteDeviceSecrets(deviceId: String) {
        if (!runCatching { java.util.UUID.fromString(deviceId).toString() == deviceId }.getOrDefault(false)) return
        check(prefs.edit().remove("device_sign_" + deviceId).remove("device_enc_" + deviceId).commit()) {
            "device key cleanup failed"
        }
    }

    fun saveSeed(seed: ByteArray) = write(SEED, seed)
    fun loadSeed(): ByteArray? = read(SEED)
    fun clearSeed() { prefs.edit().remove(SEED).commit() }

    fun clearPreKeys() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("prekey_") }.forEach { edit.remove(it) }
        check(edit.commit()) { "secure prekey cleanup failed" }
    }

    /** Exports private prekeys only into the already passphrase-encrypted backup stream. */
    fun exportPreKeys(): Map<String, ByteArray> = prefs.all.keys
        .filter { it.startsWith("prekey_") }
        .mapNotNull { storedName ->
            val keyId = storedName.removePrefix("prekey_").replaceFirst("_", ":")
            read(storedName)?.let { keyId to it }
        }.toMap()

    /** Used only when a backup is applied: secrets are re-wrapped by this device's Keystore key. */
    fun restoreKeys(seed: ByteArray?, dbKey: ByteArray, preKeys: Map<String, ByteArray> = emptyMap()) {
        require(dbKey.size == 32) { "invalid database key" }
        require(seed == null || seed.isEmpty() || seed.size == 32) { "invalid identity seed" }
        require(preKeys.size <= 10_000 && preKeys.all { (id, value) ->
            id.matches(Regex("[a-z-]+:[0-9]+")) && value.size == 32
        }) { "invalid private prekey backup" }
        // Commit root secrets and restored private prekeys in one SharedPreferences transaction.
        // This avoids a partially restored keyset if one individual write fails.
        val dbWrapped = Base64.getEncoder().encodeToString(
            KeystoreVault.wrap(dbKey, DB_KEY.toByteArray())
        )
        val seedWrapped = seed?.takeIf { it.isNotEmpty() }?.let {
            Base64.getEncoder().encodeToString(KeystoreVault.wrap(it, SEED.toByteArray()))
        }
        val editor = prefs.edit().putString(DB_KEY, dbWrapped)
        if (seedWrapped == null) editor.remove(SEED) else editor.putString(SEED, seedWrapped)
        prefs.all.keys.filter { it.startsWith("prekey_") }.forEach { editor.remove(it) }
        preKeys.forEach { (id, value) ->
            val name = "prekey_" + id.replace(':', '_')
            editor.putString(name, Base64.getEncoder().encodeToString(KeystoreVault.wrap(value, name.toByteArray())))
        }
        check(editor.commit()) { "secure key restore failed" }
    }

    private companion object {
        const val DB_KEY = "db_key_v1"
        const val SEED = "identity_seed_v1"
        const val DEVICE_ID = "device_id_v1"
    }
}
