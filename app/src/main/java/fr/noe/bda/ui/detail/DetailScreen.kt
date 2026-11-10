package fr.noe.bda.ui.detail

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.noe.bda.data.Cotes
import fr.noe.bda.data.bedetheque.Edition
import fr.noe.bda.data.bedetheque.Fiche
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.local.CoteStatut
import fr.noe.bda.ui.commun.ChoixEtagere
import fr.noe.bda.ui.commun.Couverture
import fr.noe.bda.ui.commun.coteLibelle
import fr.noe.bda.ui.commun.libelle
import fr.noe.bda.work.CoteWorker
import fr.noe.bda.work.Portee
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import fr.noe.bda.ui.commun.ChoixDate
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Editions offered to pick from: `null` when the dialog is closed. */
data class Choix(val chargement: Boolean = true, val editions: List<Pair<Fiche, Edition>> = emptyList(), val erreur: String? = null)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val dao: BdaDao,
    private val cotes: Cotes,
) : ViewModel() {

    val id: Long = checkNotNull(savedState["id"])
    val album = dao.observerAlbum(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val historique = dao.observerHistorique(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val meubles = dao.observerMeubles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val occupe = MutableStateFlow(false)
    val choix = MutableStateFlow<Choix?>(null)

    private fun tache(bloc: suspend () -> Unit) = viewModelScope.launch {
        occupe.value = true
        try { bloc() } catch (e: Exception) { choix.value = choix.value?.copy(chargement = false, erreur = e.message) } finally { occupe.value = false }
    }

    fun rafraichir() = tache { cotes.rafraichir(id) }
    fun deplacer(etagereId: Long?) = tache { dao.deplacer(id, etagereId) }
    fun decaler(sens: Int) = tache { dao.decaler(id, sens) }
    fun saisirCote(min: Int?, max: Int?) = tache { cotes.saisirManuellement(id, min, max) }
    fun reprendreAutomatique() = tache { cotes.reprendreAutomatique(id) }
    fun mettreAJourSerie() = album.value?.album?.serie?.let { CoteWorker.mettreAJour(context, Portee.SERIE, it) }
    fun mettreAJourAuteur(nom: String) = CoteWorker.mettreAJour(context, Portee.AUTEUR, nom)

    fun modifier(titre: String, serie: String, tome: String) = tache {
        dao.album(id)?.let { dao.modifierAlbum(it.copy(titre = titre.trim(), serie = serie.trim().ifEmpty { null }, tome = tome.trim().ifEmpty { null })) }
    }

    fun dater(millis: Long) = tache { dao.album(id)?.let { dao.modifierAlbum(it.copy(ajouteLe = millis)) } }

    fun basculerDedicace() = tache { dao.album(id)?.let { dao.modifierAlbum(it.copy(dedicace = !it.dedicace)) } }

    fun supprimer(apres: () -> Unit) = viewModelScope.launch { dao.album(id)?.let { dao.supprimerAlbum(it) }; apres() }

    /** [url] null: search again from the album's ISBN or title. */
    fun ouvrirChoix(url: String? = null) {
        choix.value = Choix()
        viewModelScope.launch {
            choix.value = try {
                val album = dao.album(id) ?: return@launch
                Choix(false, if (url != null) cotes.editionsDeLaFiche(url) else cotes.editionsPossibles(album))
            } catch (e: Exception) {
                Choix(false, erreur = e.message ?: e.toString())
            }
        }
    }

    fun choisir(fiche: Fiche, edition: Edition) {
        choix.value = null
        tache { cotes.lier(id, fiche, edition) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(onRetour: () -> Unit, viewModel: DetailViewModel = hiltViewModel()) {
    val complet by viewModel.album.collectAsStateWithLifecycle()
    val historique by viewModel.historique.collectAsStateWithLifecycle()
    val meubles by viewModel.meubles.collectAsStateWithLifecycle()
    val occupe by viewModel.occupe.collectAsStateWithLifecycle()
    val choix by viewModel.choix.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialogue by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(complet?.album?.serie ?: complet?.album?.titre ?: "") },
                navigationIcon = { IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        val (album, auteurs) = complet ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Couverture(album, Modifier.size(120.dp, 165.dp).clip(MaterialTheme.shapes.small))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(album.titre, style = MaterialTheme.typography.titleLarge)
                    album.serie?.let { Text("$it${album.tome?.let { t -> " — tome $t" } ?: ""}") }
                    if (auteurs.isNotEmpty()) Text(auteurs.joinToString { it.nom }, style = MaterialTheme.typography.bodyMedium)
                    album.editeur?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    album.isbn?.let { Text("ISBN $it", style = MaterialTheme.typography.bodySmall) }
                    FilterChip(
                        selected = album.dedicace,
                        onClick = { viewModel.basculerDedicace() },
                        label = { Text(if (album.dedicace) "Dédicacée" else "Non dédicacée") },
                        leadingIcon = { if (album.dedicace) Icon(Icons.Default.Check, null) },
                    )
                    ChoixDate(album.ajouteLe, viewModel::dater)
                    TextButton(onClick = { dialogue = "modifier" }) { Text("Modifier") }
                }
            }

            HorizontalDivider()
            Text("Cote : ${album.coteLibelle()}", style = MaterialTheme.typography.titleMedium)
            album.coteMajLe?.let { Text("Mise à jour le ${DateFormat.getDateInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
            if (occupe) Text("…", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (album.coteStatut in setOf(CoteStatut.A_CHOISIR, CoteStatut.NON_TROUVEE, CoteStatut.EXCEL)) {
                    Button(onClick = { viewModel.ouvrirChoix() }) { Text("Choisir l'édition") }
                }
                if (album.coteStatut == CoteStatut.MANUELLE) {
                    OutlinedButton(onClick = viewModel::reprendreAutomatique) { Text("Revenir à BDthèque") }
                } else {
                    OutlinedButton(onClick = viewModel::rafraichir, enabled = !occupe) { Text("Rafraîchir la cote") }
                }
                OutlinedButton(onClick = { dialogue = "cote" }) { Text("Saisir la cote") }
                OutlinedButton(onClick = { dialogue = "lien" }) { Text("Lier une fiche") }
                album.bdthequeUrl?.let { url ->
                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) { Text("Fiche BDthèque") }
                }
                if (album.serie != null) OutlinedButton(onClick = { viewModel.mettreAJourSerie() }) { Text("MàJ la série") }
                auteurs.forEach { a -> OutlinedButton(onClick = { viewModel.mettreAJourAuteur(a.nom) }) { Text("MàJ ${a.nom}") } }
            }
            if (historique.isNotEmpty()) {
                Text("Historique", style = MaterialTheme.typography.titleSmall)
                historique.forEach { h ->
                    val valeur = when {
                        h.min == null && h.max == null -> "non coté"
                        h.min == h.max || h.min == null || h.max == null -> "${h.min ?: h.max} €"
                        else -> "${h.min}–${h.max} €"
                    }
                    Text("${DateFormat.getDateInstance().format(Date(h.date))} : $valeur", style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChoixEtagere(meubles, album.etagereId, viewModel::deplacer, Modifier.weight(1f))
                IconButton(onClick = { viewModel.decaler(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Décaler à gauche") }
                IconButton(onClick = { viewModel.decaler(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Décaler à droite") }
            }
            TextButton(onClick = { dialogue = "supprimer" }) { Text("Supprimer l'album", color = MaterialTheme.colorScheme.error) }
        }

        when (dialogue) {
            "modifier" -> DialogueChamps(
                "Modifier", listOf("Titre" to album.titre, "Série" to album.serie.orEmpty(), "Tome" to album.tome.orEmpty()),
                onFermer = { dialogue = null },
            ) { (t, s, n) -> viewModel.modifier(t, s, n) }
            "cote" -> DialogueChamps(
                "Cote (en euros)", listOf("Minimum" to album.coteMin?.toString().orEmpty(), "Maximum" to album.coteMax?.toString().orEmpty()),
                onFermer = { dialogue = null }, nombres = true,
            ) { (min, max) -> viewModel.saisirCote(min.toIntOrNull(), max.toIntOrNull()) }
            "lien" -> DialogueChamps("Adresse de la fiche BDthèque", listOf("URL" to ""), onFermer = { dialogue = null }) { (url) ->
                viewModel.ouvrirChoix(url.trim())
            }
            "supprimer" -> AlertDialog(
                onDismissRequest = { dialogue = null },
                title = { Text("Supprimer « ${album.titre} » ?") },
                confirmButton = { TextButton(onClick = { dialogue = null; viewModel.supprimer(onRetour) }) { Text("Supprimer") } },
                dismissButton = { TextButton(onClick = { dialogue = null }) { Text("Annuler") } },
            )
        }
        choix?.let { c -> DialogueEditions(c, onChoisir = viewModel::choisir, onFermer = { viewModel.choix.value = null }) }
    }
}

/** A dialog with one text field per (label, initial value); [onValider] gets the values in order. */
@Composable
private fun DialogueChamps(
    titre: String,
    champs: List<Pair<String, String>>,
    onFermer: () -> Unit,
    nombres: Boolean = false,
    onValider: (List<String>) -> Unit,
) {
    var valeurs by remember { mutableStateOf(champs.map { it.second }) }
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(titre) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                champs.forEachIndexed { i, (label, _) ->
                    OutlinedTextField(
                        value = valeurs[i],
                        onValueChange = { v -> valeurs = valeurs.toMutableList().also { it[i] = v } },
                        label = { Text(label) },
                        singleLine = true,
                        keyboardOptions = if (nombres) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(valeurs); onFermer() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onFermer) { Text("Annuler") } },
    )
}

/** The editions sharing the album's ISBN (or found by title), with what tells an EO from a reprint. */
@Composable
private fun DialogueEditions(choix: Choix, onChoisir: (Fiche, Edition) -> Unit, onFermer: () -> Unit) {
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text("Quelle édition avez-vous ?") },
        text = {
            when {
                choix.chargement -> Text("Recherche sur BDthèque…")
                choix.erreur != null -> Text("Erreur : ${choix.erreur}")
                choix.editions.isEmpty() -> Text("Aucune édition trouvée. Collez l'adresse de la fiche avec « Lier une fiche ».")
                else -> LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(choix.editions) { (fiche, edition) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onChoisir(fiche, edition) },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AsyncImage(edition.couvertureUrl, null, Modifier.size(48.dp, 66.dp))
                            Column(Modifier.weight(1f)) {
                                Text(listOfNotNull(fiche.serie, edition.titre).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(edition.libelle, edition.depotLegal?.let { "DL $it" }, edition.cote.libelle()).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                edition.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onFermer) { Text("Fermer") } },
    )
}
