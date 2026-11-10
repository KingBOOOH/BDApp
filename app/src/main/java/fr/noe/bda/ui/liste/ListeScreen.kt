package fr.noe.bda.ui.liste

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.noe.bda.data.bedetheque.normaliser
import fr.noe.bda.data.local.AlbumComplet
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.local.CoteStatut
import fr.noe.bda.data.local.MeubleComplet
import fr.noe.bda.ui.commun.Couverture
import fr.noe.bda.ui.commun.MenuChip
import fr.noe.bda.ui.commun.coteLibelle
import fr.noe.bda.ui.commun.libelleEtagere
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class Tri(val libelle: String) { SERIE("Série"), TITRE("Titre"), COTE("Cote"), AJOUT("Ajout") }

enum class Filtre(val libelle: String) {
    COTE_MANQUANTE("Cote manquante"),
    A_CHOISIR("Édition à choisir"),
    COTEES("Cotées"),
    DEDICACEES("Dédicacées"),
}

data class Criteres(
    val recherche: String = "",
    val tri: Tri = Tri.SERIE,
    val filtre: Filtre? = null,
    val meubleId: Long? = null,
    val parSerie: Boolean = true,
)

data class ListeState(
    val albums: List<AlbumComplet> = emptyList(),
    val meubles: List<MeubleComplet> = emptyList(),
    val nbTotal: Int = 0,
    /** Estimated value of the whole collection, not just the filtered albums. */
    val valeurTotale: Int = 0,
    val valeurAffichee: Int = 0,
)

/** True when [q] (already normalised) is in the title, the series or an author's name. */
fun AlbumComplet.correspond(q: String): Boolean =
    q.isEmpty() || normaliser(album.titre).contains(q) || album.serieNorm?.contains(q) == true ||
        auteurs.any { it.nomNorm.contains(q) }

@HiltViewModel
class ListeViewModel @Inject constructor(dao: BdaDao) : ViewModel() {

    val criteres = MutableStateFlow(Criteres())

    val state = combine(dao.observerAlbums(), dao.observerMeubles(), criteres) { tous, meubles, c ->
        val q = normaliser(c.recherche)
        val etageresDuMeuble = meubles.firstOrNull { it.meuble.id == c.meubleId }?.etageres?.map { it.id }?.toSet()
        val albums = tous.filter { a ->
            a.correspond(q) &&
                (etageresDuMeuble == null || a.album.etagereId in etageresDuMeuble) &&
                when (c.filtre) {
                    null -> true
                    Filtre.COTE_MANQUANTE -> a.album.coteEstimee == null && a.album.coteStatut != CoteStatut.NON_COTEE
                    Filtre.A_CHOISIR -> a.album.coteStatut == CoteStatut.A_CHOISIR
                    Filtre.COTEES -> a.album.coteEstimee != null
                    Filtre.DEDICACEES -> a.album.dedicace
                }
        }.sortedWith(
            when (c.tri) {
                Tri.SERIE -> compareBy({ it.album.serieNorm ?: normaliser(it.album.titre) }, { it.album.tome?.toIntOrNull() ?: Int.MAX_VALUE }, { it.album.tome })
                Tri.TITRE -> compareBy { normaliser(it.album.titre) }
                Tri.COTE -> compareByDescending { it.album.coteEstimee ?: -1 }
                Tri.AJOUT -> compareByDescending { it.album.ajouteLe }
            },
        )
        ListeState(
            albums = albums,
            meubles = meubles,
            nbTotal = tous.size,
            valeurTotale = tous.sumOf { it.album.coteEstimee ?: 0 },
            valeurAffichee = albums.sumOf { it.album.coteEstimee ?: 0 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeState())
}

@Composable
fun ListeScreen(onAlbum: (Long) -> Unit, viewModel: ListeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c by viewModel.criteres.collectAsStateWithLifecycle()
    fun maj(f: (Criteres) -> Criteres) { viewModel.criteres.value = f(viewModel.criteres.value) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = c.recherche,
                onValueChange = { v -> maj { it.copy(recherche = v) } },
                placeholder = { Text("Titre, série, auteur") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuChip("Tri : ${c.tri.libelle}", Tri.entries.map { it.libelle to { maj { c -> c.copy(tri = it) } } })
                MenuChip(
                    state.meubles.firstOrNull { it.meuble.id == c.meubleId }?.meuble?.nom ?: "Tous les meubles",
                    listOf("Tous les meubles" to { maj { it.copy(meubleId = null) } }) +
                        state.meubles.map { m -> m.meuble.nom to { maj { it.copy(meubleId = m.meuble.id) } } },
                )
                FilterChip(c.parSerie, { maj { it.copy(parSerie = !it.parSerie) } }, { Text("Par série") })
                Filtre.entries.forEach { f ->
                    FilterChip(c.filtre == f, { maj { it.copy(filtre = if (it.filtre == f) null else f) } }, { Text(f.libelle) })
                }
            }
            Text(
                "${state.albums.size} / ${state.nbTotal} albums · ${state.valeurAffichee} € affichés · collection ≈ ${state.valeurTotale} €",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        val groupes = if (c.parSerie) state.albums.groupBy { it.album.serie ?: "Sans série" } else mapOf("" to state.albums)
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            groupes.forEach { (serie, albums) ->
                if (serie.isNotEmpty()) {
                    item(key = "s:$serie") {
                        Text(
                            "$serie (${albums.size}) · ${albums.sumOf { it.album.coteEstimee ?: 0 }} €",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                items(albums, key = { it.album.id }) { a -> Ligne(a, state.meubles, onAlbum) }
            }
        }
    }
}

@Composable
private fun Ligne(a: AlbumComplet, meubles: List<MeubleComplet>, onAlbum: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onAlbum(a.album.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Couverture(a.album, Modifier.size(40.dp, 54.dp).clip(MaterialTheme.shapes.extraSmall))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(a.album.tome?.let { "T$it" }, a.album.titre).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    "dédicacée".takeIf { a.album.dedicace },
                    a.auteurs.joinToString { it.nom }.ifEmpty { null },
                    meubles.libelleEtagere(a.album.etagereId),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(a.album.coteLibelle(), style = MaterialTheme.typography.labelLarge)
    }
}
