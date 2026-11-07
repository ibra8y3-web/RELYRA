package org.relyra.app.core.tools

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String)

class ApkInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val permissions: List<String>,
    val signerSha256: List<String>,
)

/** Inspection helpers that need Android. Everything runs on this device; nothing is uploaded or installed. */
object AndroidTools {
    const val MAX_COPY = 300L * 1024 * 1024

    fun describe(resolver: ContentResolver, uri: Uri): PickedFile {
        var name = "file"
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = FileTools.cleanName(it) }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
                Unit
            }
        } catch (e: RuntimeException) {
            // The name and size are cosmetic.
        }
        return PickedFile(uri, name, size, resolver.getType(uri) ?: "application/octet-stream")
    }

    /** Copies the picked file into the app cache (for tools that need a real file path). Null if unreadable or over [maxBytes]. */
    suspend fun copyToCache(resolver: ContentResolver, uri: Uri, dir: File, maxBytes: Long = MAX_COPY): File? =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val f = File(dir, "tool-" + UUID.randomUUID() + ".tmp")
            try {
                val input = resolver.openInputStream(uri) ?: return@withContext null
                input.use { i ->
                    f.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IOException("file is too large")
                            o.write(buf, 0, n)
                        }
                    }
                }
                f
            } catch (e: IOException) {
                f.delete()
                null
            } catch (e: RuntimeException) {
                f.delete()
                null
            }
        }

    fun apkInfo(ctx: Context, file: File): ApkInfo? {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        val info = try { pm.getPackageArchiveInfo(file.path, flags) } catch (e: RuntimeException) { null }
        if (info == null) return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = file.path
        ai.publicSourceDir = file.path
        val label = try { ai.loadLabel(pm).toString() } catch (e: RuntimeException) { info.packageName }
        val signing = info.signingInfo
        val signers = when {
            signing == null -> emptyArray<android.content.pm.Signature>()
            signing.hasMultipleSigners() -> signing.apkContentsSigners
            else -> signing.signingCertificateHistory
        }
        val digests = signers.map { FileTools.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
        return ApkInfo(
            label = label,
            packageName = info.packageName,
            versionName = info.versionName ?: "",
            versionCode = info.longVersionCode,
            minSdk = ai.minSdkVersion,
            targetSdk = ai.targetSdkVersion,
            debuggable = (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            permissions = info.requestedPermissions?.toList() ?: emptyList(),
            signerSha256 = digests,
        )
    }

    private val READABLE_TAGS = listOf(
        "Make", "Model", "Software", "DateTime", "DateTimeOriginal", "ImageWidth", "ImageLength",
        "Orientation", "Artist", "Copyright",
    )

    /** Tags that identify the device, the person or the place. Orientation is kept so the picture still shows upright. */
    private val PRIVATE_TAGS = listOf(
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef",
        "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod", "GPSAreaInformation",
        "Make", "Model", "Software", "DateTime", "DateTimeOriginal", "DateTimeDigitized",
        "Artist", "Copyright", "ImageUniqueID", "UserComment", "MakerNote",
    )

    class ExifSummary(val fields: List<Pair<String, String>>, val latitude: Double?, val longitude: Double?)

    fun exif(path: String): ExifSummary {
        val ex = ExifInterface(path)
        val fields = READABLE_TAGS.mapNotNull { tag -> ex.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { tag to FileTools.cleanName(it) } }
        val out = FloatArray(2)
        val hasGps = ex.getLatLong(out)
        return ExifSummary(fields, if (hasGps) out[0].toDouble() else null, if (hasGps) out[1].toDouble() else null)
    }

    /** Removes location, device, time and author tags from a JPEG in place (no re-encoding, so no quality loss). */
    fun stripJpegMetadata(path: String) {
        val ex = ExifInterface(path)
        for (t in PRIVATE_TAGS) ex.setAttribute(t, null)
        ex.saveAttributes()
    }
}
