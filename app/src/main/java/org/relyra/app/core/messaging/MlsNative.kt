package org.relyra.app.core.messaging

/**
 * JNI boundary for the pinned OpenMLS native engine.
 *
 * The native layer persists its OpenMLS state in a separate SQLCipher database
 * under app-private storage, keyed by the Keystore-wrapped application DB key.
 */
internal object MlsNative {
    init {
        System.loadLibrary("relyra_mls")
    }

    @JvmStatic external fun nativeInit(path: String, databaseKey: ByteArray)
    @JvmStatic external fun nativeCreateGroup(groupId: ByteArray, credentialIdentity: ByteArray): Long
    @JvmStatic external fun nativeGenerateKeyPackage(credentialIdentity: ByteArray): ByteArray
    @JvmStatic external fun nativeKeyPackageIdentity(keyPackage: ByteArray): ByteArray
    @JvmStatic external fun nativeAddMembers(groupId: ByteArray, packages: Array<ByteArray>): String
    @JvmStatic external fun nativeJoinGroup(groupId: ByteArray, welcome: ByteArray, credentialIdentity: ByteArray): Long
    @JvmStatic external fun nativeEncrypt(groupId: ByteArray, plaintext: ByteArray): ByteArray
    @JvmStatic external fun nativeProcessMessage(message: ByteArray): String
    @JvmStatic external fun nativeRemoveMember(groupId: ByteArray, credentialIdentity: ByteArray): String
    @JvmStatic external fun nativeProposeAddMember(groupId: ByteArray, keyPackage: ByteArray): ByteArray
    @JvmStatic external fun nativeProposeRemoveMember(groupId: ByteArray, credentialIdentity: ByteArray): ByteArray
    @JvmStatic external fun nativeProcessProposal(message: ByteArray, expectedPublicId: String, allowedTargetPublicIds: Array<String>, allowAdd: Boolean, allowRemove: Boolean): Long
    @JvmStatic external fun nativeCommitPendingProposals(groupId: ByteArray): String
    @JvmStatic external fun nativePendingProposalCount(groupId: ByteArray): Long
    @JvmStatic external fun nativeEpoch(groupId: ByteArray): Long
    @JvmStatic external fun nativeMembers(groupId: ByteArray): String
    @JvmStatic external fun nativeClose()
}
