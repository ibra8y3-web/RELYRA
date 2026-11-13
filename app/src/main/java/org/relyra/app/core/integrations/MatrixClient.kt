package org.relyra.app.core.integrations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MatrixSession(val homeserver:String,val userId:String,val accessToken:String,val deviceId:String)
data class MatrixSync(val nextBatch:String,val joinedRoomIds:List<String>,val raw:JSONObject)

/** Matrix Client-Server v3. Encrypted room events are never downgraded to plaintext. */
class MatrixClient(private val http:OkHttpClient=OkHttpClient.Builder().callTimeout(45,TimeUnit.SECONDS).build()) {
 suspend fun login(input:String,user:String,password:String):MatrixSession=withContext(Dispatchers.IO) {
  val base=normalize(input); require(user.isNotBlank() && password.isNotEmpty())
  val body=JSONObject().put("type","m.login.password").put("identifier",JSONObject().put("type","m.id.user").put("user",user))
   .put("password",password).put("initial_device_display_name","RELYRA Android")
  val j=post(base+"/_matrix/client/v3/login",body)
  MatrixSession(base,j.getString("user_id"),j.getString("access_token"),j.optString("device_id"))
 }
 suspend fun sync(s:MatrixSession,since:String?=null,timeoutMs:Long=30000):MatrixSync=withContext(Dispatchers.IO) {
  require(timeoutMs in 0..60000)
  val url=s.homeserver+"/_matrix/client/v3/sync?timeout="+timeoutMs+
   if(since.isNullOrBlank()) "" else "&since="+URLEncoder.encode(since,"UTF-8")
  val j=get(url,s.accessToken); val joined=j.optJSONObject("rooms")?.optJSONObject("join")
  MatrixSync(j.getString("next_batch"),joined?.keys()?.asSequence()?.toList().orEmpty(),j)
 }
 suspend fun join(s:MatrixSession,room:String):String=withContext(Dispatchers.IO) {
  require(room.isNotBlank() && room.length<=512)
  post(s.homeserver+"/_matrix/client/v3/join/"+URLEncoder.encode(room,"UTF-8"),JSONObject(),s.accessToken).getString("room_id")
 }
 suspend fun sendText(s:MatrixSession,room:String,text:String):String=withContext(Dispatchers.IO) {
  require(text.isNotBlank() && text.length<=65536)
  send(s,room,"m.room.message",JSONObject().put("msgtype","m.text").put("body",text))
 }
 /** Event content must be produced by a Matrix E2EE SDK; this method never encrypts locally. */
 suspend fun sendEncryptedEvent(s:MatrixSession,room:String,content:JSONObject):String=withContext(Dispatchers.IO) {
  require(content.optString("algorithm").isNotBlank() && content.has("ciphertext"))
  send(s,room,"m.room.encrypted",content)
 }
 private fun send(s:MatrixSession,room:String,type:String,content:JSONObject):String {
  require(room.startsWith("!") && room.length<=255)
  val url=s.homeserver+"/_matrix/client/v3/rooms/"+URLEncoder.encode(room,"UTF-8")+"/send/"+type+"/"+UUID.randomUUID()
  return post(url,content,s.accessToken).getString("event_id")
 }
 private fun normalize(input:String):String {
  val u=URI(input.trim()); require(u.scheme=="https" && u.host!=null && u.userInfo==null && u.query==null && u.fragment==null)
  return u.toASCIIString().trimEnd('/')
 }
 private fun post(url:String,j:JSONObject,token:String?=null):JSONObject {
  val b=Request.Builder().url(url).post(j.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
  if(token!=null)b.header("Authorization","Bearer "+token)
  return execute(b.build())
 }
 private fun get(url:String,token:String)=execute(Request.Builder().url(url).header("Authorization","Bearer "+token).get().build())
 private fun execute(r:Request):JSONObject=http.newCall(r).execute().use { response ->
  val body=response.body?.string().orEmpty()
  if(!response.isSuccessful)throw IOException("Matrix HTTP "+response.code+": "+body.take(512))
  JSONObject(body)
 }
}