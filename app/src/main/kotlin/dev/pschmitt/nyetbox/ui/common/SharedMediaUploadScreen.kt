package dev.pschmitt.nyetbox.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMediaUploadScreen(
    endpointPath: String,
    objectId: Int,
    targetLabel: String,
    uri: String,
    mimeType: String?,
    filename: String?,
    onBack: () -> Unit,
    onUploaded: () -> Unit,
) {
    val context = LocalContext.current
    val sharedUri = remember(uri) { uri.toUri() }
    val isImage = isSharedImage(mimeType, filename, sharedUri)
    val initialKind = if (isImage) MediaUploadKind.ImageAttachment else MediaUploadKind.Document

    NetBoxResponsiveScaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Share to NetBox") },
            )
        }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetBoxSectionHeader(Icons.Default.AttachFile, "Upload attachment")
            Text(
                "${filename?.takeIf(String::isNotBlank) ?: "Shared file"} → $targetLabel",
                style = MaterialTheme.typography.titleMedium,
            )
            SharedMediaPreview(
                uri = sharedUri,
                mimeType = mimeType ?: context.contentResolver.getType(sharedUri),
                filename = filename,
            )
            Text(
                if (isImage) {
                    "This image will be uploaded as an image attachment. Device types also offer front/rear photo replacement."
                } else {
                    "This file will be uploaded as a NetBox document. Choose its document type before uploading."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    MediaUploadDialog(
        endpointPath = endpointPath,
        objectId = objectId,
        onDismiss = onBack,
        onUploaded = onUploaded,
        initialKind = initialKind,
        initialUri = sharedUri,
        initialFilename = filename,
        initialMimeType = mimeType,
    )
}

@Composable
internal fun SharedMediaPreview(uri: Uri, mimeType: String?, filename: String?) {
    val context = LocalContext.current
    val isImage = isSharedImage(mimeType, filename, uri)
    val isPdf =
        mimeType.equals("application/pdf", ignoreCase = true) ||
            filename?.substringAfterLast('.', "")?.equals("pdf", ignoreCase = true) == true ||
            uri.lastPathSegment?.substringAfterLast('.', "")?.equals("pdf", ignoreCase = true) ==
                true
    val pdfPreview by
        produceState<Bitmap?>(initialValue = null, uri, isPdf) {
            value =
                if (isPdf) {
                    withContext(Dispatchers.IO) { renderSharedPdfPreview(context, uri) }
                } else {
                    null
                }
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(width = 112.dp, height = 112.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isImage ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Preview of shared image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                pdfPreview != null ->
                    Image(
                        bitmap = pdfPreview!!.asImageBitmap(),
                        contentDescription = "Preview of shared document",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                else ->
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (isImage) "Image preview" else "Document preview",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                filename?.takeIf(String::isNotBlank) ?: "Shared file",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            Text(
                mimeType?.takeIf(String::isNotBlank) ?: "Preview unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun isSharedImage(mimeType: String?, filename: String?, uri: Uri): Boolean {
    return isSharedImage(mimeType, filename, uri.lastPathSegment)
}

internal fun isSharedImage(
    mimeType: String?,
    filename: String?,
    uriLastPathSegment: String?,
): Boolean {
    if (mimeType?.startsWith("image/", ignoreCase = true) == true) return true
    val extension =
        (filename?.substringAfterLast('.', "") ?: uriLastPathSegment?.substringAfterLast('.', ""))
            ?.lowercase()
    return extension in setOf("avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "webp")
}

private fun renderSharedPdfPreview(context: Context, uri: Uri): Bitmap? =
    runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return@runCatching null
                    val page = renderer.openPage(0)
                    try {
                        val scale = minOf(1f, 240f / page.width, 320f / page.height)
                        val bitmap =
                            createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    } finally {
                        page.close()
                    }
                }
            }
        }
        .getOrNull()
