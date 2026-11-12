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
    suspend fun proposeAddMember(groupId: String, keyPackage: ByteArray): ByteArray
    suspend fun proposeRemoveMember(groupId: String, relyraPublicId: String): ByteArray
    /** Stores a native RFC 9420 proposal only when its MLS credential matches the authenticated envelope sender. */
    suspend fun processProposal(message: ByteArray, messageId: String, expectedSenderPublicId: String, allowedTargetPublicIds: Set<String>, allowAdd: Boolean, allowRemove: Boolean): Long
    suspend fun commitPendingProposals(groupId: String): MlsPreparedCommit
    suspend fun pendingCommitInfo(groupId: String): MlsPreparedCommit?
    suspend fun mergePendingCommit(groupId: String): Long
    suspend fun pendingProposalCount(groupId: String): Int
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
        val encodedMembers = JSONArray(MlsNative.nativeMembers(groupIdBytes(groupId)))
        val targetCredential = (0 until encodedMembers.length())
            .mapNotNull { index ->
                runCatching { Base64.getDecoder().decode(encodedMembers.getString(index)) }.getOrNull()
            }
            .firstOrNull { MlsCredentialCodec.verify(it, relyraPublicId) }
            ?: throw IllegalArgumentException("The requested RELYRA member is not present in the MLS group")
        val json = JSONObject(MlsNative.nativeRemoveMember(groupIdBytes(groupId), targetCredential))
        MlsCommitBundle(
            commit = Base64.getDecoder().decode(json.getString("commit")),
            welcome = json.optString("welcome").takeIf { it.isNotBlank() && it != "null" }
                ?.let(Base64.getDecoder()::decode),
            epoch = json.getLong("epoch"),
        )
    }

    override suspend fun proposeAddMember(groupId: String, keyPackage: ByteArray): ByteArray = runNative {
        require(keyPackage.isNotEmpty() && keyPackage.size <= 64 * 1024)
        MlsNative.nativeProposeAddMember(groupIdBytes(groupId), keyPackage)
            .also { check(it.isNotEmpty()) { "OpenMLS returned an empty Add Proposal" } }
    }

    override suspend fun proposeRemoveMember(groupId: String, relyraPublicId: String): ByteArray = runNative {
        val encodedMembers = JSONArray(MlsNative.nativeMembers(groupIdBytes(groupId)))
        val targetCredential = (0 until encodedMembers.length())
            .mapNotNull { index ->
                runCatching { Base64.getDecoder().decode(encodedMembers.getString(index)) }.getOrNull()
            }
            .firstOrNull { MlsCredentialCodec.verify(it, relyraPublicId) }
            ?: throw IllegalArgumentException("The requested RELYRA member is not present in the MLS group")
        MlsNative.nativeProposeRemoveMember(groupIdBytes(groupId), targetCredential)
            .also { check(it.isNotEmpty()) { "OpenMLS returned an empty Remove Proposal" } }
    }

    override suspend fun processProposal(
        message: ByteArray,
        messageId: String,
        expectedSenderPublicId: String,
        allowedTargetPublicIds: Set<String>,
        allowAdd: Boolean,
        allowRemove: Boolean,
    ): Long = runNative {
        require(allowedTargetPublicIds.isNotEmpty() && allowedTargetPublicIds.size <= 200)
        require(allowAdd.xor(allowRemove))
        MlsNative.nativeProcessProposal(
            message, messageId, expectedSenderPublicId, allowedTargetPublicIds.toTypedArray(), allowAdd, allowRemove,
        )
            .also { check(it >= 0) { "OpenMLS rejected the proposal or its sender identity" } }
    }

    override suspend fun commitPendingProposals(groupId: String): MlsPreparedCommit = runNative {
        decodePreparedCommit(MlsNative.nativeCommitPendingProposals(groupIdBytes(groupId)))
    }

    override suspend fun pendingCommitInfo(groupId: String): MlsPreparedCommit? = runNative {
        val json = MlsNative.nativePendingCommitInfo(groupIdBytes(groupId))
        if (json == "null" || json.isBlank()) null else decodePreparedCommit(json)
    }

    override suspend fun mergePendingCommit(groupId: String): Long = runNative {
        MlsNative.nativeMergePendingCommit(groupIdBytes(groupId)).also {
            check(it >= 0) { "OpenMLS could not merge the durable pending Commit" }
        }
    }

    private fun decodePreparedCommit(raw: String): MlsPreparedCommit {
        val json = JSONObject(raw)
        fun stringSet(name: String) = buildSet {
            val array = json.optJSONArray(name) ?: return@buildSet
            for (index in 0 until array.length()) add(array.getString(index).lowercase())
        }
        return MlsPreparedCommit(
            commit = Base64.getDecoder().decode(json.getString("commit")),
            welcome = json.optString("welcome").takeIf { it.isNotBlank() && it != "null" }
                ?.let(Base64.getDecoder()::decode),
            epoch = json.getLong("epoch"),
            removedIds = stringSet("removed_ids"),
            addedIds = stringSet("added_ids"),
        )
    }

    override suspend fun pendingProposalCount(groupId: String): Int = runNative {
        MlsNative.nativePendingProposalCount(groupIdBytes(groupId)).also {
            check(it >= 0) { "OpenMLS could not read pending proposals" }
        }.toInt()
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

    /** Holds the native-operation mutex while a consistent on-disk MLS snapshot is copied. */
    suspend fun <T> withBackupBarrier(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        nativeLock.lock()
        try {
            if (initialized) {
                MlsNative.nativeClose()
                initialized = false
            }
            block()
        } finally {
            nativeLock.unlock()
        }
    }

    override suspend fun close() {
        withBackupBarrier { Unit }
    }
}
