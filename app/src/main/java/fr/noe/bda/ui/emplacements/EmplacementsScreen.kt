package fr.noe.bda.ui.emplacements

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.local.Etagere
import fr.noe.bda.data.local.Forme
import fr.noe.bda.data.local.Meuble
import fr.noe.bda.data.local.Mur
import fr.noe.bda.data.local.TypeEtagere
import fr.noe.bda.ui.commun.MenuChip
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EmplacementsViewModel @Inject constructor(private val dao: BdaDao) : ViewModel() {

    val meubles = dao.observerMeubles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun faire(bloc: suspend BdaDao.() -> Unit) = viewModelScope.launch { dao.bloc() }

    /** A new piece of furniture comes with a first shelf, so it can take albums right away. */
    fun ajouterMeuble(nom: String) = faire {
        val id = insererMeuble(Meuble(nom = nom, ordre = ordreMeubleSuivant()))
        insererEtagere(Etagere(meubleId = id, nom = "Étagère 1", ordre = 0))
    }

    fun ajouterEtagere(meuble: Meuble) = faire {
        val ordre = ordreEtagereSuivant(meuble.id)
        insererEtagere(Etagere(meubleId = meuble.id, nom = "Étagère ${ordre + 1}", ordre = ordre))
    }

    fun modifier(meuble: Meuble) = faire { modifierMeubles(meuble) }
    fun modifier(etagere: Etagere) = faire { modifierEtageres(etagere) }

    /** Albums on a deleted shelf are not lost: they go to "Sans emplacement". */
    fun supprimer(meuble: Meuble) = faire { supprimerMeuble(meuble) }
    fun supprimer(etagere: Etagere) = faire { supprimerEtagere(etagere) }

    fun echangerMeubles(a: Meuble, b: Meuble) = faire { modifierMeubles(a.copy(ordre = b.ordre), b.copy(ordre = a.ordre)) }
    fun echangerEtageres(a: Etagere, b: Etagere) = faire { modifierEtageres(a.copy(ordre = b.ordre), b.copy(ordre = a.ordre)) }
}

/** A pending dialog: rename (with the current name) or delete confirmation. */
private sealed interface Dialogue {
    data class Nom(val titre: String, val initial: String, val valider: (String) -> Unit) : Dialogue
    data class Supprimer(val titre: String, val valider: () -> Unit) : Dialogue
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmplacementsScreen(onRetour: () -> Unit, viewModel: EmplacementsViewModel = hiltViewModel()) {
    val meubles by viewModel.meubles.collectAsStateWithLifecycle()
    var dialogue by remember { mutableStateOf<Dialogue?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meubles et étagères") },
                navigationIcon = { IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialogue = Dialogue.Nom("Nouveau meuble", "Meuble ${meubles.size + 1}", viewModel::ajouterMeuble) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Meuble") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (meubles.isEmpty()) item { Text("Ajoutez un premier meuble : la bibliothèque, l'armoire du salon…") }
            itemsIndexed(meubles, key = { _, m -> m.meuble.id }) { i, m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Ligne(
                            m.meuble.nom, MaterialTheme.typography.titleMedium,
                            monter = meubles.getOrNull(i - 1)?.let { p -> { viewModel.echangerMeubles(m.meuble, p.meuble) } },
                            descendre = meubles.getOrNull(i + 1)?.let { s -> { viewModel.echangerMeubles(m.meuble, s.meuble) } },
                            renommer = { dialogue = Dialogue.Nom("Renommer", m.meuble.nom) { viewModel.modifier(m.meuble.copy(nom = it)) } },
                            supprimer = { dialogue = Dialogue.Supprimer("Supprimer « ${m.meuble.nom} » et ses étagères ? Les BD passeront « sans emplacement ».") { viewModel.supprimer(m.meuble) } },
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MenuChip("Mur : ${m.meuble.mur.libelle}", Mur.entries.map { mur -> mur.libelle to { viewModel.modifier(m.meuble.copy(mur = mur)) } })
                            MenuChip(m.meuble.forme.libelle, Forme.entries.map { f -> f.libelle to { viewModel.modifier(m.meuble.copy(forme = f)) } })
                            FilterChip(m.meuble.vitre, { viewModel.modifier(m.meuble.copy(vitre = !m.meuble.vitre)) }, { Text("Portes vitrées") })
                        }
                        val etageres = m.etageres.sortedBy { it.ordre }
                        etageres.forEachIndexed { j, e ->
                            Ligne(
                                "   ${e.nom}", MaterialTheme.typography.bodyLarge,
                                monter = etageres.getOrNull(j - 1)?.let { p -> { viewModel.echangerEtageres(e, p) } },
                                descendre = etageres.getOrNull(j + 1)?.let { s -> { viewModel.echangerEtageres(e, s) } },
                                renommer = { dialogue = Dialogue.Nom("Renommer", e.nom) { viewModel.modifier(e.copy(nom = it)) } },
                                supprimer = { dialogue = Dialogue.Supprimer("Supprimer « ${e.nom} » ? Ses BD passeront « sans emplacement ».") { viewModel.supprimer(e) } },
                                type = {
                                    MenuChip(e.type.libelle, TypeEtagere.entries.map { t -> t.libelle to { viewModel.modifier(e.copy(type = t)) } })
                                },
                            )
                        }
                        TextButton(onClick = { viewModel.ajouterEtagere(m.meuble) }) { Text("+ Étagère") }
                    }
                }
            }
        }
    }

    when (val d = dialogue) {
        is Dialogue.Nom -> {
            var nom by remember(d) { mutableStateOf(d.initial) }
            AlertDialog(
                onDismissRequest = { dialogue = null },
                title = { Text(d.titre) },
                text = { OutlinedTextField(nom, { nom = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { d.valider(nom.trim()); dialogue = null }, enabled = nom.isNotBlank()) { Text("OK") } },
                dismissButton = { TextButton(onClick = { dialogue = null }) { Text("Annuler") } },
            )
        }
        is Dialogue.Supprimer -> AlertDialog(
            onDismissRequest = { dialogue = null },
            text = { Text(d.titre) },
            confirmButton = { TextButton(onClick = { d.valider(); dialogue = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { dialogue = null }) { Text("Annuler") } },
        )
        null -> Unit
    }
}

@Composable
private fun Ligne(
    nom: String,
    style: androidx.compose.ui.text.TextStyle,
    monter: (() -> Unit)?,
    descendre: (() -> Unit)?,
    renommer: () -> Unit,
    supprimer: () -> Unit,
    type: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(nom, style = style, modifier = Modifier.weight(1f))
        type?.invoke()
        IconButton(onClick = { monter?.invoke() }, enabled = monter != null) { Icon(Icons.Default.KeyboardArrowUp, "Monter") }
        IconButton(onClick = { descendre?.invoke() }, enabled = descendre != null) { Icon(Icons.Default.KeyboardArrowDown, "Descendre") }
        IconButton(onClick = renommer) { Icon(Icons.Default.Edit, "Renommer") }
        IconButton(onClick = supprimer) { Icon(Icons.Default.Delete, "Supprimer") }
    }
}
