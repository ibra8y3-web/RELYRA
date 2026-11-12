package org.relyra.app.core.devices

import android.os.Build
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.crypto.PayloadCrypto
import org.relyra.app.core.database.*
import org.relyra.app.core.identity.IdentityRepository
import org.relyra.app.core.storage.SecureStore
import java.io.*
import java.security.SecureRandom
import java.util.Base64

/** Signed, expiring device enrollment and per-installation keys. */
class DeviceManager(private val db: AppDatabase, private val secure: SecureStore, private val identities: IdentityRepository) {
    companion object {
        private const val REQUEST = "RELYRA-DEVICE-REQUEST-V1"
        private const val APPROVAL = "RELYRA-DEVICE-APPROVAL-V1"
        private const val REVOCATION = "RELYRA-DEVICE-REVOCATION-V1"
        private const val ROTATION = "RELYRA-DEVICE-KEY-ROTATION-V1"
        private const val TTL = 300_000L
        private const val MAX = 8192
    }
    private fun b64(v: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(v)
    private fun un64(v: String) = Base64.getUrlDecoder().decode(v)
    private fun transcript(vararg values: String) = ByteArrayOutputStream().use { b ->
        DataOutputStream(b).use { o -> values.forEach(o::writeUTF) }; b.toByteArray()
    }
    private fun envelope(kind: String, body: ByteArray, sig: ByteArray): String {
        val raw = ByteArrayOutputStream().use { b -> DataOutputStream(b).use { o ->
            o.writeUTF(kind); o.writeInt(body.size); o.write(body); o.writeInt(sig.size); o.write(sig)
        }; b.toByteArray() }
        require(raw.size <= MAX)
        return "relyra-device://" + b64(raw)
    }
    private fun open(value: String, kind: String): Pair<ByteArray, ByteArray> {
        require(value.startsWith("relyra-device://") && value.length <= MAX * 2)
        val raw = un64(value.removePrefix("relyra-device://")); require(raw.size in 1..MAX)
        return DataInputStream(ByteArrayInputStream(raw)).use { i ->
            require(i.readUTF() == kind)
            val n = i.readInt(); require(n in 1..(MAX - 128) && n <= i.available())
            val body = ByteArray(n).also(i::readFully)
            val s = i.readInt(); require(s in 32..128 && s == i.available())
            body to ByteArray(s).also(i::readFully)
        }
    }
    private suspend fun log(type: String, detail: String) {
        db.eventDao().insert(SecurityEventEntity(timestamp=System.currentTimeMillis(), type=type, detail=detail))
    }
    suspend fun ensureLocalDevice(): TrustedDeviceEntity {
        val identity = checkNotNull(db.identityDao().get()) { "Create or restore your RELYRA identity first" }
        val id = secure.deviceId()
        db.trustedDeviceDao().get(id)?.let { row ->
            check(row.accountId == identity.publicId && row.status == TrustedDeviceStatus.APPROVED) { "This device is not trusted" }
            return row
        }
        // A device may bootstrap itself only when this account has no device records at all.
        // A second installation must complete the signed pairing flow; never auto-trust it here.
        check(db.trustedDeviceDao().countForAccount(identity.publicId) == 0) {
            "This installation is not trusted. Complete pairing from an already trusted device."
        }
        val signing = IdentityCrypto.generate()
        val enc = ByteArray(32).also { SecureRandom().nextBytes(it) }
        secure.saveDeviceSigningSeed(id, signing.seed); secure.saveDeviceEncryptionSecret(id, enc)
        val now = System.currentTimeMillis()
        val row = TrustedDeviceEntity(id, identity.publicId, Build.MODEL ?: "Android device",
            "Android " + Build.VERSION.SDK_INT, signing.publicKey, PayloadCrypto.publicKey(enc),
            TrustedDeviceStatus.PENDING, now, now, null, null, 1, 0)
        db.trustedDeviceDao().insertPending(row)
        check(db.trustedDeviceDao().approvePending(id, identity.publicId, now) == 1)
        log(EventType.DEVICE_APPROVED, id)
        return row.copy(status=TrustedDeviceStatus.APPROVED, approvedAt=now)
    }
    suspend fun createPairingRequest(displayName: String): String {
        val identity = checkNotNull(db.identityDao().get()) { "Restore the same RELYRA account first" }
        val id = secure.deviceId()
        val signingSeed = secure.loadDeviceSigningSeed(id) ?: IdentityCrypto.generate().seed.also { secure.saveDeviceSigningSeed(id,it) }
        val signing = IdentityCrypto.fromSeed(signingSeed)
        val enc = secure.loadDeviceEncryptionSecret(id) ?: ByteArray(32).also { SecureRandom().nextBytes(it); secure.saveDeviceEncryptionSecret(id,it) }
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        secure.saveDevicePairingChallenge(id, challenge)
        val created = System.currentTimeMillis()
        val body = transcript(REQUEST, identity.publicId, id, displayName.take(80), "Android " + Build.VERSION.SDK_INT,
            b64(signing.publicKey), b64(PayloadCrypto.publicKey(enc)), b64(challenge), created.toString())
        val sig = IdentityCrypto.sign(signingSeed, body)
        db.trustedDeviceDao().get(id) ?: db.trustedDeviceDao().insertPending(
            TrustedDeviceEntity(id, identity.publicId, displayName.take(80), "Android " + Build.VERSION.SDK_INT,
                signing.publicKey, PayloadCrypto.publicKey(enc), TrustedDeviceStatus.PENDING, created, created, null, null, 1, 0))
        log(EventType.DEVICE_ENROLLMENT_PENDING, id)
        return envelope(REQUEST, body, sig)
    }
    suspend fun approvePairingRequest(encoded: String): String {
        val identity = checkNotNull(db.identityDao().get())
        val rootSeed = checkNotNull(secure.loadSeed())
        val local = ensureLocalDevice()
        val (body, sig) = open(encoded, REQUEST)
        val values = DataInputStream(ByteArrayInputStream(body)).use { i ->
            require(i.readUTF() == REQUEST)
            List(8) { i.readUTF() }.also { require(i.available()==0) }
        }
        val account=values[0]; val id=values[1]; val name=values[2]; val platform=values[3]
        val signPub=un64(values[4]); val encPub=un64(values[5]); val challenge=un64(values[6]); val created=values[7].toLong()
        require(account==identity.publicId && id!=secure.deviceId() && name.isNotBlank())
        require(System.currentTimeMillis()-created in 0..TTL && signPub.size==32 && encPub.size==32 && challenge.size==32)
        require(IdentityCrypto.verify(signPub,sig,body)) { "Invalid enrollment signature" }
        val now=System.currentTimeMillis()
        val existing = db.trustedDeviceDao().get(id)
        if(existing==null) {
            db.trustedDeviceDao().insertPending(TrustedDeviceEntity(id,account,name,platform,signPub,encPub,
                TrustedDeviceStatus.PENDING,created,now,null,null,1,0))
            check(db.trustedDeviceDao().approvePending(id,account,now)==1)
        } else {
            require(existing.accountId==account && existing.signingPublicKey.contentEquals(signPub) &&
                existing.encryptionPublicKey.contentEquals(encPub) && existing.status==TrustedDeviceStatus.APPROVED) {
                "Device ID is already registered with different keys or a revoked state"
            }
        }
        val approval=transcript(APPROVAL,account,id,b64(challenge),now.toString(),b64(identity.publicKey),
            local.deviceId,local.displayName,local.platform,b64(local.signingPublicKey),b64(local.encryptionPublicKey))
        log(EventType.DEVICE_APPROVED,id)
        return envelope(APPROVAL,approval,IdentityCrypto.sign(rootSeed,approval))
    }
    suspend fun acceptPairingApproval(encoded: String) {
        val identity=checkNotNull(db.identityDao().get())
        val (body,sig)=open(encoded,APPROVAL)
        val values=DataInputStream(ByteArrayInputStream(body)).use { i ->
            require(i.readUTF()==APPROVAL); List(10){i.readUTF()}.also{require(i.available()==0)}
        }
        val (account,id,challenge,approved,signer,approverId,approverName,approverPlatform,approverSign,approverEnc)=values
        require(account==identity.publicId && id==secure.deviceId() && un64(signer).contentEquals(identity.publicKey))
        require(approverId != id && runCatching { java.util.UUID.fromString(approverId) }.isSuccess)
        require(un64(approverSign).size==32 && un64(approverEnc).size==32)
        require(challenge.length in 40..64 && approved.toLong() <= System.currentTimeMillis()+60_000)
        require(secure.loadDevicePairingChallenge(id)?.contentEquals(un64(challenge)) == true) { "Pairing challenge does not match this installation" }
        val approverPublicKey=un64(approverSign)
        require(approverPublicKey.size==32 && IdentityCrypto.verify(identity.publicKey,sig,body)) {
            "Approval signature invalid"
        }
        val row=checkNotNull(db.trustedDeviceDao().get(id))
        check(row.status==TrustedDeviceStatus.PENDING)
        val localSigning = checkNotNull(secure.loadDeviceSigningSeed(id))
        val localEncryption = checkNotNull(secure.loadDeviceEncryptionSecret(id))
        require(row.signingPublicKey.contentEquals(IdentityCrypto.fromSeed(localSigning).publicKey))
        require(row.encryptionPublicKey.contentEquals(PayloadCrypto.publicKey(localEncryption)))
        check(db.trustedDeviceDao().approvePending(id,account,approved.toLong())==1)
        secure.clearDevicePairingChallenge(id)
        val existingApprover=db.trustedDeviceDao().get(approverId)
        if(existingApprover==null) {
            db.trustedDeviceDao().insertPending(TrustedDeviceEntity(approverId,account,approverName,approverPlatform,
                un64(approverSign),un64(approverEnc),TrustedDeviceStatus.APPROVED,approved.toLong(),approved.toLong(),
                approved.toLong(),null,1,0))
        } else {
            require(existingApprover.signingPublicKey.contentEquals(un64(approverSign)) &&
                existingApprover.encryptionPublicKey.contentEquals(un64(approverEnc)) &&
                existingApprover.status==TrustedDeviceStatus.APPROVED)
        }
        log(EventType.DEVICE_APPROVED,id)
    }
    suspend fun revoke(deviceId: String): String {
        val account=checkNotNull(db.identityDao().get()).publicId
        require(deviceId!=secure.deviceId()) { "The active device cannot revoke itself" }
        check(db.trustedDeviceDao().revokeApproved(deviceId,account,System.currentTimeMillis())==1) { "Device is not approved" }
        val local=ensureLocalDevice()
        val signerSeed=checkNotNull(secure.loadDeviceSigningSeed(local.deviceId))
        val body = transcript(REVOCATION, account, deviceId, System.currentTimeMillis().toString(),
            local.deviceId, b64(local.signingPublicKey))
        val signedCode = envelope(REVOCATION, body, IdentityCrypto.sign(signerSeed, body))
        log(EventType.DEVICE_REVOKED,deviceId)
        return signedCode
    }
    suspend fun acceptRevocation(encoded: String) {
        val identity = checkNotNull(db.identityDao().get())
        val (body, signature) = open(encoded, REVOCATION)
        val values = DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readUTF() == REVOCATION)
            buildList { while(input.available()>0) add(input.readUTF()) }
        }
        require(values.size==4 || values.size==5) { "Invalid revocation token" }
        val account=values[0]; val target=values[1]; val timestamp=values[2]
        require(account==identity.publicId && timestamp.toLong()<=System.currentTimeMillis()+60_000)
        if(values.size==4) {
            // Accept previously issued root-signed revocation tokens.
            require(un64(values[3]).contentEquals(identity.publicKey) &&
                IdentityCrypto.verify(identity.publicKey,signature,body)) { "Invalid legacy revocation signature" }
        } else {
            val signer=checkNotNull(db.trustedDeviceDao().get(values[3])) { "Unknown revocation signer" }
            val signerPublic=un64(values[4])
            require(signer.accountId==account && signer.status==TrustedDeviceStatus.APPROVED &&
                signer.signingPublicKey.contentEquals(signerPublic) &&
                IdentityCrypto.verify(signerPublic,signature,body)) { "Invalid revocation signature" }
        }
        val now = System.currentTimeMillis()
        val existing = db.trustedDeviceDao().get(target)
        when (existing?.status) {
            TrustedDeviceStatus.APPROVED -> check(db.trustedDeviceDao().revokeApproved(target, account, now) == 1)
            TrustedDeviceStatus.REVOKED -> return
            TrustedDeviceStatus.PENDING -> error("Pending device cannot be revoked with an approval token")
            null -> db.trustedDeviceDao().insertPending(TrustedDeviceEntity(
                target, account, "Revoked device", "Unknown", ByteArray(0), ByteArray(0),
                TrustedDeviceStatus.REVOKED, now, now, null, now, 1, 0
            ))
            else -> error("Unknown device state")
        }
        log(EventType.DEVICE_REVOKED,target)
    }
    suspend fun rotateLocalDeviceKeys(): String {
        val local=ensureLocalDevice()
        val oldSeed=checkNotNull(secure.loadDeviceSigningSeed(local.deviceId))
        val oldEncryption=checkNotNull(secure.loadDeviceEncryptionSecret(local.deviceId))
        val newSigning=IdentityCrypto.generate()
        // Rotate the device signing identity first. Keep the X25519 transport key stable
        // until peers have received this signed update, otherwise they could not decrypt it.
        val newEncryption=oldEncryption
        val newVersion=local.keyVersion+1
        val body=transcript(ROTATION,local.accountId,local.deviceId,System.currentTimeMillis().toString(),
            local.keyVersion.toString(),newVersion.toString(),b64(local.signingPublicKey),
            b64(newSigning.publicKey),b64(PayloadCrypto.publicKey(oldEncryption)),
            b64(PayloadCrypto.publicKey(newEncryption)))
        val token=envelope(ROTATION,body,IdentityCrypto.sign(oldSeed,body))
        secure.saveDeviceSigningSeed(local.deviceId,newSigning.seed)
        val changed=db.trustedDeviceDao().rotateDeviceKeys(local.deviceId,local.accountId,
            local.keyVersion,newVersion,newSigning.publicKey,PayloadCrypto.publicKey(newEncryption))
        if(changed!=1) {
            secure.saveDeviceSigningSeed(local.deviceId,oldSeed)
            error("Device key rotation lost a concurrent update; previous signing key was restored")
        }
        log(EventType.DEVICE_KEY_ROTATED,local.deviceId)
        return token
    }

    suspend fun acceptKeyRotation(encoded: String) {
        val identity=checkNotNull(db.identityDao().get())
        val (body,signature)=open(encoded,ROTATION)
        val values=DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readUTF()==ROTATION)
            List(9) { input.readUTF() }.also { require(input.available()==0) }
        }
        val account=values[0]; val deviceId=values[1]; val timestamp=values[2]
        val oldVersion=values[3].toLong(); val newVersion=values[4].toLong()
        val oldSigning=un64(values[5]); val newSigning=un64(values[6])
        val oldEncryption=un64(values[7]); val newEncryption=un64(values[8])
        val device=checkNotNull(db.trustedDeviceDao().get(deviceId)) { "Unknown rotating device" }
        require(account==identity.publicId && device.accountId==account &&
            device.status==TrustedDeviceStatus.APPROVED && device.keyVersion==oldVersion &&
            newVersion==oldVersion+1 && timestamp.toLong()<=System.currentTimeMillis()+60_000)
        require(device.signingPublicKey.contentEquals(oldSigning) &&
            device.encryptionPublicKey.contentEquals(oldEncryption) && oldSigning.size==32 &&
            newSigning.size==32 && oldEncryption.size==32 && newEncryption.size==32)
        require(IdentityCrypto.verify(oldSigning,signature,body)) { "Invalid device key rotation signature" }
        check(db.trustedDeviceDao().rotateDeviceKeys(deviceId,account,oldVersion,newVersion,
            newSigning,newEncryption)==1) { "Device key rotation is stale or already applied" }
        log(EventType.DEVICE_KEY_ROTATED,deviceId)
    }
    fun observeTrustedDevices(accountId: String) = db.trustedDeviceDao().observeForAccount(accountId)

    suspend fun encryptForDevice(targetId: String, plain: ByteArray): ByteArray {
        require(plain.size<=8*1024*1024)
        val local=ensureLocalDevice(); val target=checkNotNull(db.trustedDeviceDao().get(targetId))
        require(target.accountId==local.accountId && target.status==TrustedDeviceStatus.APPROVED)
        return PayloadCrypto.seal(local.deviceId,checkNotNull(secure.loadDeviceEncryptionSecret(local.deviceId)),
            target.deviceId,target.encryptionPublicKey,plain)
    }
    suspend fun decryptFromDevice(wire: ByteArray): ByteArray {
        require(wire.size in 64..(8*1024*1024+2048))
        val local=ensureLocalDevice(); val h=checkNotNull(PayloadCrypto.parseHeader(wire))
        val sender=checkNotNull(db.trustedDeviceDao().get(h.senderId))
        require(sender.accountId==local.accountId && sender.status==TrustedDeviceStatus.APPROVED)
        return checkNotNull(PayloadCrypto.open(wire,h,local.deviceId,
            checkNotNull(secure.loadDeviceEncryptionSecret(local.deviceId)),sender.encryptionPublicKey))
    }
}
