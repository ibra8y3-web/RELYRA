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
    private fun log(type: String, detail: String) {
        kotlinx.coroutines.runBlocking { db.eventDao().insert(SecurityEventEntity(timestamp=System.currentTimeMillis(), type=type, detail=detail)) }
    }
    suspend fun ensureLocalDevice(): TrustedDeviceEntity {
        val identity = checkNotNull(db.identityDao().get()) { "Create or restore your RELYRA identity first" }
        val id = secure.deviceId()
        db.trustedDeviceDao().get(id)?.let { row ->
            check(row.accountId == identity.publicId && row.status == TrustedDeviceStatus.APPROVED) { "This device is not trusted" }
            return row
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
        ensureLocalDevice()
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
        require(db.trustedDeviceDao().get(id)==null) { "Device already exists" }
        val now=System.currentTimeMillis()
        db.trustedDeviceDao().insertPending(TrustedDeviceEntity(id,account,name,platform,signPub,encPub,
            TrustedDeviceStatus.PENDING,created,now,null,null,1,0))
        check(db.trustedDeviceDao().approvePending(id,account,now)==1)
        val approval=transcript(APPROVAL,account,id,b64(challenge),now.toString(),b64(identity.publicKey))
        log(EventType.DEVICE_APPROVED,id)
        return envelope(APPROVAL,approval,IdentityCrypto.sign(rootSeed,approval))
    }
    suspend fun acceptPairingApproval(encoded: String) {
        val identity=checkNotNull(db.identityDao().get())
        val (body,sig)=open(encoded,APPROVAL)
        val values=DataInputStream(ByteArrayInputStream(body)).use { i ->
            require(i.readUTF()==APPROVAL); List(5){i.readUTF()}.also{require(i.available()==0)}
        }
        val (account,id,challenge,approved,signer)=values
        require(account==identity.publicId && id==secure.deviceId() && un64(signer).contentEquals(identity.publicKey))
        require(challenge.length in 40..64 && approved.toLong() <= System.currentTimeMillis()+60_000)
        require(IdentityCrypto.verify(identity.publicKey,sig,body)) { "Approval signature invalid" }
        val row=checkNotNull(db.trustedDeviceDao().get(id))
        check(row.status==TrustedDeviceStatus.PENDING)
        check(db.trustedDeviceDao().approvePending(id,account,approved.toLong())==1)
        log(EventType.DEVICE_APPROVED,id)
    }
    suspend fun revoke(deviceId: String) {
        val account=checkNotNull(db.identityDao().get()).publicId
        require(deviceId!=secure.deviceId()) { "The active device cannot revoke itself" }
        check(db.trustedDeviceDao().revokeApproved(deviceId,account,System.currentTimeMillis())==1) { "Device is not approved" }
        log(EventType.DEVICE_REVOKED,deviceId)
    }
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
