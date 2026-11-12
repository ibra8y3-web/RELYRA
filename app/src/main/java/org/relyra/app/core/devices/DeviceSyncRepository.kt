package org.relyra.app.core.devices

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.database.*
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.messaging.MessageState
import java.util.Base64

data class DeviceSyncReport(val contactsAdded:Int,val messagesAdded:Int,val preferencesUpdated:Int,val conflictsKept:Int)

/** User-triggered E2EE sync. Ratchet, MLS, Keystore secrets and transport ciphertext are never copied. */
class DeviceSyncRepository(private val db:AppDatabase,private val devices:DeviceManager,private val settings:org.relyra.app.core.storage.AppSettings) {
    companion object { private const val FORMAT="RELYRA-DEVICE-SYNC"; private const val VERSION=2; private const val MAX=8*1024*1024; private const val LIMIT=100_000 }
    private fun enc(b:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun dec(s:String,max:Int):ByteArray { require(s.length in 1..max*2); return Base64.getUrlDecoder().decode(s).also{require(it.size<=max)} }

    suspend fun exportEncrypted(targetDeviceId:String):ByteArray {
        val identity=checkNotNull(db.identityDao().get())
        val contacts=db.contactDao().allForDeviceSync()
        val attachmentIds=db.attachmentDao().allMessageIdsForDeviceSync().toSet()
        val messages=db.messageDao().allForDeviceSync().filter { it.messageId !in attachmentIds && (!it.outgoing || it.state !in setOf("QUEUED", "SENT", "RELAYED")) }
        val prefs=db.chatPrefDao().allForDeviceSync()
        val trustedDevices=db.trustedDeviceDao().observeForAccount(identity.publicId).first()
        require(contacts.size+messages.size+prefs.size+trustedDevices.size<=LIMIT)
        val root=JSONObject().put("format",FORMAT).put("version",VERSION).put("account",identity.publicId)
            .put("createdAt",System.currentTimeMillis())
        val ca=JSONArray()
        contacts.forEach { c -> ca.put(JSONObject().put("id",c.publicId).put("name",c.displayName)
            .put("pub",enc(c.publicKey)).put("verified",c.verified).put("blocked",c.blocked)
            .put("cardSigned",c.cardSigned).put("addedAt",c.addedAt)
            .put("enc",c.encKey?.let(::enc)).put("nostr",c.nostrKey?.let(::enc))
            .put("spkId",c.signedPreKeyId).put("spk",c.signedPreKey?.let(::enc))
            .put("spkExp",c.signedPreKeyExpiresAt).put("spkSig",c.signedPreKeySignature?.let(::enc))
            .put("opkId",c.oneTimePreKeyId).put("opk",c.oneTimePreKey?.let(::enc))) }
        root.put("contacts",ca)
        val ma=JSONArray()
        messages.forEach { m -> ma.put(JSONObject().put("id",m.messageId).put("chat",m.chatId)
            .put("out",m.outgoing).put("body",m.body).put("created",m.createdAt).put("state",m.state)
            .put("attempts",m.attempts).put("next",m.nextAttemptAt).put("read",m.readLocally)) }
        root.put("messages",ma)
        val pa=JSONArray()
        prefs.forEach { p -> pa.put(JSONObject().put("chat",p.chatId).put("pinned",p.pinned)
            .put("archived",p.archived).put("muted",p.muted).put("updated",p.updatedAt)) }
        root.put("preferences",pa)
        root.put("trustedDevices",JSONArray().also { a -> trustedDevices.forEach { d ->
            a.put(JSONObject().put("id",d.deviceId).put("account",d.accountId)
                .put("name",d.displayName).put("platform",d.platform)
                .put("signing",enc(d.signingPublicKey)).put("encryption",enc(d.encryptionPublicKey))
                .put("status",d.status).put("created",d.createdAt).put("lastSeen",d.lastSeenAt)
                .put("approved",d.approvedAt).put("revoked",d.revokedAt)
                .put("keyVersion",d.keyVersion).put("syncCursor",d.syncCursor))
        } })
        val global = settings.exportSyncable()
        root.put("globalPreferences", JSONObject().put("updatedAt",global.updatedAt)
            .put("notifications",global.notifications).put("notificationPreview",global.notificationPreview)
            .put("listPreview",global.listPreview))

        // Group history and attachment decryption metadata travel only inside the per-device E2EE payload.
        // Absolute local paths are intentionally never exported.
        val groupDao=db.groupDao()
        val groups=groupDao.allForDeviceSync()
        val groupMembers=groupDao.allMembersForDeviceSync()
        val groupMessages=groupDao.allMessagesForDeviceSync()
        val groupAttachments=groupDao.allAttachmentsForDeviceSync()
        require(contacts.size+messages.size+prefs.size+groups.size+groupMembers.size+
            groupMessages.size+groupAttachments.size<=LIMIT)
        root.put("groups",JSONArray().also { a -> groups.forEach { g ->
            a.put(JSONObject().put("id",g.groupId).put("name",g.name).put("kind",g.kind)
                .put("owner",g.ownerId).put("epoch",g.epoch).put("state",g.state)
                .put("created",g.createdAt).put("invitedBy",g.invitedBy)
                .put("mlsEnabled",g.mlsEnabled).put("mlsEpoch",g.mlsEpoch))
        } })
        root.put("groupMembers",JSONArray().also { a -> groupMembers.forEach { m ->
            a.put(JSONObject().put("group",m.groupId).put("member",m.memberId)
                .put("role",m.role).put("mlsJoined",m.mlsJoined))
        } })
        root.put("groupMessages",JSONArray().also { a -> groupMessages.forEach { m ->
            a.put(JSONObject().put("id",m.messageId).put("group",m.groupId).put("sender",m.senderId)
                .put("out",m.outgoing).put("body",m.body).put("created",m.createdAt).put("read",m.readLocally))
        } })
        root.put("groupAttachments",JSONArray().also { a -> groupAttachments.forEach { f ->
            a.put(JSONObject().put("id",f.messageId).put("group",f.groupId).put("sender",f.senderId)
                .put("name",f.name).put("mime",f.mime).put("size",f.size).put("blobSize",f.blobSize)
                .put("plainHash",enc(f.plainSha256)).put("blobHash",enc(f.blobSha256))
                .put("fileKey",enc(f.fileKey)).put("url",f.serverUrl).put("phase",f.phase)
                .put("created",f.createdAt).put("out",f.outgoing))
        } })
        val bytes=root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX)
        return devices.encryptForDevice(targetDeviceId,bytes)
    }

