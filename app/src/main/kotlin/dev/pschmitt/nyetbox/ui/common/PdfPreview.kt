package dev.pschmitt.nyetbox.ui.common

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.File

/**
 * Renders the first page of a local PDF file as a bitmap scaled to fit within
 * [maxWidth]x[maxHeight], or `null` if [file] isn't a readable PDF. Shared by the small carousel
 * tile preview and the full-resolution page in [ImageViewerDialog].
 */
internal fun renderPdfPage(
    file: File?,
    filename: String,
    url: String?,
    maxWidth: Int,
    maxHeight: Int,
): Bitmap? {
    if (file == null || !file.isFile || !looksLikePdf(file, filename, url)) return null
    return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return@runCatching null
                    val page = renderer.openPage(0)
                    try {
                        val scale =
                            minOf(
                                1f,
                                maxWidth.toFloat() / page.width,
                                maxHeight.toFloat() / page.height,
                            )
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
}

/** How many pages [file] has, or `null` if it isn't a readable PDF. */
internal fun pdfPageCount(file: File?, filename: String, url: String?): Int? {
    if (file == null || !file.isFile || !looksLikePdf(file, filename, url)) return null
    return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            }
        }
        .getOrNull()
}

internal fun looksLikePdf(file: File, filename: String, url: String?): Boolean {
    if (filename.hasPdfExtension() || url.orEmpty().substringBefore('?').hasPdfExtension()) {
        return true
    }
    if (file.extension.equals("pdf", ignoreCase = true)) return true
    return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(5)
                input.read(header) == header.size &&
                    header.contentEquals("%PDF-".encodeToByteArray())
            }
        }
        .getOrDefault(false)
}

private fun String.hasPdfExtension(): Boolean = substringAfterLast('.', "").equals("pdf", true)
