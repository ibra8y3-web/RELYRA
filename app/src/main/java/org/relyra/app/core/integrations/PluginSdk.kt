package org.relyra.app.core.integrations
import org.json.JSONArray
import org.json.JSONObject

data class PluginManifestV1(val id:String,val name:String,val version:String,val description:String,val permissions:Set<String>,val actions:List<PluginSdkAction>)
data class PluginSdkAction(val id:String,val type:String,val title:String,val value:String?)
sealed interface PluginValidation { data class Accepted(val manifest:PluginManifestV1):PluginValidation; data class Rejected(val reason:String):PluginValidation }

/** Versioned data-only plugin SDK. No DEX, native code, scripts, reflection, shell or arbitrary network access. */
object PluginSdk {
 const val SCHEMA=1
 const val MIME_TYPE="application/vnd.relyra.plugin+json"
 private val permissions=setOf("clipboard.write","external.open")
 private val actionTypes=setOf("notice","copy_text","open_url")
 fun parse(source:String):PluginValidation=try {
  require(source.toByteArray(Charsets.UTF_8).size<=262144){"Plugin package too large"}
  val root=JSONObject(source);require(root.optInt("schemaVersion")==SCHEMA){"Unsupported plugin schema"}
  val id=root.getString("id");require(id.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}"))){"Invalid plugin id"}
  val name=root.getString("name").trim();val version=root.getString("version").trim()
  require(name.isNotEmpty()&&name.length<=80&&version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))){"Invalid plugin metadata"}
  val p=root.optJSONArray("permissions")?:JSONArray();require(p.length()<=permissions.size)
  val grants=(0 until p.length()).map{p.getString(it)}.toSet()
  require(grants.size==p.length()&&grants.all{it in permissions}){"Unknown or duplicate capability"}
  val a=root.getJSONArray("actions");require(a.length() in 1..32)
  val parsed=(0 until a.length()).map{i->
   val item=a.getJSONObject(i);val aid=item.getString("id");val type=item.getString("type");val title=item.getString("title").trim()
   require(aid.matches(Regex("[A-Za-z0-9_-]{1,48}"))&&type in actionTypes&&title.isNotEmpty()&&title.length<=80){"Invalid action"}
   val value=when(type){"notice","copy_text"->item.optString("text").takeIf{it.isNotEmpty()};"open_url"->item.optString("url").takeIf{it.isNotEmpty()};else->null}
   when(type){"copy_text"->require("clipboard.write" in grants&&value!=null&&value.length<=8192)
    "open_url"->require("external.open" in grants&&value?.startsWith("https://")==true)
    "notice"->require(value!=null&&value.length<=2048)}
   PluginSdkAction(aid,type,title,value)
  }
  require(parsed.map{it.id}.toSet().size==parsed.size){"Duplicate action id"}
  PluginValidation.Accepted(PluginManifestV1(id,name,version,root.optString("description").take(512),grants,parsed))
 } catch(t:Throwable){PluginValidation.Rejected(t.message?:"Invalid plugin manifest")}
}
