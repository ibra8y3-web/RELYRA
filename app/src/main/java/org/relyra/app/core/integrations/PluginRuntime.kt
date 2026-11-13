package org.relyra.app.core.integrations

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.tools.BrowserUrlPolicy
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

data class PluginAction(
    val id: String,
    val type: String,
    val title: String,
    val text: String? = null,
    val url: String? = null,
)

data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val requestedPermissions: List<String>,
    val grantedPermissions: List<String>,
    val enabled: Boolean,
    val actions: List<PluginAction>,
)

sealed interface PluginCommand {
    data class Notice(val title: String, val text: String) : PluginCommand
    data class CopyText(val text: String) : PluginCommand
    data class OpenUrl(val url: String) : PluginCommand
}

sealed interface PluginInstallResult {
    data class Installed(val plugin: PluginInfo) : PluginInstallResult
    data class Rejected(val reason: String) : PluginInstallResult
}

/**
 * RELYRA's first-party plugin runtime executes a constrained JSON workflow format, not arbitrary DEX,
 * native code, JavaScript, reflection or shell commands. Each action is validated against a fixed
 * capability allow-list and checked again at execution time.
 */
class PluginRuntime(context: android.content.Context) {
    private val root = File(context.applicationContext.filesDir, "plugins").apply { mkdirs() }
    private val prefs = context.applicationContext.getSharedPreferences("relyra_plugins", android.content.Context.MODE_PRIVATE)
    private val _installed = kotlinx.coroutines.flow.MutableStateFlow(loadInstalled())
    val installed: kotlinx.coroutines.flow.StateFlow<List<PluginInfo>> = _installed

    private val allowedPermissions = setOf("clipboard.write", "external.open")
    private val allowedActionTypes = setOf("notice", "copy_text", "open_url")

    suspend fun inspect(resolver: ContentResolver, uri: Uri): PluginInfo? = withContext(Dispatchers.IO) {
        val raw = readPackage(resolver, uri) ?: return@withContext null
        runCatching { parse(raw, emptySet(), false) }.getOrNull()
    }

