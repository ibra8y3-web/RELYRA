package org.relyra.app.core.messaging

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.storage.SecureStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

data class MlsCommitBundle(
    val commit: ByteArray,
    val welcome: ByteArray?,
    val epoch: Long,
)

data class MlsProcessedMessage(
    val kind: String,
    val groupId: String,
    val epoch: Long,
    val application: ByteArray?,
)

interface GroupMlsProvider {
    suspend fun createGroup(groupId: String, relyraPublicId: String): Long
    suspend fun generateKeyPackage(relyraPublicId: String): ByteArray
    /** Returns the validated RELYRA identity credential embedded in a signed KeyPackage. */
    suspend fun keyPackageIdentity(keyPackage: ByteArray): ByteArray
    suspend fun addMembers(groupId: String, keyPackages: List<ByteArray>): MlsCommitBundle
    suspend fun joinGroup(groupId: String, welcome: ByteArray, relyraPublicId: String): Long
    suspend fun encrypt(groupId: String, plaintext: ByteArray): ByteArray
    suspend fun processMessage(message: ByteArray): MlsProcessedMessage
    suspend fun removeMember(groupId: String, relyraPublicId: String): MlsCommitBundle
    suspend fun epoch(groupId: String): Long
    suspend fun members(groupId: String): List<String>
    suspend fun close()
}

/**
 * Per-installation OpenMLS engine. A single mutex serializes all JNI operations because
 * OpenMLS state transitions and the SQLCipher storage transaction must not race.
 */
class MlsEngine(
    context: Context,
    private val secure: SecureStore,
    private val signIdentity: (ByteArray) -> ByteArray?,
) : GroupMlsProvider {
    private val appContext = context.applicationContext
    private val initLock = Mutex()
    private val nativeLock = Mutex()
    @Volatile private var initialized = false
    private val databaseFile = File(File(appContext.filesDir, "mls"), "groups.db")

    private suspend fun ensureInitialized() {
        if (initialized) return
        initLock.withLock {
            if (initialized) return@withLock
            databaseFile.parentFile?.mkdirs()
            val key = secure.databaseKey()
            try {
                MlsNative.nativeInit(databaseFile.absolutePath, key)
                initialized = true
            } finally {
                key.fill(0)
            }
        }
    }

    private suspend fun <T> runNative(operation: () -> T): T = withContext(Dispatchers.IO) {
        nativeLock.withLock {
            ensureInitialized()
            operation()
        }
    }

    private fun groupIdBytes(groupId: String): ByteArray {
        require(groupId.length in 1..128)
        return groupId.toByteArray(StandardCharsets.UTF_8)
    }

    private fun credentialIdentity(relyraPublicId: String): ByteArray {
        val deviceId = secure.deviceId()
        val payload = MlsCredentialCodec.signingPayload(relyraPublicId, deviceId)
        val signature = signIdentity(payload)
            ?: throw IllegalStateException("RELYRA identity signing key is unavailable")
        return MlsCredentialCodec.encode(relyraPublicId, deviceId, signature)
    }

    override suspend fun createGroup(groupId: String, relyraPublicId: String): Long = runNative {
        MlsNative.nativeCreateGroup(groupIdBytes(groupId), credentialIdentity(relyraPublicId))
            .also { check(it >= 0) { "OpenMLS could not create the group" } }
    }

    override suspend fun generateKeyPackage(relyraPublicId: String): ByteArray = runNative {
        MlsNative.nativeGenerateKeyPackage(credentialIdentity(relyraPublicId))
            .also { check(it.isNotEmpty()) { "OpenMLS returned an empty KeyPackage" } }
    }

    override suspend fun keyPackageIdentity(keyPackage: ByteArray): ByteArray = runNative {
        MlsNative.nativeKeyPackageIdentity(keyPackage).also {
            require(MlsCredentialCodec.decode(it) != null) { "KeyPackage contains an invalid RELYRA credential" }
        }
    }

    override suspend fun addMembers(groupId: String, keyPackages: List<ByteArray>): MlsCommitBundle = runNative {
        require(keyPackages.isNotEmpty() && keyPackages.size <= 200)
        val json = JSONObject(MlsNative.nativeAddMembers(groupIdBytes(groupId), keyPackages.toTypedArray()))
        MlsCommitBundle(
            commit = Base64.getDecoder().decode(json.getString("commit")),
            welcome = json.optString("welcome").takeIf { it.isNotBlank() && it != "null" }
                ?.let(Base64.getDecoder()::decode),
            epoch = json.getLong("epoch"),
        )
    }

    override suspend fun joinGroup(groupId: String, welcome: ByteArray, relyraPublicId: String): Long = runNative {
        MlsNative.nativeJoinGroup(groupIdBytes(groupId), welcome, credentialIdentity(relyraPublicId))
            .also { check(it >= 0) { "OpenMLS could not process the Welcome" } }
    }

    override suspend fun encrypt(groupId: String, plaintext: ByteArray): ByteArray = runNative {
        MlsNative.nativeEncrypt(groupIdBytes(groupId), plaintext)
            .also { check(it.isNotEmpty()) { "OpenMLS returned an empty ciphertext" } }
    }

    override suspend fun processMessage(message: ByteArray): MlsProcessedMessage = runNative {
        val json = JSONObject(MlsNative.nativeProcessMessage(message))
        val app = json.optString("application").takeIf { it.isNotBlank() && it != "null" }
            ?.let(Base64.getDecoder()::decode)
        MlsProcessedMessage(
            kind = json.getString("kind"),
            groupId = Base64.getDecoder().decode(json.getString("group_id"))
                .toString(StandardCharsets.UTF_8),
            epoch = json.getLong("epoch"),
            application = app,
        )
    }

    override suspend fun removeMember(groupId: String, relyraPublicId: String): MlsCommitBundle = runNative {
        val json = JSONObject(
            MlsNative.nativeRemoveMember(groupIdBytes(groupId), credentialIdentity(relyraPublicId))
        )
        MlsCommitBundle(
            commit = Base64.getDecoder().decode(json.getString("commit")),
            welcome = json.optString("welcome").takeIf { it.isNotBlank() && it != "null" }
                ?.let(Base64.getDecoder()::decode),
            epoch = json.getLong("epoch"),
        )
    }

    override suspend fun epoch(groupId: String): Long = runNative {
        MlsNative.nativeEpoch(groupIdBytes(groupId)).also {
            check(it >= 0) { "MLS group is not present in local storage" }
        }
    }

    override suspend fun members(groupId: String): List<String> = runNative {
        val json = JSONArray(MlsNative.nativeMembers(groupIdBytes(groupId)))
        List(json.length()) { index -> json.getString(index) }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        nativeLock.withLock {
            initLock.withLock {
                if (initialized) {
                    MlsNative.nativeClose()
                    initialized = false
                }
            }
        }
    }
}
