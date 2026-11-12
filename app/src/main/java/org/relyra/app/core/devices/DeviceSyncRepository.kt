package org.relyra.app.core.devices

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.database.*
import org.relyra.app.core.crypto.IdentityCrypto
import java.util.Base64

data class DeviceSyncReport(val contactsAdded:Int,val messagesAdded:Int,val preferencesUpdated:Int,val conflictsKept:Int)

/** User-triggered E2EE sync. Ratchet, MLS, Keystore secrets and transport ciphertext are never copied. */
class DeviceSyncRepository(private val db:AppDatabase,private val devices:DeviceManager,private val settings:org.relyra.app.core.storage.AppSettings) {
    companion object { private const val FORMAT="RELYRA-DEVICE-SYNC"; private const val VERSION=1; private const val MAX=8*1024*1024; private const val LIMIT=100_000 }
    private fun enc(b:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun dec(s:String,max:Int):ByteArray { require(s.length in 1..max*2); return Base64.getUrlDecoder().decode(s).also{require(it.size<=max)} }

    suspend fun exportEncrypted(targetDeviceId:String):ByteArray {
        val identity=checkNotNull(db.identityDao().get())
        val contacts=db.contactDao().allForDeviceSync()
        val messages=db.messageDao().allForDeviceSync()
        val prefs=db.chatPrefDao().allForDeviceSync()
        require(contacts.size+messages.size+prefs.size<=LIMIT)
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
        val global = settings.exportSyncable()
        root.put("globalPreferences", JSONObject().put("updatedAt",global.updatedAt)
            .put("notifications",global.notifications).put("notificationPreview",global.notificationPreview)
            .put("listPreview",global.listPreview))
        val bytes=root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX)
        return devices.encryptForDevice(targetDeviceId,bytes)
    }

    suspend fun importEncrypted(wire:ByteArray):DeviceSyncReport {
        val plain=devices.decryptFromDevice(wire)
        try {
            require(plain.size<=MAX)
            val root=JSONObject(String(plain,Charsets.UTF_8))
            require(root.optString("format")==FORMAT && root.optInt("version")==VERSION)
            val identity=checkNotNull(db.identityDao().get())
            require(root.optString("account")==identity.publicId)
            val ca=root.getJSONArray("contacts"); val ma=root.getJSONArray("messages"); val pa=root.getJSONArray("preferences")
            val global = root.optJSONObject("globalPreferences")
            require(ca.length()+ma.length()+pa.length()<=LIMIT)
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
                    val incoming=MessageEntity(id,j.getString("chat"),j.getBoolean("out"),j.getString("body"),
                        j.getLong("created"),j.getString("state"),j.optInt("attempts"),j.optLong("next"),
                        j.optBoolean("read"),null)
                    require(incoming.body.length<=1_000_000)
                    val current=db.messageDao().get(id)
                    if(current==null) { if(db.messageDao().insertIfAbsent(incoming)!=-1L) mAdded++ }
                    else {
                        require(current.chatId==incoming.chatId&&current.outgoing==incoming.outgoing&&
                            current.createdAt==incoming.createdAt&&current.body==incoming.body) {
                            "Message ID collision; refusing to replace a different message"
                        }
                        val state=mergeState(current.state,incoming.state)
                        val merged=current.copy(state=state,readLocally=current.readLocally||incoming.readLocally,
                            attempts=maxOf(current.attempts,incoming.attempts),
                            nextAttemptAt=minOf(current.nextAttemptAt,incoming.nextAttemptAt))
                        if(merged!=current) db.messageDao().update(merged) else conflicts++
                    }
                }
                for(i in 0 until pa.length()) {
                    val j=pa.getJSONObject(i); val chat=j.getString("chat")
                    val incoming=ChatPrefEntity(chat,j.getBoolean("pinned"),j.getBoolean("archived"),
                        j.getBoolean("muted"),j.optLong("updated"))
                    val current=db.chatPrefDao().get(chat)
                    if(current==null||incoming.updatedAt>current.updatedAt) { db.chatPrefDao().put(incoming); pUpdated++ }
                    else if(current!=incoming) conflicts++
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