    suspend fun install(
        resolver: ContentResolver,
        uri: Uri,
        approvedPermissions: Set<String>,
    ): PluginInstallResult = withContext(Dispatchers.IO) {
        val raw = readPackage(resolver, uri) ?: return@withContext PluginInstallResult.Rejected("Plugin file is unreadable or too large")
        val parsed = try { parse(raw, emptySet(), false) } catch (t: Throwable) {
            return@withContext PluginInstallResult.Rejected(t.message ?: "Invalid plugin package")
        }
        if (!parsed.requestedPermissions.containsAll(approvedPermissions) ||
            approvedPermissions.any { it !in allowedPermissions }
        ) return@withContext PluginInstallResult.Rejected("Permission grant does not match the manifest")
        val target = File(root, parsed.id + ".json")
        if (target.canonicalFile.parentFile != root.canonicalFile) {
            return@withContext PluginInstallResult.Rejected("Invalid plugin path")
        }
        val temporary = File(root, parsed.id + ".tmp")
        try {
            temporary.writeText(raw, StandardCharsets.UTF_8)
            if (target.exists() && !target.delete()) throw IOException("Could not replace the old plugin")
            if (!temporary.renameTo(target)) throw IOException("Could not store plugin package")
            prefs.edit().putString("grants_${parsed.id}", JSONArray(approvedPermissions.sorted()).toString())
                .putBoolean("enabled_${parsed.id}", false).apply()
            val saved = parse(raw, approvedPermissions, false)
            _installed.value = loadInstalled()
            PluginInstallResult.Installed(saved)
        } catch (t: Throwable) {
            temporary.delete()
            PluginInstallResult.Rejected(t.message ?: "Could not install plugin")
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val plugin = _installed.value.firstOrNull { it.id == id } ?: return
        if (enabled && plugin.actions.isEmpty()) return
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
        _installed.value = loadInstalled()
    }

    fun remove(id: String): Boolean {
        if (!id.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}"))) return false
        val target = File(root, "$id.json")
        val deleted = !target.exists() || target.delete()
        if (deleted) {
            prefs.edit().remove("grants_$id").remove("enabled_$id").apply()
            _installed.value = loadInstalled()
        }
        return deleted
    }

    fun runAction(pluginId: String, actionId: String): PluginCommand? {
        val plugin = _installed.value.firstOrNull { it.id == pluginId && it.enabled } ?: return null
        val action = plugin.actions.firstOrNull { it.id == actionId } ?: return null
        return when (action.type) {
            "notice" -> PluginCommand.Notice(action.title, action.text ?: return null)
            "copy_text" -> {
                if ("clipboard.write" !in plugin.grantedPermissions) return null
                PluginCommand.CopyText(action.text ?: return null)
            }
            "open_url" -> {
                if ("external.open" !in plugin.grantedPermissions) return null
                val safe = BrowserUrlPolicy.normalize(action.url ?: return null) ?: return null
                if (!safe.startsWith("https://", true)) return null
                PluginCommand.OpenUrl(safe)
            }
            else -> null
        }
    }

    private fun readPackage(resolver: ContentResolver, uri: Uri): String? {
        return try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use {
                val bytes = it.readNBytes(MAX_PACKAGE_BYTES + 1)
                if (bytes.size > MAX_PACKAGE_BYTES) return null
                String(bytes, StandardCharsets.UTF_8).also { text ->
                    if (text.isBlank()) return null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadInstalled(): List<PluginInfo> {
        if (!root.exists()) return emptyList()
        return root.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val id = file.nameWithoutExtension
                    val grants = readGrants(id)
                    parse(file.readText(StandardCharsets.UTF_8), grants, prefs.getBoolean("enabled_$id", false))
                }.getOrNull()
            }.sortedBy { it.name.lowercase() }
    }

    private fun readGrants(id: String): Set<String> = runCatching {
        val a = JSONArray(prefs.getString("grants_$id", "[]"))
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.toSet()
    }.getOrDefault(emptySet())

    private fun parse(raw: String, grants: Set<String>, enabled: Boolean): PluginInfo {
        require(raw.length <= MAX_PACKAGE_BYTES) { "Plugin package is too large" }
        val json = JSONObject(raw)
        require(json.optInt("schemaVersion", -1) == 1) { "Unsupported plugin schema" }
        val id = json.optString("id")
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}"))) { "Invalid plugin identifier" }
        val name = json.optString("name").trim()
        require(name.isNotBlank() && name.length <= 80) { "Invalid plugin name" }
        val version = json.optString("version", "1.0.0").take(32)
        val description = json.optString("description").take(500)
        val requested = json.optJSONArray("permissions") ?: JSONArray()
        require(requested.length() <= allowedPermissions.size) { "Too many plugin permissions" }
        val permissions = (0 until requested.length()).map { requested.getString(it) }.distinct()
        require(permissions.all { it in allowedPermissions }) { "Plugin requested an unsupported permission" }
        require(grants.all { it in permissions }) { "Stored permission grant exceeds manifest" }
        val actionsArray = json.optJSONArray("actions") ?: JSONArray()
        require(actionsArray.length() in 1..20) { "A plugin must declare between 1 and 20 actions" }
        val actions = (0 until actionsArray.length()).map { index ->
            val item = actionsArray.getJSONObject(index)
            val actionId = item.optString("id")
            require(actionId.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) { "Invalid action identifier" }
            val type = item.optString("type")
            require(type in allowedActionTypes) { "Unsupported plugin action: $type" }
            val title = item.optString("title").trim().take(80)
            require(title.isNotBlank()) { "Action title is required" }
            when (type) {
                "notice" -> {
                    val text = item.optString("text").take(2_000)
                    require(text.isNotBlank()) { "Notice text is required" }
                    PluginAction(actionId, type, title, text = text)
                }
                "copy_text" -> {
                    require("clipboard.write" in permissions) { "copy_text requires clipboard.write" }
                    val text = item.optString("text").take(4_000)
                    require(text.isNotBlank()) { "Copy text is required" }
                    PluginAction(actionId, type, title, text = text)
                }
                "open_url" -> {
                    require("external.open" in permissions) { "open_url requires external.open" }
                    val url = BrowserUrlPolicy.normalize(item.optString("url"))
                    require(url != null && url.startsWith("https://", true)) { "Only HTTPS links are supported" }
                    PluginAction(actionId, type, title, url = url)
                }
                else -> error("Unsupported action")
            }
        }
        require(actions.map { it.id }.distinct().size == actions.size) { "Duplicate plugin action id" }
        return PluginInfo(id, name, version, description, permissions, grants.toList().sorted(), enabled, actions)
    }

    companion object {
        const val MAX_PACKAGE_BYTES = 256 * 1024
    }
}