    suspend fun importEncrypted(wire:ByteArray):DeviceSyncReport {
        val plain=devices.decryptFromDevice(wire)
        try {
            require(plain.size<=MAX)
            val root=JSONObject(String(plain,Charsets.UTF_8))
            require(root.optString("format")==FORMAT && root.optInt("version") in 1..VERSION)
            val identity=checkNotNull(db.identityDao().get())
            require(root.optString("account")==identity.publicId)
            val ca=root.getJSONArray("contacts"); val ma=root.getJSONArray("messages"); val pa=root.getJSONArray("preferences")
            val global = root.optJSONObject("globalPreferences")
            val da=root.optJSONArray("trustedDevices") ?: JSONArray()
            val ga=root.optJSONArray("groups") ?: JSONArray()
            val gma=root.optJSONArray("groupMembers") ?: JSONArray()
            val gmessages=root.optJSONArray("groupMessages") ?: JSONArray()
            val gattachments=root.optJSONArray("groupAttachments") ?: JSONArray()
            require(ca.length()+ma.length()+pa.length()+da.length()+ga.length()+gma.length()+
                gmessages.length()+gattachments.length()<=LIMIT)
            var cAdded=0; var mAdded=0; var pUpdated=0; var conflicts=0
            db.withTransaction {
                for(i in 0 until ca.length()) {
                    val j=ca.getJSONObject(i); val id=j.getString("id")
                    require(id.isNotBlank()&&id.length<=128)
                    val publicKey=dec(j.getString("pub"),128)
                    require(IdentityCrypto.publicId(publicKey)==id) { "Contact identity does not match its public key" }
                    val incoming=ContactEntity(id,j.getString("name").take(80),publicKey,
                        j.optBoolean("verified"),j.optBoolean("blocked"),j.optBoolean("cardSigned"),j.getLong("addedAt"),
                        j.optNullableBytes("enc"),j.optNullableBytes("nostr"),j.optNullableLong("spkId"),
                        j.optNullableBytes("spk"),j.optNullableLong("spkExp"),j.optNullableBytes("spkSig"),
                        j.optNullableLong("opkId"),j.optNullableBytes("opk"))
                    val current=db.contactDao().get(id)
                    if(current==null) { db.contactDao().insertIfAbsent(incoming); cAdded++ }
                    else {
                        // Keep local trust/block choices. Only accept a newer profile/key bundle.
                        val newer=if(incoming.addedAt>current.addedAt) incoming.copy(verified=current.verified,blocked=current.blocked) else current
                        if(newer!=current) db.contactDao().upsert(newer) else conflicts++
                    }
                }
                for(i in 0 until ma.length()) {
                    val j=ma.getJSONObject(i); val id=j.getString("id")
                    require(id.isNotBlank()&&id.length<=128)
                    val stateName=j.getString("state")
                    MessageState.valueOf(stateName)
                    val chatId=j.getString("chat")
                    require(chatId.isNotBlank() && chatId.length<=128)
                    val incoming=MessageEntity(id,chatId,j.getBoolean("out"),j.getString("body"),
                        j.getLong("created"),stateName,j.optInt("attempts"),j.optLong("next"),
                        j.optBoolean("read"),null)
                    require(incoming.body.length<=1_000_000)
                    val current=db.messageDao().get(id)
                    if(current==null) {
                        if(db.messageDao().insertIfAbsent(incoming)!=-1L) mAdded++
                    } else {
                        require(current.chatId==incoming.chatId&&current.outgoing==incoming.outgoing&&
                            current.createdAt==incoming.createdAt&&current.body==incoming.body) {
                            "Message ID collision; refusing to replace a different message"
                        }
                        if(incoming.outgoing) {
                            val state=mergeState(current.state,incoming.state)
                            val merged=current.copy(state=state,readLocally=current.readLocally||incoming.readLocally,
                                attempts=maxOf(current.attempts,incoming.attempts),
                                nextAttemptAt=minOf(current.nextAttemptAt,incoming.nextAttemptAt))
                            if(merged!=current) db.messageDao().update(merged) else conflicts++
                        } else {
                            val merged=current.copy(readLocally=current.readLocally||incoming.readLocally)
                            if(merged!=current) db.messageDao().update(merged) else conflicts++
                        }
                    }
                    if(!incoming.outgoing) db.messageDao().insertProcessed(ProcessedIdEntity(id,System.currentTimeMillis()))
                }
                for(i in 0 until pa.length()) {
                    val j=pa.getJSONObject(i); val chat=j.getString("chat")
                    val incoming=ChatPrefEntity(chat,j.getBoolean("pinned"),j.getBoolean("archived"),
                        j.getBoolean("muted"),j.optLong("updated"))
                    val current=db.chatPrefDao().get(chat)
                    if(current==null||incoming.updatedAt>current.updatedAt) { db.chatPrefDao().put(incoming); pUpdated++ }
                    else if(current!=incoming) conflicts++
                }

                for(i in 0 until da.length()) {
                    val j=da.getJSONObject(i)
                    val id=j.getString("id")
                    require(id.length<=64 && runCatching { java.util.UUID.fromString(id) }.isSuccess)
                    require(j.getString("account")==identity.publicId)
                    val signing=dec(j.getString("signing"),32)
                    val encryption=dec(j.getString("encryption"),32)
                    require(signing.size==32 && encryption.size==32)
                    val status=j.getString("status")
                    require(status in setOf(TrustedDeviceStatus.PENDING,TrustedDeviceStatus.APPROVED,TrustedDeviceStatus.REVOKED))
                    val incoming=TrustedDeviceEntity(id,identity.publicId,j.getString("name").take(80),
                        j.getString("platform").take(80),signing,encryption,status,j.getLong("created"),
                        j.getLong("lastSeen"),j.optNullableLong("approved"),j.optNullableLong("revoked"),
                        j.getLong("keyVersion"),j.optLong("syncCursor"))
                    require(incoming.keyVersion>=1 && incoming.syncCursor>=0)
                    val current=db.trustedDeviceDao().get(id)
                    when {
                        current==null -> db.trustedDeviceDao().putSnapshot(incoming)
                        current.status==TrustedDeviceStatus.REVOKED -> Unit
                        status==TrustedDeviceStatus.REVOKED -> {
                            db.trustedDeviceDao().revokeAny(id,identity.publicId,incoming.revokedAt ?: System.currentTimeMillis())
                        }
                        status==TrustedDeviceStatus.APPROVED && current.status==TrustedDeviceStatus.PENDING -> {
                            check(db.trustedDeviceDao().approvePending(id,identity.publicId,
                                incoming.approvedAt ?: System.currentTimeMillis())==1)
                            val approved=db.trustedDeviceDao().get(id) ?: error("Approved device disappeared")
                            if(incoming.keyVersion>approved.keyVersion) {
                                check(db.trustedDeviceDao().rotateDeviceKeys(id,identity.publicId,approved.keyVersion,
                                    incoming.keyVersion,signing,encryption)==1)
                            } else require(incoming.keyVersion==approved.keyVersion &&
                                signing.contentEquals(approved.signingPublicKey) &&
                                encryption.contentEquals(approved.encryptionPublicKey))
                        }
                        status==TrustedDeviceStatus.APPROVED && current.status==TrustedDeviceStatus.APPROVED -> {
                            if(incoming.keyVersion>current.keyVersion) {
                                check(db.trustedDeviceDao().rotateDeviceKeys(id,identity.publicId,current.keyVersion,
                                    incoming.keyVersion,signing,encryption)==1)
                            } else require(incoming.keyVersion==current.keyVersion &&
                                signing.contentEquals(current.signingPublicKey) &&
                                encryption.contentEquals(current.encryptionPublicKey)) {
                                "Trusted-device key rollback or collision detected"
                            }
                        }
                    }
                }

                val groupDao=db.groupDao()
                val replaceMemberGroups=mutableSetOf<String>()
                for(i in 0 until ga.length()) {
                    val j=ga.getJSONObject(i)
                    val id=j.getString("id")
                    require(id.isNotBlank() && id.length<=128)
                    val incoming=GroupEntity(id,j.getString("name").take(120),j.getString("kind"),
                        j.getString("owner"),j.getLong("epoch"),j.getString("state"),
                        j.getLong("created"),j.getString("invitedBy"),j.optBoolean("mlsEnabled"),
                        j.optLong("mlsEpoch"))
                    require(runCatching { incoming.toModel() }.isSuccess) { "Invalid group record" }
                    val current=groupDao.get(id)
                    if(current==null || incoming.epoch>current.epoch ||
                        (incoming.epoch==current.epoch && incoming.mlsEpoch>current.mlsEpoch)) {
                        groupDao.put(incoming)
                        replaceMemberGroups.add(id)
                        pUpdated++
                    } else if(current!=incoming) conflicts++
                }
                for(i in 0 until gma.length()) {
                    val j=gma.getJSONObject(i); val group=j.getString("group")
                    if(groupDao.get(group)==null) { conflicts++; continue }
                    if(replaceMemberGroups.remove(group)) groupDao.clearMembers(group)
                    val member=GroupMemberEntity(group,j.getString("member"),j.getString("role"),j.optBoolean("mlsJoined"))
                    require(member.memberId.isNotBlank() && member.memberId.length<=128)
                    require(runCatching { member.toModel() }.isSuccess)
                    groupDao.putMembers(listOf(member))
                }
                replaceMemberGroups.forEach { groupDao.clearMembers(it) }
                for(i in 0 until gmessages.length()) {
                    val j=gmessages.getJSONObject(i); val group=j.getString("group")
                    if(groupDao.get(group)==null) { conflicts++; continue }
                    val incoming=GroupMessageEntity(j.getString("id"),group,j.getString("sender"),
                        j.getBoolean("out"),j.getString("body"),j.getLong("created"),j.optBoolean("read"))
                    require(incoming.messageId.isNotBlank() && incoming.messageId.length<=128 &&
                        incoming.body.length<=1_000_000)
                    val inserted=groupDao.insertMessageIfAbsent(incoming)
                    if(inserted!=-1L) mAdded++ else {
                        val current=groupDao.groupMessage(incoming.messageId)
                        if(current!=null && (current.groupId!=incoming.groupId ||
                            current.senderId!=incoming.senderId || current.body!=incoming.body ||
                            current.createdAt!=incoming.createdAt)) {
                            error("Group message ID collision; refusing to replace a different message")
                        }
                    }
                }
                for(i in 0 until gattachments.length()) {
                    val j=gattachments.getJSONObject(i); val group=j.getString("group")
                    if(groupDao.get(group)==null) { conflicts++; continue }
                    val key=dec(j.getString("fileKey"),32)
                    val plainHash=dec(j.getString("plainHash"),32)
                    val blobHash=dec(j.getString("blobHash"),32)
                    require(key.size==32 && plainHash.size==32 && blobHash.size==32)
                    val attachment=GroupAttachmentEntity(j.getString("id"),group,j.getString("sender"),
                        j.getString("name").take(255),j.getString("mime").take(255),j.getLong("size"),
                        j.getLong("blobSize"),plainHash,blobHash,
                        key,j.getString("url").take(4096),null,j.getString("phase"),j.getLong("created"),
                        j.getBoolean("out"))
                    require(attachment.messageId.isNotBlank() && attachment.messageId.length<=128 &&
                        attachment.size>=0 && attachment.blobSize>=0 &&
                        runCatching { org.relyra.app.core.messaging.GroupAttachmentPhase.valueOf(attachment.phase) }.isSuccess)
                    val inserted=groupDao.insertGroupAttachmentIfAbsent(attachment)
                    if(inserted==-1L) {
                        val current=groupDao.groupAttachment(attachment.messageId)
                        if(current!=null && (current.groupId!=attachment.groupId ||
                            !current.blobSha256.contentEquals(attachment.blobSha256) ||
                            !current.plainSha256.contentEquals(attachment.plainSha256))) {
                            error("Group attachment ID collision; refusing to replace different file metadata")
                        }
                    }
                }
            }
            if(global != null) {
                val merged = settings.applySyncable(org.relyra.app.core.storage.SyncablePreferences(
                    global.optLong("updatedAt"), global.optBoolean("notifications"),
                    global.optBoolean("notificationPreview"), global.optBoolean("listPreview")
                ))
                if(merged) pUpdated++
            }
            return DeviceSyncReport(cAdded,mAdded,pUpdated,conflicts)
        } finally { plain.fill(0) }
    }

    private fun mergeState(a:String,b:String):String {
        val order=listOf("QUEUED","SENT","RELAYED","DELIVERED","READ")
        val x=order.indexOf(a); val y=order.indexOf(b)
        if(x<0) return if(y>=0)b else a
        if(y<0) return a
        return order[maxOf(x,y)]
    }
    private fun JSONObject.optNullableBytes(k:String):ByteArray? =
        if(!has(k)||isNull(k)) null else dec(getString(k),512)
    private fun JSONObject.optNullableLong(k:String):Long? =
        if(!has(k)||isNull(k)) null else getLong(k)
}
