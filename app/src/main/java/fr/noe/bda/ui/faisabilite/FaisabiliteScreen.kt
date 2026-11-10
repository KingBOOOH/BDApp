package fr.noe.bda.ui.faisabilite

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import fr.noe.bda.ui.scan.ScannerIsbn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.noe.bda.data.bedetheque.Correspondance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaisabiliteScreen(viewModel: FaisabiliteViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scanner by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Faisabilité BDthèque") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (scanner) {
                item {
                    ScannerIsbn(
                        onIsbn = viewModel::ajouterScan,
                        message = state.dernierScan,
                        modifier = Modifier.fillMaxWidth().height(280.dp).clip(MaterialTheme.shapes.medium),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = state.saisie,
                    onValueChange = viewModel::saisir,
                    label = { Text("ISBN (un par ligne)") },
                    minLines = 4,
                    enabled = !state.enCours,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scanner = !scanner }, enabled = !state.enCours) {
                        Text(if (scanner) "Fermer" else "Scanner")
                    }
                    if (state.enCours) {
                        OutlinedButton(onClick = viewModel::arreter) { Text("Arrêter") }
                    } else {
                        Button(onClick = viewModel::lancer, enabled = state.saisie.isNotBlank()) { Text("Lancer") }
                    }
                    OutlinedButton(
                        enabled = state.lignes.isNotEmpty(),
                        onClick = {
                            val envoi = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, rapport(state.lignes))
                            context.startActivity(Intent.createChooser(envoi, "Partager le rapport"))
                        },
                    ) { Text("Partager le rapport") }
                }
            }
            items(state.lignes) { LigneCard(it) }
        }
    }
}

@Composable
private fun LigneCard(ligne: Ligne) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ligne.saisie, style = MaterialTheme.typography.titleMedium)
            Text(ligne.lookup, style = MaterialTheme.typography.bodySmall)
            ligne.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (val c = ligne.correspondance) {
                is Correspondance.Trouvee -> {
                    Text("✔ ${c.fiche.serie} — tome ${c.fiche.tome ?: "?"} — ${c.fiche.titre}")
                    Text(c.edition.resume(), style = MaterialTheme.typography.bodySmall)
                    Text(c.raison, style = MaterialTheme.typography.labelSmall)
                }
                is Correspondance.AChoisir -> {
                    Text("? ${c.raison}")
                    c.editions.forEach { Text("• ${it.resume()}", style = MaterialTheme.typography.bodySmall) }
                }
                is Correspondance.Aucune -> Text("✘ ${c.raison}")
                null -> if (ligne.erreur == null && ligne.dureeMs == null && ligne.requetes.isNotEmpty()) Text("en cours…")
            }
            if (ligne.requetes.isNotEmpty()) {
                Text(
                    "${ligne.requetes.size} requêtes : ${ligne.requetes.joinToString { it.code.toString() }}" +
                        (ligne.dureeMs?.let { " · ${it / 1000} s" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
