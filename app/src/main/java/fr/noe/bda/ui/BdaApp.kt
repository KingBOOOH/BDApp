package fr.noe.bda.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.noe.bda.ui.ajout.AjoutScreen
import fr.noe.bda.ui.detail.DetailScreen
import fr.noe.bda.ui.faisabilite.FaisabiliteScreen
import fr.noe.bda.ui.liste.ListeScreen
import fr.noe.bda.ui.outils.OutilsScreen
import fr.noe.bda.ui.verifier.VerifierScreen

private enum class Onglet(val route: String, val libelle: String, val icone: ImageVector) {
    VERIFIER("verifier", "Vérifier", Icons.Default.CheckCircle),
    LISTE("liste", "Liste", Icons.AutoMirrored.Filled.List),
    AJOUT("ajout", "Scanner", Icons.Default.AddCircle),
    OUTILS("outils", "Outils", Icons.Default.Build),
}

@Composable
fun BdaApp() {
    val nav = rememberNavController()
    val entree by nav.currentBackStackEntryAsState()
    val route = entree?.destination?.route
    val ouvrirAlbum: (Long) -> Unit = { nav.navigate("album/$it") }

    // Screens with their own top bar handle the status bar themselves; tabs pad for it below
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (Onglet.entries.any { it.route == route }) {
                NavigationBar {
                    Onglet.entries.forEach { o ->
                        NavigationBarItem(
                            selected = route == o.route,
                            onClick = {
                                nav.navigate(o.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(o.icone, null) },
                            label = { Text(o.libelle) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Onglet.VERIFIER.route, modifier = Modifier.padding(padding)) {
            composable(Onglet.VERIFIER.route) { Onglet { VerifierScreen(ouvrirAlbum) } }
            composable(Onglet.LISTE.route) { Onglet { ListeScreen(ouvrirAlbum) } }
            composable(Onglet.AJOUT.route) { Onglet { AjoutScreen(ouvrirAlbum) } }
            composable(Onglet.OUTILS.route) {
                Onglet { OutilsScreen(onFaisabilite = { nav.navigate("faisabilite") }) }
            }
            composable("album/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                DetailScreen(onRetour = { nav.popBackStack() })
            }
            composable("faisabilite") { FaisabiliteScreen() }
        }
    }
}

@Composable
private fun Onglet(contenu: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding()) { contenu() }
}
