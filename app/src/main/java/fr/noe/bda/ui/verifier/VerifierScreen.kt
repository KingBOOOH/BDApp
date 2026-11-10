package fr.noe.bda.ui.verifier

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.noe.bda.data.bedetheque.BedethequeSource
import fr.noe.bda.data.book.Isbn
import fr.noe.bda.data.bedetheque.cleSerie
import fr.noe.bda.data.bedetheque.memeTome
import fr.noe.bda.data.bedetheque.normaliser
import fr.noe.bda.data.local.AlbumComplet
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.lookup.BookLookup
import fr.noe.bda.data.lookup.LookupResult
import fr.noe.bda.ui.commun.Couverture
import fr.noe.bda.ui.commun.coteLibelle
import fr.noe.bda.ui.commun.libelle
import fr.noe.bda.ui.liste.correspond
import fr.noe.bda.ui.scan.ScannerIsbn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** The answer for the last scanned ISBN. */
data class Verdict(val isbn: String, val possede: Boolean?, val texte: String, val albums: List<AlbumComplet> = emptyList())

@HiltViewModel
class VerifierViewModel @Inject constructor(
    private val dao: BdaDao,
    private val bedetheque: BedethequeSource,
    private val lookup: BookLookup,
) : ViewModel() {

    val recherche = MutableStateFlow("")
    val verdict = MutableStateFlow<Verdict?>(null)

    /** Typed search: every album whose title, series or author matches. */
    val resultats = combine(dao.observerAlbums(), recherche) { albums, q ->
        val norm = normaliser(q)
        if (norm.isEmpty()) emptyList() else albums.filter { it.correspond(norm) }
            .sortedWith(compareBy({ it.album.serieNorm ?: normaliser(it.album.titre) }, { it.album.tome?.toIntOrNull() ?: Int.MAX_VALUE }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A typed ISBN is checked like a scanned one. */
    fun saisir(texte: String) {
        recherche.value = texte
        Isbn.toIsbn13(texte.trim())?.let(::scanner)
    }

    /**
     * Same ISBN first. Albums imported from the list have none, so bedetheque then names the
     * series and tome of that ISBN, looked for in the collection.
     */
    fun scanner(isbn: String) = viewModelScope.launch {
        if (verdict.value?.isbn == isbn) return@launch
        dao.albumParIsbn(isbn)?.let { album ->
            verdict.value = Verdict(isbn, true, "Déjà dans la collection", dao.observerAlbums().first().filter { it.album.id == album.id })
            return@launch
        }
        verdict.value = Verdict(isbn, null, "Recherche de $isbn…")
        val candidat = try { bedetheque.rechercherParIsbn(isbn).firstOrNull() } catch (e: IOException) { null }
        if (candidat == null) {
            val titre = (lookup.findByIsbn(isbn) as? LookupResult.Found)?.metadata?.title
            verdict.value = Verdict(isbn, false, "Pas dans la collection${titre?.let { " : $it" } ?: ""} (ISBN $isbn)")
            return@launch
        }
        val cle = cleSerie(candidat.serie)
        val trouves = dao.observerAlbums().first().filter { a ->
            cleSerie(a.album.serie ?: a.album.titre) == cle &&
                (candidat.numero == null || a.album.tome?.let { memeTome(candidat.numero, it) } == true)
        }
        val nom = listOfNotNull(candidat.serie, candidat.numero?.let { "tome $it" }).joinToString(" — ") + " : ${candidat.titre}"
        verdict.value = if (trouves.isEmpty()) Verdict(isbn, false, "Pas dans la collection : $nom")
        else Verdict(isbn, true, "Déjà dans la collection : $nom", trouves)
    }
}

@Composable
fun VerifierScreen(onAlbum: (Long) -> Unit, viewModel: VerifierViewModel = hiltViewModel()) {
    val recherche by viewModel.recherche.collectAsStateWithLifecycle()
    val verdict by viewModel.verdict.collectAsStateWithLifecycle()
    val resultats by viewModel.resultats.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScannerIsbn(
                onIsbn = viewModel::scanner,
                message = verdict?.texte,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.medium),
            )
        }
        verdict?.let { v ->
            item {
                val (fond, texte) = when (v.possede) {
                    true -> Color(0xFF2E7D32) to "✓ ${v.texte}"
                    false -> Color(0xFFC62828) to "✗ ${v.texte}"
                    null -> MaterialTheme.colorScheme.surfaceVariant to v.texte
                }
                Text(
                    texte,
                    color = if (v.possede == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(fond).padding(12.dp),
                )
            }
            items(v.albums, key = { "v${it.album.id}" }) { LigneAlbum(it, onAlbum) }
        }
        item {
            OutlinedTextField(
                value = recherche,
                onValueChange = viewModel::saisir,
                placeholder = { Text("Titre, série, auteur ou ISBN") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (recherche.isNotBlank() && Isbn.toIsbn13(recherche.trim()) == null) {
            item {
                Text(
                    if (resultats.isEmpty()) "Rien dans la collection pour « $recherche »" else "${resultats.size} album(s) dans la collection",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(resultats, key = { it.album.id }) { LigneAlbum(it, onAlbum) }
        }
    }
}

@Composable
private fun LigneAlbum(complet: AlbumComplet, onAlbum: (Long) -> Unit) {
    val album = complet.album
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onAlbum(album.id) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Couverture(album, Modifier.size(40.dp, 54.dp).clip(MaterialTheme.shapes.extraSmall))
        Column(Modifier.weight(1f)) {
            Text(album.libelle(), style = MaterialTheme.typography.bodyLarge)
            Text(album.coteLibelle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
