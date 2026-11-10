package fr.noe.bda.ui.ajout

import android.content.Context
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.noe.bda.data.book.Isbn
import fr.noe.bda.data.local.Album
import fr.noe.bda.data.local.BdaDao
import fr.noe.bda.data.lookup.BookLookup
import fr.noe.bda.data.lookup.LookupResult
import fr.noe.bda.ui.commun.ChoixDate
import fr.noe.bda.ui.commun.dateLibelle
import fr.noe.bda.ui.commun.Couverture
import fr.noe.bda.ui.commun.coteLibelle
import fr.noe.bda.ui.commun.libelle
import fr.noe.bda.ui.scan.ScannerIsbn
import fr.noe.bda.work.CoteWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** One line of the session's scans; [albumId] once saved (or found already in the collection). */
data class Scan(val cle: String, val albumId: Long?, val message: String)

@HiltViewModel
class AjoutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: BdaDao,
    private val lookup: BookLookup,
) : ViewModel() {

    /** Date given to the albums added now; null is "today", stamped at the moment of each scan. */
    val date = MutableStateFlow<Long?>(null)
    private val scans = MutableStateFlow<List<Scan>>(emptyList())
    // Saves one at a time, so two quick scans of the same ISBN don't create two albums
    private val verrou = Mutex()

    /** The session's scans, newest first, with their album as it is now (cote filled in by the worker). */
    val lignes = combine(scans, dao.observerAlbums()) { s, albums ->
        val parId = albums.associateBy { it.album.id }
        s.map { it to it.albumId?.let(parId::get)?.album }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun scanner(isbn13: String) = viewModelScope.launch {
        verrou.withLock {
            dao.albumParIsbn(isbn13)?.let { return@launch noter(Scan(isbn13, it.id, "déjà dans la collection")) }
            noter(Scan(isbn13, null, "recherche…"))
            val meta = (lookup.findByIsbn(isbn13) as? LookupResult.Found)?.metadata
            // Without a title, the ISBN stands in until bedetheque names the album
            enregistrer(isbn13, Album(isbn = isbn13, titre = meta?.title ?: isbn13, editeur = meta?.publisher,
                couvertureUrl = meta?.coverUrl, hauteurCm = meta?.heightCm, epaisseurCm = meta?.thicknessCm,
                pages = meta?.pageCount), meta?.authors.orEmpty())
        }
    }

    /** Typed: an ISBN goes through the scan path, anything else is a title (old albums without barcode). */
    fun saisir(texte: String) {
        val t = texte.trim().ifEmpty { return }
        Isbn.toIsbn13(t)?.let { scanner(it); return }
        viewModelScope.launch { verrou.withLock { enregistrer(t, Album(titre = t), emptyList()) } }
    }

    private suspend fun enregistrer(cle: String, album: Album, auteurs: List<String>) {
        val id = dao.ajouter(album.copy(ajouteLe = date.value ?: System.currentTimeMillis()), auteurs)
        noter(Scan(cle, id, "ajouté"))
        CoteWorker.chercherNouveaux(context)
    }

    private fun noter(scan: Scan) = scans.update { liste -> listOf(scan) + liste.filterNot { it.cle == scan.cle } }
}

@Composable
fun AjoutScreen(onAlbum: (Long) -> Unit, viewModel: AjoutViewModel = hiltViewModel()) {
    val lignes by viewModel.lignes.collectAsStateWithLifecycle()
    val date by viewModel.date.collectAsStateWithLifecycle()
    var saisie by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ChoixDate(date ?: System.currentTimeMillis(), { viewModel.date.value = it }) }
        item {
            ScannerIsbn(
                onIsbn = viewModel::scanner,
                message = lignes.firstOrNull()?.let { (scan, album) -> album?.libelle() ?: scan.message },
                modifier = Modifier.fillMaxWidth().height(260.dp).clip(MaterialTheme.shapes.medium),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = saisie,
                    onValueChange = { saisie = it },
                    label = { Text("ISBN ou titre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions { viewModel.saisir(saisie); saisie = "" },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { viewModel.saisir(saisie); saisie = "" }, enabled = saisie.isNotBlank()) { Text("Ajouter") }
            }
        }
        items(lignes, key = { it.first.cle }) { (scan, album) ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = album != null) { album?.let { onAlbum(it.id) } },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (album != null) Couverture(album, Modifier.size(40.dp, 54.dp).clip(MaterialTheme.shapes.extraSmall))
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(album?.libelle() ?: scan.cle, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (scan.message == "ajouté" && album != null) "${album.coteLibelle()} · ajouté le ${dateLibelle(album.ajouteLe)}" else scan.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
