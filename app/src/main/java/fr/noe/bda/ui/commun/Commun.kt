package fr.noe.bda.ui.commun

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import fr.noe.bda.data.bedetheque.Cote
import fr.noe.bda.data.bedetheque.normaliser
import fr.noe.bda.data.local.Album
import fr.noe.bda.data.local.CoteStatut
import java.io.File
import java.text.DateFormat
import java.util.Date

fun Album.coteLibelle(): String = when (coteStatut) {
    CoteStatut.NON_CHERCHEE -> "cote à chercher"
    CoteStatut.NON_TROUVEE -> "cote non trouvée"
    CoteStatut.A_CHOISIR -> "édition à choisir"
    CoteStatut.NON_COTEE -> "non coté"
    CoteStatut.TROUVEE, CoteStatut.MANUELLE, CoteStatut.ERREUR, CoteStatut.EXCEL -> when {
        coteMin != null && coteMax != null && coteMin != coteMax -> "$coteMin–$coteMax €"
        coteMax != null && coteMin == null -> "< $coteMax €"
        coteMin != null && coteMax == null -> "> $coteMin €"
        coteEstimee != null -> "$coteEstimee €"
        else -> "cote illisible"
    } + when (coteStatut) {
        CoteStatut.ERREUR -> " (ancienne)"
        CoteStatut.EXCEL -> " (liste)"
        else -> ""
    }
}

fun Cote.libelle(): String = when (this) {
    is Cote.Estimee -> texte
    Cote.NonCotee -> "non coté"
    Cote.Absente, is Cote.Illisible -> "cote illisible"
}

/** "Série 3 · Titre", or just the title. */
fun Album.libelle(): String =
    listOfNotNull(serie?.let { s -> tome?.let { "$s $it" } ?: s }, titre.takeIf { normaliser(it) != serieNorm })
        .joinToString(" · ")

/** The local copy when there is one, so it shows offline. */
@Composable
fun Couverture(album: Album, modifier: Modifier = Modifier) {
    val modele: Any? = album.couvertureFichier?.let(::File)?.takeIf { it.exists() } ?: album.couvertureUrl
    if (modele == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        AsyncImage(model = modele, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

/** A chip that opens a menu of [options] (label, action). */
@Composable
fun MenuChip(libelle: String, options: List<Pair<String, () -> Unit>>) {
    var ouvert by remember { mutableStateOf(false) }
    // The Box anchors the menu under its chip
    Box {
        FilterChip(selected = false, onClick = { ouvert = true }, label = { Text(libelle) })
        DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            options.forEach { (texte, action) -> DropdownMenuItem(text = { Text(texte) }, onClick = { action(); ouvert = false }) }
        }
    }
}

fun dateLibelle(millis: Long): String = DateFormat.getDateInstance().format(Date(millis))

/** "Ajouté le …": tap to pick another day. The picker works in UTC midnights, which show as the same day in France. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoixDate(millis: Long, onChoix: (Long) -> Unit, modifier: Modifier = Modifier) {
    var ouvert by remember { mutableStateOf(false) }
    TextButton(onClick = { ouvert = true }, modifier = modifier) { Text("Ajouté le ${dateLibelle(millis)}") }
    if (ouvert) {
        val etat = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { ouvert = false },
            confirmButton = {
                TextButton(onClick = { etat.selectedDateMillis?.let(onChoix); ouvert = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { ouvert = false }) { Text("Annuler") } },
        ) { DatePicker(etat) }
    }
}
