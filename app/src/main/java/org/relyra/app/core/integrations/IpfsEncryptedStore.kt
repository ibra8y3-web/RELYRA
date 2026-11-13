package org.relyra.app.core.integrations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.core.content.edit
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Optional Kubo RPC storage. Only RELYRA encrypted .enc blobs are accepted. */
class IpfsEncryptedStore(
 private val context: Context,
 private val http:OkHttpClient=OkHttpClient.Builder().callTimeout(90,TimeUnit.SECONDS).build()
) {
 private val prefs = context.applicationContext.getSharedPreferences("relyra_ipfs", Context.MODE_PRIVATE)
 private val _endpoint = MutableStateFlow(prefs.getString("kubo_https_endpoint", "").orEmpty())
 val endpoint: StateFlow<String> = _endpoint
 fun configureEndpoint(input: String) {
  val normalized = if (input.isBlank()) "" else normalize(input)
  prefs.edit { putString("kubo_https_endpoint", normalized) }
  _endpoint.value = normalized
 }
 suspend fun addCiphertext(api:String = endpoint.value,file:File):String=withContext(Dispatchers.IO) {
  val base=normalize(api); require(file.isFile && file.extension=="enc" && file.length() in 1..MAX_BYTES)
  val body=MultipartBody.Builder().setType(MultipartBody.FORM)
   .addFormDataPart("file",file.name,file.asRequestBody("application/octet-stream".toMediaType())).build()
  val req=Request.Builder().url(base+"/api/v0/add?pin=true&cid-version=1&raw-leaves=true").post(body).build()
  http.newCall(req).execute().use { res ->
   val raw=res.body?.string().orEmpty(); if(!res.isSuccessful)throw IOException("IPFS add HTTP "+res.code)
   val last=raw.lineSequence().filter{it.isNotBlank()}.lastOrNull()?:throw IOException("Empty IPFS response")
   val cid=JSONObject(last).optString("Hash"); require(CID.matches(cid)){"Invalid CIDv1 from Kubo"}; cid
  }
 }
 suspend fun fetchCiphertext(api:String = endpoint.value,cid:String,target:File,maxBytes:Long=MAX_BYTES):File=withContext(Dispatchers.IO) {
  require(CID.matches(cid) && maxBytes in 1..MAX_BYTES)
  val req=Request.Builder().url(normalize(api)+"/api/v0/cat?arg="+cid).post(ByteArray(0).toRequestBody()).build()
  http.newCall(req).execute().use { res ->
   if(!res.isSuccessful)throw IOException("IPFS cat HTTP "+res.code)
   val body=res.body?:throw IOException("Empty IPFS object")
   if(body.contentLength()>maxBytes)throw IOException("IPFS object exceeds size limit")
   target.parentFile?.mkdirs(); val temp=File(target.parentFile,target.name+".part")
   try {
    body.byteStream().use { input -> temp.outputStream().use { output ->
     val buffer=ByteArray(32768); var total=0L
     while(true){val n=input.read(buffer);if(n<0)break;total+=n;if(total>maxBytes)throw IOException("IPFS object exceeds size limit");output.write(buffer,0,n)}
    }}
    if(!temp.renameTo(target))throw IOException("Could not finalize ciphertext file"); target
   } catch(t:Throwable){temp.delete();throw t}
  }
 }
 suspend fun pin(api:String = endpoint.value,cid:String)=withContext(Dispatchers.IO) {
  require(CID.matches(cid))
  val req=Request.Builder().url(normalize(api)+"/api/v0/pin/add?arg="+cid).post(ByteArray(0).toRequestBody()).build()
  http.newCall(req).execute().use{if(!it.isSuccessful)throw IOException("IPFS pin HTTP "+it.code)}
 }
 private fun normalize(input:String):String {
  val u=URI(input.trim());require(u.scheme=="https"&&u.host!=null&&u.userInfo==null&&u.query==null&&u.fragment==null){"Kubo API must use HTTPS"}
  return u.toASCIIString().trimEnd('/')
 }
 companion object { const val MAX_BYTES=100L*1024*1024; private val CID=Regex("b[a-z2-7]{20,120}") }
}