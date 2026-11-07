package org.relyra.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.relyra.app.MainViewModel
import org.relyra.app.R
import org.relyra.app.UiMessage
import org.relyra.app.core.tools.AndroidTools
import org.relyra.app.core.tools.FileTools
import org.relyra.app.core.tools.PickedFile
import org.relyra.app.ui.components.InfoRow
import org.relyra.app.ui.components.SectionCard
import java.io.File
import java.io.IOException
import android.text.format.Formatter

private enum class Tool(@StringRes val label: Int) {
    HASH(R.string.tool_hash), HEX(R.string.tool_hex), STRINGS(R.string.tool_strings), META(R.string.tool_meta),
    VIEW(R.string.tool_view), ARCHIVE(R.string.tool_archive), APK(R.string.tool_apk),
}

private sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data object Failed : LoadState<Nothing>
    class Done<out T>(val value: T) : LoadState<T>
}

/** Runs [work] off the main thread when [key] changes, and shows a spinner, an error, or [content]. */
@Composable
private fun <T> Loaded(key: Any?, work: suspend () -> T, content: @Composable (T) -> Unit) {
    var state by remember(key) { mutableStateOf<LoadState<T>>(LoadState.Loading) }
    LaunchedEffect(key) {
        state = try {
            LoadState.Done(withContext(Dispatchers.IO) { work() })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            LoadState.Failed
        }
    }
    when (val s = state) {
        LoadState.Loading -> CircularProgressIndicator(Modifier.padding(16.dp))
        LoadState.Failed -> Text(stringResource(R.string.tool_failed), color = MaterialTheme.colorScheme.error)
        is LoadState.Done -> content(s.value)
    }
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
fun ToolsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var tool by rememberSaveable { mutableStateOf(Tool.HASH) }
    var uri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val file: PickedFile? = remember(uri) { uri?.let { AndroidTools.describe(ctx.contentResolver, it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        vm.externalInFlight = false
        if (u != null) uri = u
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tools_intro), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = { vm.externalInFlight = true; picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.tool_pick)) }
        if (file != null) {
            SectionCard(null) {
                InfoRow(stringResource(R.string.tool_name), file.name)
                InfoRow(stringResource(R.string.tool_size), if (file.size >= 0) Formatter.formatFileSize(ctx, file.size) else "?")
                InfoRow(stringResource(R.string.tool_mime), file.mime)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool.entries.forEach { t ->
                FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(stringResource(t.label)) })
            }
        }
        if (file == null) {
            Text(stringResource(R.string.tool_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            when (tool) {
                Tool.HASH -> HashTool(file)
                Tool.HEX -> HexTool(file)
                Tool.STRINGS -> StringsTool(file)
                Tool.META -> MetaTool(vm, file)
                Tool.VIEW -> ViewTool(file)
                Tool.ARCHIVE -> ArchiveTool(vm, file)
                Tool.APK -> ApkTool(file)
            }
        }
    }
}

private fun openIn(ctx: android.content.Context, f: PickedFile) =
    ctx.contentResolver.openInputStream(f.uri) ?: throw IOException("cannot open")

// ---- hashes --------------------------------------------------------------------------------------------------

