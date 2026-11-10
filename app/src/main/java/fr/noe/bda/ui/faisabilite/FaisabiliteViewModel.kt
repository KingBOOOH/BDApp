package fr.noe.bda.ui.faisabilite

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.noe.bda.data.bedetheque.BedethequeClient
import fr.noe.bda.data.bedetheque.BedethequeSource
import fr.noe.bda.data.bedetheque.Correspondance
import fr.noe.bda.data.bedetheque.Cote
import fr.noe.bda.data.bedetheque.Edition
import fr.noe.bda.data.bedetheque.Requete
import fr.noe.bda.data.bedetheque.trouverEdition
import fr.noe.bda.data.book.Isbn
import fr.noe.bda.data.lookup.BookLookup
import fr.noe.bda.data.lookup.LookupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One scanned ISBN, as far as the pipeline got. */
data class Ligne(
    val saisie: String,
    val lookup: String = "…",
    val correspondance: Correspondance? = null,
    val requetes: List<Requete> = emptyList(),
    val dureeMs: Long? = null,
    val erreur: String? = null,
)

data class FaisabiliteState(
    val saisie: String = "",
    val lignes: List<Ligne> = emptyList(),
    val enCours: Boolean = false,
    val dernierScan: String? = null,
)

/**
 * Step 1 of the plan: runs the real add pipeline (lookup, then bedetheque search, album
 * page, edition matching) on a list of ISBNs and reports what each step got.
 */
@HiltViewModel
class FaisabiliteViewModel @Inject constructor(
    private val lookup: BookLookup,
    private val bedetheque: BedethequeSource,
    private val client: BedethequeClient,
) : ViewModel() {

    private val _state = MutableStateFlow(FaisabiliteState())
    val state: StateFlow<FaisabiliteState> = _state
    private var job: Job? = null

    fun saisir(texte: String) = _state.update { it.copy(saisie = texte) }

    /** A scanned ISBN goes to the end of the list, once. */
    fun ajouterScan(isbn13: String) = _state.update { s ->
        val deja = s.saisie.split(Regex("[\\s,;]+")).any { Isbn.toIsbn13(it) == isbn13 }
        s.copy(
            saisie = if (deja) s.saisie else s.saisie.trimEnd().let { if (it.isEmpty()) isbn13 else "$it\n$isbn13" },
            dernierScan = if (deja) "déjà dans la liste : $isbn13" else "ajouté : $isbn13",
        )
    }

    fun lancer() {
        val isbns = _state.value.saisie.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.distinct()
        _state.update { it.copy(lignes = isbns.map { s -> Ligne(s) }, enCours = true) }
        job = viewModelScope.launch {
            try {
                isbns.forEachIndexed { i, saisie -> traiter(i, saisie) }
            } finally {
                client.observateur = null
                _state.update { it.copy(enCours = false) }
                // Readable from a computer with `adb logcat -s BDA-Rapport`; logcat truncates long messages
                rapport(_state.value.lignes).lines().forEach { Log.i("BDA-Rapport", it) }
            }
        }
    }

    fun arreter() {
        job?.cancel()
    }

    private suspend fun traiter(index: Int, saisie: String) {
        val requetes = mutableListOf<Requete>()
        client.observateur = { r -> synchronized(requetes) { requetes += r } }
        val debut = System.currentTimeMillis()
        fun maj(f: (Ligne) -> Ligne) = _state.update { s ->
            s.copy(lignes = s.lignes.toMutableList().also { it[index] = f(it[index]).copy(requetes = synchronized(requetes) { requetes.toList() }) })
        }

        val isbn13 = Isbn.toIsbn13(saisie)
        if (isbn13 == null) return maj { it.copy(lookup = "—", erreur = "ISBN invalide") }
        try {
            val metadata = when (val r = lookup.findByIsbn(isbn13)) {
                is LookupResult.Found -> r.metadata.also { m ->
                    maj { it.copy(lookup = "${m.title} — ${m.authors.joinToString()} (${m.source})") }
                }
                LookupResult.NotFound -> null.also { maj { it.copy(lookup = "inconnu d'OpenLibrary et Google Books") } }
                LookupResult.Unavailable -> null.also { maj { it.copy(lookup = "sources injoignables") } }
            }
            val correspondance = bedetheque.trouverEdition(isbn13, metadata?.title)
            maj { it.copy(correspondance = correspondance, dureeMs = System.currentTimeMillis() - debut) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            maj { it.copy(erreur = "${e::class.simpleName}: ${e.message}", dureeMs = System.currentTimeMillis() - debut) }
        }
    }
}

fun Cote.texte(): String = when (this) {
    is Cote.Estimee -> "$texte [min=${minEuros ?: "–"} max=${maxEuros ?: "–"}]"
    Cote.NonCotee -> "non coté"
    Cote.Absente -> "pas de ligne Estimation"
    is Cote.Illisible -> "ILLISIBLE: « $texte »"
}

fun Edition.resume(): String = "$libelle · DL ${depotLegal ?: "?"} · ${cote.texte()}"

/** Plain-text report to paste back into the conversation. */
fun rapport(lignes: List<Ligne>): String = buildString {
    val faites = lignes.filter { it.dureeMs != null || it.erreur != null }
    val trouvees = faites.count { it.correspondance is Correspondance.Trouvee }
    val aChoisir = faites.count { it.correspondance is Correspondance.AChoisir }
    val aucunes = faites.count { it.correspondance is Correspondance.Aucune }
    val illisibles = faites.flatMap { l ->
        when (val c = l.correspondance) {
            is Correspondance.Trouvee -> listOf(c.edition)
            is Correspondance.AChoisir -> c.editions
            else -> emptyList()
        }
    }.count { it.cote is Cote.Illisible || it.cote is Cote.Absente }
    val codes = faites.flatMap { it.requetes }.groupingBy { it.code }.eachCount()
    appendLine("BDA — faisabilité BDthèque — ${faites.size}/${lignes.size} ISBN traités")
    appendLine("auto: $trouvees · à choisir: $aChoisir · aucune: $aucunes · erreurs: ${faites.count { it.erreur != null }}")
    appendLine("cotes illisibles/absentes: $illisibles · codes HTTP: $codes · durée totale: ${faites.sumOf { it.dureeMs ?: 0 } / 1000} s")
    lignes.forEach { l ->
        appendLine()
        appendLine("■ ${l.saisie} (${l.dureeMs?.div(1000) ?: "?"} s, ${l.requetes.size} requêtes)")
        appendLine("  lookup: ${l.lookup}")
        l.erreur?.let { appendLine("  ERREUR: $it") }
        when (val c = l.correspondance) {
            is Correspondance.Trouvee -> {
                appendLine("  AUTO (${c.raison}): ${c.fiche.serie} — tome ${c.fiche.tome ?: "?"} — ${c.fiche.titre}")
                appendLine("  ${c.edition.resume()}")
                appendLine("  ${c.edition.url}")
            }
            is Correspondance.AChoisir -> {
                appendLine("  À CHOISIR (${c.raison}): ${c.fiches.joinToString { "${it.serie} t.${it.tome ?: "?"}" }}")
                c.editions.forEach { appendLine("   - ${it.resume()} · ${it.url}") }
            }
            is Correspondance.Aucune -> appendLine("  AUCUNE: ${c.raison}")
            null -> Unit
        }
        l.requetes.forEach { appendLine("   ${it.code} ${it.dureeMs} ms ${it.url}") }
    }
}
