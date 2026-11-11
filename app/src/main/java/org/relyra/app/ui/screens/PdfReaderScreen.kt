package org.relyra.app.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.relyra.app.R

private data class RenderedPdfPage(val pageCount: Int, val bitmap: Bitmap)
private sealed interface PdfRenderState {
    data object Loading : PdfRenderState
    data class Ready(val value: RenderedPdfPage) : PdfRenderState
    data class Failed(val reason: String) : PdfRenderState
}

/** Local, read-only PDF page renderer. The document is opened through Android's Storage Access Framework. */
@Composable
fun PdfReaderScreen() {
    val context = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<PdfRenderState?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            uri = selected
            pageIndex = 0
            state = PdfRenderState.Loading
        }
    }

    LaunchedEffect(uri, pageIndex) {
        val selected = uri ?: run {
            state = null
            return@LaunchedEffect
        }
        state = PdfRenderState.Loading
        val result = withContext(Dispatchers.IO) {
            try {
                val descriptor = context.contentResolver.openFileDescriptor(selected, "r")
                    ?: throw IllegalStateException("The selected document cannot be opened")
                descriptor.use { pfd: ParcelFileDescriptor ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount <= 0) throw IllegalStateException("The PDF has no pages")
                        val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                        renderer.openPage(safeIndex).use { page ->
                            val scale = minOf(1.5f, 1800f / page.width, 2400f / page.height).coerceAtLeast(0.25f)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            RenderedPdfPage(renderer.pageCount, bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                PdfRenderState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        state = when (result) {
            is RenderedPdfPage -> PdfRenderState.Ready(result)
            is PdfRenderState.Failed -> result
            else -> PdfRenderState.Failed("Unable to render this PDF")
        }
        val ready = state as? PdfRenderState.Ready
        if (ready != null) pageIndex = pageIndex.coerceIn(0, ready.value.pageCount - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pdf_open))
        }
        when (val current = state) {
            null -> Text(stringResource(R.string.pdf_pick_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            PdfRenderState.Loading -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            is PdfRenderState.Failed -> Text(
                stringResource(R.string.pdf_error, current.reason),
                color = MaterialTheme.colorScheme.error,
            )
            is PdfRenderState.Ready -> {
                Image(
                    bitmap = current.value.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.pdf_page_accessibility, pageIndex + 1, current.value.pageCount),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) {
                        Text(stringResource(R.string.pdf_previous))
                    }
                    Text(stringResource(R.string.pdf_page, pageIndex + 1, current.value.pageCount))
                    TextButton(onClick = { pageIndex++ }, enabled = pageIndex + 1 < current.value.pageCount) {
                        Text(stringResource(R.string.pdf_next))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.pdf_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