@Composable
private fun HashTool(f: PickedFile) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expected by remember(f.uri) { mutableStateOf("") }
    Loaded(f.uri, { openIn(ctx, f).use { FileTools.digests(it) } }) { d ->
        SectionCard(stringResource(R.string.tool_hash)) {
            Text("SHA-256", style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(d.sha256, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(d.sha256)) }) { Text(stringResource(R.string.tool_copy)) }
            Text("SHA-1", style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(d.sha1, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            Text("MD5", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            SelectionContainer { Text(d.md5, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            Text(stringResource(R.string.tool_hash_old), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedTextField(
                value = expected, onValueChange = { expected = it.take(200) },
                label = { Text(stringResource(R.string.tool_hash_compare)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            val e = expected.trim().lowercase()
            if (e.isNotEmpty()) {
                val ok = e == d.sha256 || e == d.sha1 || e == d.md5
                Text(
                    stringResource(if (ok) R.string.tool_hash_match else R.string.tool_hash_mismatch),
                    color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---- hex -------------------------------------------------------------------------------------------------------

private const val HEX_PAGE = 2048

@Composable
private fun HexTool(f: PickedFile) {
    val ctx = LocalContext.current
    var offset by remember(f.uri) { mutableStateOf(0L) }
    Loaded(Pair(f.uri, offset), {
        openIn(ctx, f).use {
            FileTools.skipFully(it, offset)
            FileTools.hexDump(FileTools.readUpTo(it, HEX_PAGE), offset)
        }
    }) { dump ->
        SectionCard(stringResource(R.string.tool_hex)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { offset = (offset - HEX_PAGE).coerceAtLeast(0) }, enabled = offset > 0) {
                    Text(stringResource(R.string.tool_prev))
                }
                OutlinedButton(
                    onClick = { offset += HEX_PAGE },
                    enabled = dump.lines().count { it.isNotBlank() } * 16 >= HEX_PAGE && (f.size < 0 || offset + HEX_PAGE < f.size),
                ) { Text(stringResource(R.string.tool_next)) }
            }
            if (dump.isBlank()) Text(stringResource(R.string.tool_empty)) else Mono(dump)
        }
    }
}

// ---- strings ---------------------------------------------------------------------------------------------------

@Composable
private fun StringsTool(f: PickedFile) {
    val ctx = LocalContext.current
    var minLen by rememberSaveable { mutableStateOf(6) }
    Loaded(Pair(f.uri, minLen), {
        openIn(ctx, f).use { FileTools.strings(FileTools.readUpTo(it, 8 * 1024 * 1024), minLen, maxCount = 2000) }
    }) { list ->
        SectionCard(stringResource(R.string.tool_strings)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6, 10).forEach { n ->
                    FilterChip(selected = minLen == n, onClick = { minLen = n }, label = { Text(stringResource(R.string.tool_min_len, n)) })
                }
            }
            Text(stringResource(R.string.tool_strings_count, list.size), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (list.isEmpty()) Text(stringResource(R.string.tool_empty)) else Mono(list.joinToString("\n"))
        }
    }
}

// ---- metadata ----------------------------------------------------------------------------------------------------

private class MetaResult(val type: String?, val exif: AndroidTools.ExifSummary?, val isJpeg: Boolean)

@Composable
private fun MetaTool(vm: MainViewModel, f: PickedFile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleaned by remember(f.uri) { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { out ->
        vm.externalInFlight = false
        val src = cleaned
        cleaned = null
        if (out != null && src != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(out)?.use { o -> src.inputStream().use { it.copyTo(o) } } != null
                    } catch (e: IOException) {
                        false
                    } finally {
                        src.delete()
                    }
                }
                vm.message.value = UiMessage(if (ok) R.string.tool_meta_saved else R.string.tool_failed)
            }
        } else {
            src?.delete()
        }
    }
    Loaded(f.uri, {
        val head = openIn(ctx, f).use { FileTools.readUpTo(it, 600) }
        val type = FileTools.sniffType(head)
        val jpeg = type == "JPEG image"
        var exif: AndroidTools.ExifSummary? = null
        if (jpeg) {
            val tmp = AndroidTools.copyToCache(ctx.contentResolver, f.uri, File(ctx.cacheDir, "tools"), 100L * 1024 * 1024)
            if (tmp != null) {
                try { exif = AndroidTools.exif(tmp.path) } finally { tmp.delete() }
            }
        }
        MetaResult(type, exif, jpeg)
    }) { r ->
        SectionCard(stringResource(R.string.tool_meta)) {
            InfoRow(stringResource(R.string.tool_real_type), r.type ?: stringResource(R.string.tool_unknown))
            InfoRow(stringResource(R.string.tool_declared_type), f.mime)
            val ex = r.exif
            if (ex != null) {
                ex.fields.forEach { (k, v) -> InfoRow(k, v) }
                if (ex.latitude != null && ex.longitude != null) {
                    InfoRow(stringResource(R.string.tool_gps), "${"%.5f".format(ex.latitude)}, ${"%.5f".format(ex.longitude)}")
                    Text(stringResource(R.string.tool_gps_warn), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.tool_no_gps), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (r.isJpeg) {
                Button(
                    onClick = {
                        scope.launch {
                            val copy = AndroidTools.copyToCache(ctx.contentResolver, f.uri, File(ctx.cacheDir, "tools"), 100L * 1024 * 1024)
                            if (copy == null) {
                                vm.message.value = UiMessage(R.string.tool_failed)
                            } else {
                                val ok = withContext(Dispatchers.IO) {
                                    try { AndroidTools.stripJpegMetadata(copy.path); true } catch (e: IOException) { false }
                                }
                                if (ok) {
                                    cleaned = copy
                                    vm.externalInFlight = true
                                    saver.launch(f.name.substringBeforeLast('.') + "-clean.jpg")
                                } else {
                                    copy.delete()
                                    vm.message.value = UiMessage(R.string.tool_failed)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.tool_strip)) }
                Text(stringResource(R.string.tool_strip_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- text viewer -------------------------------------------------------------------------------------------------

private class TextResult(val text: String?, val pretty: String?, val cut: Boolean)

@Composable
private fun ViewTool(f: PickedFile) {
    val ctx = LocalContext.current
    var formatted by remember(f.uri) { mutableStateOf(false) }
    Loaded(f.uri, {
        val bytes = openIn(ctx, f).use { FileTools.readUpTo(it, 1024 * 1024 + 1) }
        val cut = bytes.size > 1024 * 1024
        val body = if (cut) bytes.copyOf(1024 * 1024) else bytes
        if (!FileTools.isProbablyText(body)) TextResult(null, null, cut)
        else {
            val s = String(body, Charsets.UTF_8)
            TextResult(s, if (cut) null else FileTools.prettyJson(s), cut)
        }
    }) { r ->
        SectionCard(stringResource(R.string.tool_view)) {
            val text = r.text
            if (text == null) {
                Text(stringResource(R.string.tool_not_text))
            } else {
                if (r.pretty != null) {
                    FilterChip(selected = formatted, onClick = { formatted = !formatted }, label = { Text(stringResource(R.string.tool_format_json)) })
                }
                if (r.cut) Text(stringResource(R.string.tool_cut), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val shown = if (formatted && r.pretty != null) r.pretty else text
                val lines = shown.lines()
                val capped = lines.take(3000)
                Mono(capped.mapIndexed { i, l -> String.format("%5d  %s", i + 1, l.take(400)) }.joinToString("\n"))
                if (lines.size > capped.size) Text(stringResource(R.string.tool_lines_cut, capped.size, lines.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- archive ---------------------------------------------------------------------------------------------------

private class EntryPreview(val name: String, val hex: Boolean, val body: String)

@Composable
private fun ArchiveTool(vm: MainViewModel, f: PickedFile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var extractName by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<EntryPreview?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { out ->
        vm.externalInFlight = false
        val name = extractName
        extractName = null
        if (out != null && name != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) {
                    try {
                        val o = ctx.contentResolver.openOutputStream(out)
                        if (o == null) -2L else o.use { os -> openIn(ctx, f).use { FileTools.copyZipEntry(it, name, os, 512L * 1024 * 1024) } }
                    } catch (e: IOException) {
                        -2L
                    }
                }
                vm.message.value = UiMessage(if (copied >= 0) R.string.tool_extracted else R.string.tool_failed)
            }
        }
    }
    Loaded(f.uri, { openIn(ctx, f).use { FileTools.listZip(it) } }) { listing ->
        SectionCard(stringResource(R.string.tool_archive)) {
            Text(stringResource(R.string.tool_archive_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.tool_archive_count, listing.items.size) + if (listing.truncated) "+" else "",
                style = MaterialTheme.typography.titleSmall)
            val shown = listing.items.take(300)
            shown.forEach { item ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(item.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (!item.directory) {
                        Row {
                            Text(
                                if (item.size >= 0) Formatter.formatShortFileSize(ctx, item.size) else "?",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    val p = withContext(Dispatchers.IO) {
                                        try {
                                            val bos = java.io.ByteArrayOutputStream()
                                            try { openIn(ctx, f).use { FileTools.copyZipEntry(it, item.name, bos, 64L * 1024) } } catch (e: IOException) { /* only the first 64 KB are wanted */ }
                                            val bytes = bos.toByteArray()
                                            val isText = FileTools.isProbablyText(bytes)
                                            EntryPreview(item.name, !isText, if (isText) String(bytes, Charsets.UTF_8) else FileTools.hexDump(bytes.copyOf(minOf(bytes.size, 1024))))
                                        } catch (e: IOException) {
                                            null
                                        }
                                    }
                                    if (p == null) vm.message.value = UiMessage(R.string.tool_failed) else preview = p
                                }
                            }) { Text(stringResource(R.string.tool_preview)) }
                            TextButton(onClick = {
                                extractName = item.name
                                vm.externalInFlight = true
                                saver.launch(item.name.substringAfterLast('/').ifBlank { "file" })
                            }) { Text(stringResource(R.string.tool_extract)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (listing.items.size > shown.size) {
                Text(stringResource(R.string.tool_archive_more, shown.size, listing.items.size), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(p.name, maxLines = 1) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Mono(p.body) } },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

// ---- APK ---------------------------------------------------------------------------------------------------------

@Composable
private fun ApkTool(f: PickedFile) {
    val ctx = LocalContext.current
    Loaded(f.uri, {
        val tmp = AndroidTools.copyToCache(ctx.contentResolver, f.uri, File(ctx.cacheDir, "tools"))
        if (tmp == null) null else try { AndroidTools.apkInfo(ctx, tmp) } finally { tmp.delete() }
    }) { info ->
        SectionCard(stringResource(R.string.tool_apk)) {
            if (info == null) {
                Text(stringResource(R.string.tool_not_apk))
            } else {
                InfoRow(stringResource(R.string.tool_apk_label), info.label)
                InfoRow(stringResource(R.string.tool_apk_package), info.packageName)
                InfoRow(stringResource(R.string.tool_apk_version), "${info.versionName} (${info.versionCode})")
                InfoRow(stringResource(R.string.tool_apk_sdk), "min ${info.minSdk}, target ${info.targetSdk}")
                if (info.debuggable) Text(stringResource(R.string.tool_apk_debuggable), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.tool_apk_signers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (info.signerSha256.isEmpty()) Text(stringResource(R.string.tool_apk_unsigned), color = MaterialTheme.colorScheme.error)
                info.signerSha256.forEach { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
                Text(stringResource(R.string.tool_apk_perms, info.permissions.size), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp))
                info.permissions.forEach { Text(it.removePrefix("android.permission."), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text(stringResource(R.string.tool_apk_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
