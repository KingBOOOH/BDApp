package fr.noe.bda.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import fr.noe.bda.data.Cotes
import fr.noe.bda.data.bedetheque.BedethequeBloqueException
import fr.noe.bda.data.bedetheque.normaliser
import fr.noe.bda.data.local.BdaDao

enum class Portee { NOUVEAUX, TOUT, SERIE, AUTEUR }

/**
 * Refreshes cotes one album at a time (the client spaces the requests).
 *
 * Resuming after an interruption needs no saved cursor: the run's start time is in its input,
 * and the album list is "not updated since then", so a rerun skips what is already done.
 * NOUVEAUX is the add flow's queue: albums just scanned, looked up in the background.
 */
@HiltWorker
class CoteWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: BdaDao,
    private val cotes: Cotes,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val portee = Portee.valueOf(inputData.getString(PORTEE) ?: Portee.TOUT.name)
        val valeur = inputData.getString(VALEUR).orEmpty()
        val debut = inputData.getLong(DEBUT, 0)
        var fait = 0
        while (true) {
            val ids = when (portee) {
                Portee.NOUVEAUX -> dao.nonCherches()
                Portee.TOUT -> dao.aRafraichir(debut)
                Portee.SERIE -> dao.aRafraichirSerie(normaliser(valeur), debut)
                Portee.AUTEUR -> dao.aRafraichirAuteur(normaliser(valeur), debut)
            }
            if (ids.isEmpty()) return Result.success()
            val total = fait + ids.size
            for (id in ids) {
                setProgress(workDataOf(FAIT to fait, TOTAL to total))
                if (portee != Portee.NOUVEAUX) premierPlan(fait, total)
                try {
                    cotes.rafraichir(id)
                } catch (e: BedethequeBloqueException) {
                    return Result.failure(workDataOf(ERREUR to e.message))
                }
                fait++
            }
            // A failed album stays in the list (ERREUR, date unchanged): one pass is enough
            if (portee != Portee.NOUVEAUX) return Result.success()
        }
    }

    /** A full run takes about 2.5 s per album: a foreground notification keeps it alive and visible. */
    private suspend fun premierPlan(fait: Int, total: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CANAL, "Mises à jour des cotes", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CANAL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mise à jour des cotes")
            .setContentText("$fait / $total albums")
            .setProgress(total, fait, false)
            .setOngoing(true)
            .build()
        try {
            setForeground(ForegroundInfo(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (e: IllegalStateException) {
            // Not allowed from the background on recent Android: the work still runs, without the notification
        }
    }

    companion object {
        const val PORTEE = "portee"
        const val VALEUR = "valeur"
        const val DEBUT = "debut"
        const val FAIT = "fait"
        const val TOTAL = "total"
        const val ERREUR = "erreur"
        const val NOM_MASSE = "cotes"
        const val NOM_NOUVEAUX = "cotes-nouveaux"
        private const val CANAL = "cotes"

        private val reseau = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Looks up the albums just added; a call while one runs queues another pass after it. */
        fun chercherNouveaux(context: Context) {
            val requete = OneTimeWorkRequestBuilder<CoteWorker>()
                .setConstraints(reseau)
                .setInputData(workDataOf(PORTEE to Portee.NOUVEAUX.name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOM_NOUVEAUX, ExistingWorkPolicy.APPEND_OR_REPLACE, requete)
        }

        /** Bulk update. A run already in progress is kept rather than restarted. */
        fun mettreAJour(context: Context, portee: Portee, valeur: String = "") {
            val requete = OneTimeWorkRequestBuilder<CoteWorker>()
                .setConstraints(reseau)
                .setInputData(workDataOf(PORTEE to portee.name, VALEUR to valeur, DEBUT to System.currentTimeMillis()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOM_MASSE, ExistingWorkPolicy.KEEP, requete)
        }

        fun annuler(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NOM_MASSE)
        }
    }
}
