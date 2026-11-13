package earth.ripple.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import earth.ripple.core.Label
import kotlinx.coroutines.tasks.await

/** The on-device models: find the most prominent object and follow it, name it, and read barcodes. */
class Vision {
    val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE) // gives each object a tracking id across frames
            .enableClassification()
            .build()
    )

    val barcodes: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build()
    )

    private val labeler: ImageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.45f).build()
    )

    suspend fun label(bitmap: Bitmap): List<Label> = runCatching {
        labeler.process(InputImage.fromBitmap(bitmap, 0)).await().map { Label(it.text, it.confidence) }
    }.getOrDefault(emptyList())

    fun close() {
        detector.close(); barcodes.close(); labeler.close()
    }
}

/** Crops [source] to [box] grown by a margin, or to the central area when there is no box. */
fun cropTo(source: Bitmap, box: Rect?): Bitmap {
    val r = if (box != null) {
        val mx = (box.width() * 0.08f).toInt(); val my = (box.height() * 0.08f).toInt()
        Rect(box.left - mx, box.top - my, box.right + mx, box.bottom + my)
    } else {
        val w = (source.width * 0.72f).toInt(); val h = (source.height * 0.5f).toInt()
        Rect((source.width - w) / 2, (source.height - h) / 2, (source.width + w) / 2, (source.height + h) / 2)
    }
    val l = r.left.coerceIn(0, source.width - 2); val t = r.top.coerceIn(0, source.height - 2)
    val w = (r.right.coerceIn(l + 1, source.width) - l).coerceAtLeast(1)
    val h = (r.bottom.coerceIn(t + 1, source.height) - t).coerceAtLeast(1)
    return Bitmap.createBitmap(source, l, t, w, h)
}
