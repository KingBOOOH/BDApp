package fr.noe.bda

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import fr.noe.bda.work.CoteWorker
import javax.inject.Inject

@HiltAndroidApp
class BdaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    // Albums never looked up (a restored backup, a pass cut short) get their turn at each start
    override fun onCreate() {
        super.onCreate()
        CoteWorker.chercherNouveaux(this)
    }

    // Workers get their dependencies from Hilt; the default initializer is removed in the manifest
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
