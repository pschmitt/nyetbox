package dev.pschmitt.nyetbox.scanner

import android.graphics.ImageFormat
import android.graphics.PointF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import timber.log.Timber

/** Decodes QR codes from CameraX frames with ZXing - no Google Play Services dependency. */
class BarcodeAnalyzer(private val onResult: (Detection) -> Unit) : ImageAnalysis.Analyzer {
    data class Detection(
        val text: String,
        /** Finder-pattern points ZXing found, in the analysis image's own pixel coordinates. */
        val points: List<PointF>,
        val imageWidth: Int,
        val imageHeight: Int,
        val rotationDegrees: Int,
    )

    private val reader =
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    // This app only ever scans NetBox device stickers (QR only) - narrowing the
                    // format cuts decode time per frame and lets TRY_HARDER's extra decode passes
                    // (better tolerance of blur, skew, and low contrast) stay affordable.
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) return
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val buffer = plane.buffer
            // The Y plane's row stride can exceed the image width (rows are padded to a hardware
            // alignment boundary). Handing that padded buffer straight to PlanarYUVLuminanceSource
            // as if it were tightly packed skews every row after the first, corrupting the image
            // ZXing sees - copy row by row, dropping the padding, whenever they differ.
            val data =
                if (rowStride == width) {
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } else {
                    ByteArray(width * height).also { packed ->
                        val row = ByteArray(rowStride)
                        for (y in 0 until height) {
                            buffer.position(y * rowStride)
                            buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
                            System.arraycopy(row, 0, packed, y * width, width)
                        }
                    }
                }

            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            val points =
                result.resultPoints?.filterNotNull()?.map { PointF(it.x, it.y) } ?: emptyList()
            onResult(
                Detection(
                    text = result.text,
                    points = points,
                    imageWidth = width,
                    imageHeight = height,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                )
            )
        } catch (_: NotFoundException) {
            // No barcode in this frame - expected on most frames, nothing to log.
        } catch (e: Exception) {
            Timber.w(e, "Barcode decode failed")
        } finally {
            reader.reset()
            image.close()
        }
    }
}
