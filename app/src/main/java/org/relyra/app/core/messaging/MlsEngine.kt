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
    override suspend fun createGroup(groupId: String, relyraPublicId: String): Long
    override suspend fun generateKeyPackage(relyraPublicId: String): ByteArray
    override suspend fun keyPackageIdentity(keyPackage: ByteArray): String
    override suspend fun addMembers(groupId: String, keyPackages: List<ByteArray>): MlsCommitBundle
    override suspend fun joinGroup(groupId: String, welcome: ByteArray): Long
    override suspend fun encrypt(groupId: String, plaintext: ByteArray): ByteArray
    override suspend fun processMessage(message: ByteArray): MlsProcessedMessage
    override suspend fun removeMember(groupId: String, relyraPublicId: String): MlsCommitBundle
    override suspend fun epoch(groupId: String): Long
    override suspend fun members(groupId: String): List<String>
    override suspend fun close()
}

class MlsEngine(
    context: Context,
    private val secure: SecureStore,
) : GroupMlsProvider {
    private val appContext = context.applicationContext
    private val initLock = Mutex()
    @Volatile private var initialized = false
    private val databaseFile = File(File(appContext.filesDir, "mls"), "groups.db")

    private suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
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

    private fun groupIdBytes(groupId: String): ByteArray {
        require(groupId.length in 1..128)
        return groupId.toByteArray(StandardCharsets.UTF_8)
    }

    private fun credentialIdentity(relyraPublicId: String): ByteArray {
        require(relyraPublicId.isNotBlank() && relyraPublicId.length <= 200)
        // A KeyPackage is delivered inside an authenticated RELYRA contact session.
        // The receiver additionally compares this credential to the authenticated sender ID.
        return "RELYRA-MLS-V1:$relyraPublicId:primary".toByteArray(StandardCharsets.UTF_8)
    }

    suspend fun createGroup(groupId: String, relyraPublicId: String): Long = withContext(Dispatchers.IO) {
        ensureInitialized()
        MlsNative.nativeCreateGroup(groupIdBytes(groupId), credentialIdentity(relyraPublicId))
            .also { check(it >= 0) { "OpenMLS could not create the group" } }
    }

    suspend fun generateKeyPackage(relyraPublicId: String): ByteArray = withContext(Dispatchers.IO) {
        ensureInitialized()
        MlsNative.nativeGenerateKeyPackage(credentialIdentity(relyraPublicId))
            .also { check(it.isNotEmpty()) { "OpenMLS returned an empty KeyPackage" } }
    }

    suspend fun keyPackageIdentity(keyPackage: ByteArray): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        MlsNative.nativeKeyPackageIdentity(keyPackage).toString(StandardCharsets.UTF_8)
    }

    suspend fun addMembers(groupId: String, keyPackages: List<ByteArray>): MlsCommitBundle =
        withContext(Dispatchers.IO) {
            require(keyPackages.isNotEmpty() && keyPackages.size <= 200)
            ensureInitialized()
            val json = JSONObject(MlsNative.nativeAddMembers(groupIdBytes(groupId), keyPackages.toTypedArray()))
            MlsCommitBundle(
                commit = Base64.getDecoder().decode(json.getString("commit")),
                welcome = json.optString("welcome").takeIf { it.isNotBlank() && it != "null" }
                    ?.let(Base64.getDecoder()::decode),
                epoch = json.getLong("epoch"),
            )
        }

    suspend fun joinGroup(groupId: String, welcome: ByteArray): Long = withContext(Dispatchers.IO) {
        ensureInitialized()
        MlsNative.nativeJoinGroup(groupIdBytes(groupId), welcome)
            .also { check(it >= 0) { "OpenMLS could not process the Welcome" } }
    }

    suspend fun encrypt(groupId: String, plaintext: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        ensureInitialized()
        MlsNative.nativeEncrypt(groupIdBytes(groupId), plaintext)
            .also { check(it.isNotEmpty()) { "OpenMLS returned an empty ciphertext" } }
    }

    suspend fun processMessage(message: ByteArray): MlsProcessedMessage = withContext(Dispatchers.IO) {
        ensureInitialized()
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

    suspend fun removeMember(groupId: String, relyraPublicId: String): MlsCommitBundle =
        withContext(Dispatchers.IO) {
            ensureInitialized()
            val json = JSONObject(MlsNative.nativeRemoveMember(
                groupIdBytes(groupId),
                credentialIdentity(relyraPublicId),
            ))
            MlsCommitBundle(
                commit = Base64.getDecoder().decode(json.getString("commit")),
                welcome = json.optString("welcome").takeIf { it.isNotBlank() && it != "null" }
                    ?.let(Base64.getDecoder()::decode),
                epoch = json.getLong("epoch"),
            )
        }

    suspend fun epoch(groupId: String): Long = withContext(Dispatchers.IO) {
        ensureInitialized()
        MlsNative.nativeEpoch(groupIdBytes(groupId)).also {
            check(it >= 0) { "MLS group is not present in local storage" }
        }
    }

    suspend fun members(groupId: String): List<String> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val json = JSONArray(MlsNative.nativeMembers(groupIdBytes(groupId)))
        List(json.length()) { index -> json.getString(index) }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (initialized) {
                MlsNative.nativeClose()
                initialized = false
            }
        }
    }
}
