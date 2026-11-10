package fr.noe.bda.ui.outils

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.noe.bda.data.backup.Contenu
import fr.noe.bda.data.backup.Sauvegarde
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.work.CoteWorker
import fr.noe.bda.work.Portee
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class OutilsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    dao: BdaDao,
    private val sauvegarde: Sauvegarde,
) : ViewModel() {

    val series = dao.observerSeries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val auteurs = dao.observerAuteurs().map { l -> l.map { it.nom } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The bulk run, if any: the most recent one, running or finished. */
    val travail = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(CoteWorker.NOM_MASSE)
        .map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val message = MutableStateFlow<String?>(null)
    val aRestaurer = MutableStateFlow<Contenu?>(null)

    fun mettreAJour(portee: Portee, valeur: String = "") = CoteWorker.mettreAJour(context, portee, valeur)
    fun annuler() = CoteWorker.annuler(context)

    fun exporter(uri: Uri) = viewModelScope.launch {
        message.value = try {
            val texte = sauvegarde.exporter()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(texte.toByteArray()) } }
            "Sauvegarde enregistrée."
        } catch (e: Exception) {
            "Échec de la sauvegarde : ${e.message}"
        }
    }

    /** Reads and checks the file; nothing is replaced until [restaurer] is confirmed. */
    fun lire(uri: Uri) = viewModelScope.launch {
        try {
            val texte = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            aRestaurer.value = sauvegarde.lire(texte)
        } catch (e: Exception) {
            message.value = "Fichier illisible : ${e.message}"
        }
    }

    /** [remplacer] false adds the file's albums to the collection; true replaces everything with the file. */
    fun restaurer(remplacer: Boolean) = viewModelScope.launch {
        val contenu = aRestaurer.value ?: return@launch
        aRestaurer.value = null
        message.value = try {
            if (remplacer) {
                sauvegarde.restaurer(contenu)
                "${contenu.albums.size} albums restaurés."
            } else {
                "${sauvegarde.ajouter(contenu)} albums ajoutés. Lancez « Toute la collection » pour chercher leurs cotes."
            }
        } catch (e: Exception) {
            "Échec de l'import, la collection n'a pas changé : ${e.message}"
        }
    }
}

@Composable
fun OutilsScreen(onEmplacements: () -> Unit, onFaisabilite: () -> Unit, viewModel: OutilsViewModel = hiltViewModel()) {
    val series by viewModel.series.collectAsStateWithLifecycle()
    val auteurs by viewModel.auteurs.collectAsStateWithLifecycle()
    val travail by viewModel.travail.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val aRestaurer by viewModel.aRestaurer.collectAsStateWithLifecycle()

    // The progress notification needs this permission on Android 13+; the update runs either way
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun lancer(portee: Portee, valeur: String = "") {
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.mettreAJour(portee, valeur)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(viewModel::exporter) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::lire) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mises à jour des cotes", style = MaterialTheme.typography.titleMedium)
        Text(
            "Environ 2,5 s par album (BDthèque est interrogé poliment). Une mise à jour interrompue reprend là où elle s'était arrêtée.",
            style = MaterialTheme.typography.bodySmall,
        )
        Progression(travail, viewModel::annuler)
        val enCours = travail?.state == WorkInfo.State.RUNNING || travail?.state == WorkInfo.State.ENQUEUED
        Button(onClick = { lancer(Portee.TOUT) }, enabled = !enCours) { Text("Toute la collection") }
        ChoixEtAction("Série", series, enabled = !enCours) { lancer(Portee.SERIE, it) }
        ChoixEtAction("Auteur", auteurs, enabled = !enCours) { lancer(Portee.AUTEUR, it) }

        HorizontalDivider()
        Text("Rangement", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onEmplacements) { Text("Meubles et étagères") }

        HorizontalDivider()
        Text("Sauvegarde", style = MaterialTheme.typography.titleMedium)
        Text("Un fichier JSON avec toute la collection, à garder ailleurs ou à importer sur l'autre téléphone.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exporter.launch("bda-${LocalDate.now()}.json") }) { Text("Exporter") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("Importer") }
        }
        message?.let { Text(it) }

        HorizontalDivider()
        OutlinedButton(onClick = onFaisabilite) { Text("Test BDthèque (diagnostic)") }
    }

    aRestaurer?.let { contenu ->
        AlertDialog(
            onDismissRequest = { viewModel.aRestaurer.value = null },
            title = { Text("Importer ${contenu.albums.size} albums") },
            text = {
                Text(
                    "Ajouter : les albums du fichier rejoignent la collection, sans rien effacer (rangement compris).\n\n" +
                        "Remplacer : la collection devient celle du fichier, meubles compris. Exportez-la d'abord si besoin.",
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { viewModel.restaurer(remplacer = true) }) { Text("Remplacer") }
                    TextButton(onClick = { viewModel.restaurer(remplacer = false) }) { Text("Ajouter") }
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.aRestaurer.value = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun Progression(travail: WorkInfo?, onAnnuler: () -> Unit) {
    when (travail?.state) {
        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
            val fait = travail.progress.getInt(CoteWorker.FAIT, 0)
            val total = travail.progress.getInt(CoteWorker.TOTAL, 0)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    if (total > 0) LinearProgressIndicator(progress = { fait / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (total > 0) "$fait / $total albums" else "En attente du réseau…", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onAnnuler) { Text("Annuler") }
            }
        }
        WorkInfo.State.SUCCEEDED -> Text("Dernière mise à jour terminée.", style = MaterialTheme.typography.bodySmall)
        WorkInfo.State.FAILED -> Text(
            "Arrêtée : ${travail.outputData.getString(CoteWorker.ERREUR) ?: "erreur"}. Les cotes connues sont conservées.",
            color = MaterialTheme.colorScheme.error,
        )
        WorkInfo.State.CANCELLED -> Text("Annulée. Relancer reprendra les albums restants.", style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoixEtAction(libelle: String, options: List<String>, enabled: Boolean, onLancer: (String) -> Unit) {
    var choix by remember { mutableStateOf<String?>(null) }
    var ouvert by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = ouvert, onExpandedChange = { ouvert = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = choix ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(libelle) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ouvert) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { choix = o; ouvert = false }) }
            }
        }
        Button(onClick = { choix?.let(onLancer) }, enabled = enabled && choix != null) { Text("Mettre à jour") }
    }
}
