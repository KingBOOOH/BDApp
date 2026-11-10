package fr.noe.bda.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.noe.bda.data.bedetheque.BedethequeClient
import fr.noe.bda.data.local.BdaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a local copy of each cover (the library must show offline) and derives the spine
 * colour from it. bedetheque images go through [BedethequeClient], so they share its throttle.
 */
@Singleton
class Couvertures @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val bedetheque: BedethequeClient,
    private val dao: BdaDao,
) {
    /** Best effort: a missing cover only means a plain spine. */
    suspend fun telecharger(albumId: Long, url: String) {
        val octets = try {
            if ("bedetheque.com" in url) bedetheque.octets(url) else withContext(Dispatchers.IO) { charger(url) }
        } catch (e: IOException) {
            return
        } ?: return
        val (fichier, couleur) = withContext(Dispatchers.IO) {
            val fichier = File(context.filesDir, "covers/$albumId.jpg").apply {
                parentFile?.mkdirs()
                writeBytes(octets)
            }
            fichier to couleurDominante(octets)
        }
        val album = dao.album(albumId) ?: return
        dao.modifierAlbum(album.copy(couvertureUrl = url, couvertureFichier = fichier.path, couleurDos = couleur ?: album.couleurDos))
    }

    private fun charger(url: String): ByteArray? =
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }

    private fun couleurDominante(octets: ByteArray): Int? {
        // A thumbnail is plenty for a palette
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        val image = BitmapFactory.decodeByteArray(octets, 0, octets.size, options) ?: return null
        return Palette.from(image).generate().let { it.darkVibrantSwatch ?: it.dominantSwatch }?.rgb
    }
}
