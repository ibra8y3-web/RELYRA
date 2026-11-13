package org.relyra.app.core.integrations

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import org.matrix.rustcomponents.sdk.use
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Matrix Rust SDK adapter. The SDK owns Olm/Megolm encryption, device keys, room-key
 * sharing, and event decryption. Its SQLite crypto store is encrypted with RELYRA's
 * database key; the session tokens are separately wrapped by an Android Keystore key.
 */
class MatrixRustE2eeClient(
    context: Context,
    private val databaseKey: () -> ByteArray,
) {
    private val app = context.applicationContext
    private val dataDir = File(app.noBackupFilesDir, "matrix-rust-sdk").apply { mkdirs() }
    private val cacheDir = File(app.cacheDir, "matrix-rust-sdk").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("relyra_matrix_session", Context.MODE_PRIVATE)
    @Volatile private var client: Client? = null
    @Volatile private var syncStarted = false

    suspend fun login(homeserver: String, username: String, password: String): String = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotEmpty())
        val base = normalizeHomeserver(homeserver)
        val sdk = buildClient(base)
        try {
            sdk.login(username = username, password = password, initialDeviceName = "RELYRA Android", deviceId = null)
            val session = sdk.session()
            persistSession(session)
            client = sdk
            syncStarted = false
            session.userId
        } catch (t: Throwable) {
            runCatching { sdk.close() }
            throw t
        }
    }

    suspend fun restoreSavedSession(): String = withContext(Dispatchers.IO) {
        val saved = loadSession() ?: error("No saved Matrix session")
        val sdk = buildClient(saved.homeserverUrl)
        try {
            sdk.restoreSession(saved)
            client = sdk
            syncStarted = false
            saved.userId
        } catch (t: Throwable) {
            runCatching { sdk.close() }
            throw t
        }
    }

    suspend fun startSync() = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        if (!syncStarted) {
            sdk.syncService().withOfflineMode().finish().startSync().getOrThrow()
            syncStarted = true
        }
    }

    suspend fun joinRoom(roomIdOrAlias: String): String = withContext(Dispatchers.IO) {
        require(roomIdOrAlias.isNotBlank() && roomIdOrAlias.length <= 512)
        requireClient().joinRoomByIdOrAlias(roomIdOrAlias, emptyList()).use { it.id() }
    }

    suspend fun sendEncrypted(roomId: String, body: String) = withContext(Dispatchers.IO) {
        require(roomId.startsWith("!") && roomId.length <= 255)
        require(body.isNotBlank() && body.length <= 65536)
        val room = requireClient().getRoom(roomId) ?: error("Matrix room is not available in the restored SDK store")
        room.use {
            it.timeline().use { timeline ->
                messageEventContentFromMarkdown(body).use { content ->
                    timeline.send(content)
                }
            }
        }
    }

    fun disconnect() {
        syncStarted = false
        client?.let { runCatching { it.close() } }
        client = null
    }

    private suspend fun buildClient(homeserver: String): Client {
        val key = databaseKey()
        require(key.size == 32) { "RELYRA database key must be 256 bits for Matrix SDK storage" }
        val store = SqliteStoreBuilder(dataPath = dataDir.absolutePath, cachePath = cacheDir.absolutePath).key(key)
        key.fill(0)
        return ClientBuilder()
            .serverNameOrHomeserverUrl(homeserver)
            .sqliteStore(store)
            .autoEnableCrossSigning(true)
            .autoEnableBackups(true)
            .build()
    }

    private fun requireClient() = client ?: error("Sign in or restore a Matrix E2EE session first")

    private fun normalizeHomeserver(value: String): String {
        val uri = java.net.URI(value.trim())
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Matrix homeserver must be an HTTPS base URL"
        }
        return uri.toASCIIString().trimEnd('/')
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(SESSION_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(SESSION_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun persistSession(session: Session) {
        val json = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserverUrl", session.homeserverUrl)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun loadSession(): Session? = runCatching {
        val packed = prefs.getString("session", null) ?: return null
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        Session(
            accessToken = json.getString("accessToken"),
            refreshToken = json.optString("refreshToken").takeIf { it.isNotBlank() },
            userId = json.getString("userId"),
            deviceId = json.getString("deviceId"),
            homeserverUrl = json.getString("homeserverUrl"),
            slidingSyncVersion = SlidingSyncVersion.NATIVE,
            oauthData = null,
        )
    }.getOrNull()

    companion object {
        private const val SESSION_KEY_ALIAS = "relyra.matrix.session.v1"
    }
}
