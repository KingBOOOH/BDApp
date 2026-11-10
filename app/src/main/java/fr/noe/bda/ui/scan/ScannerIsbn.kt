package fr.noe.bda.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * Burst scanning: the camera stays on and [onIsbn] gets every ISBN it reads, at most once
 * every [pauseMs] for the same book, so one barcode held in view isn't added in a loop.
 * [message] is shown over the preview (e.g. "ajouté : …").
 */
@Composable
fun ScannerIsbn(onIsbn: (String) -> Unit, message: String?, modifier: Modifier = Modifier, pauseMs: Long = 3_000) {
    val context = LocalContext.current
    var autorise by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { autorise = it }
    LaunchedEffect(Unit) { if (!autorise) demande.launch(Manifest.permission.CAMERA) }

    var indisponible by remember { mutableStateOf(false) }
    var pasUnLivre by remember { mutableStateOf(false) }
    var dernier by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val onIsbnActuel by rememberUpdatedState(onIsbn)
    LaunchedEffect(pasUnLivre) { if (pasUnLivre) { delay(2_000); pasUnLivre = false } }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            indisponible -> Text("Caméra indisponible", color = Color.White)
            !autorise -> Button(onClick = { demande.launch(Manifest.permission.CAMERA) }) { Text("Autoriser la caméra") }
            else -> {
                ApercuCamera(onIndisponible = { indisponible = true }) { resultat ->
                    when (resultat) {
                        is ScanOutcome.Isbn13 -> {
                            val maintenant = System.currentTimeMillis()
                            val (isbn, quand) = dernier ?: ("" to 0L)
                            if (resultat.isbn13 != isbn || maintenant - quand > pauseMs) onIsbnActuel(resultat.isbn13)
                            dernier = resultat.isbn13 to maintenant
                        }
                        ScanOutcome.NotABook -> pasUnLivre = true
                    }
                }
                // An ISBN barcode is about twice as wide as it is tall.
                Box(Modifier.size(width = 240.dp, height = 120.dp).border(2.dp, Color.White, MaterialTheme.shapes.medium))
                Text(
                    text = if (pasUnLivre) "Ce code-barres n'est pas un ISBN" else message ?: "Visez le code-barres au dos de l'album",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
                )
            }
        }
    }
}

/** CameraX preview + ML Kit analysis, bound to the lifecycle and released when it leaves composition. */
@Composable
private fun ApercuCamera(onIndisponible: () -> Unit, onResultat: (ScanOutcome) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val vue = remember { PreviewView(context) }
    val onResultatActuel by rememberUpdatedState(onResultat)
    val onIndisponibleActuel by rememberUpdatedState(onIndisponible)

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val analyseur = IsbnBarcodeAnalyzer { onResultatActuel(it) }
        val apercu = Preview.Builder().build().also { it.setSurfaceProvider(vue.surfaceProvider) }
        val analyse = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, analyseur) }
        var fournisseur: ProcessCameraProvider? = null
        var libere = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (libere) return@addListener
                try {
                    fournisseur = future.get().also {
                        it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, apercu, analyse)
                    }
                } catch (e: Exception) {
                    onIndisponibleActuel()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            libere = true
            fournisseur?.unbind(apercu, analyse)
            analyse.clearAnalyzer()
            analyseur.close()
            executor.shutdown()
        }
    }

    AndroidView(factory = { vue }, modifier = Modifier.fillMaxSize())
}
