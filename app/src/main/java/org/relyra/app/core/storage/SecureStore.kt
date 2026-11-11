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

    fun saveSeed(seed: ByteArray) = write(SEED, seed)
    fun loadSeed(): ByteArray? = read(SEED)
    fun clearSeed() { prefs.edit().remove(SEED).commit() }

    fun clearPreKeys() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("prekey_") }.forEach(edit::remove)
        check(edit.commit()) { "secure prekey cleanup failed" }
    }

    /** Used only when a backup is applied: replaces both secrets, re-wrapped by this device's Keystore key. */
    fun restoreKeys(seed: ByteArray?, dbKey: ByteArray) {
        write(DB_KEY, dbKey)
        if (seed != null && seed.isNotEmpty()) saveSeed(seed) else clearSeed()
    }

    private companion object {
        const val DB_KEY = "db_key_v1"
        const val SEED = "identity_seed_v1"
    }
}
