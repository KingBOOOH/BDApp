package fr.noe.bda.ui.scan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import fr.noe.bda.data.book.Isbn

/** What one camera frame showed. Frames with no barcode at all report nothing. */
sealed interface ScanOutcome {
    data class Isbn13(val isbn13: String) : ScanOutcome

    /** A barcode was read, but it isn't a book's: a price label, a grocery EAN. */
    data object NotABook : ScanOutcome
}

/**
 * The ISBN among the barcodes read in one frame, if any. An ISBN barcode is an EAN-13
 * starting 978 or 979, so [Isbn.toIsbn13] tells it apart from any other EAN-13.
 */
internal fun scanOutcome(rawValues: List<String>): ScanOutcome? {
    if (rawValues.isEmpty()) return null
    return rawValues.firstNotNullOfOrNull(Isbn::toIsbn13)?.let(ScanOutcome::Isbn13) ?: ScanOutcome.NotABook
}

/**
 * EAN-13 only: the format every ISBN barcode uses, and narrowing it keeps detection fast
 * on older phones.
 */
internal val isbnScannerOptions: BarcodeScannerOptions =
    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13).build()

/** Runs ML Kit on each frame. [onOutcome] is called on the main thread. */
class IsbnBarcodeAnalyzer(private val onOutcome: (ScanOutcome) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(isbnScannerOptions)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes -> scanOutcome(barcodes.mapNotNull { it.rawValue })?.let(onOutcome) }
            // The next frame is only delivered once this one is closed.
            .addOnCompleteListener { imageProxy.close() }
    }

    fun close() = scanner.close()
}
