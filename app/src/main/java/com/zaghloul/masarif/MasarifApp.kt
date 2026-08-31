package com.zaghloul.masarif

import android.app.Application
import com.zaghloul.masarif.data.Repo
import com.zaghloul.masarif.ui.AppState

class MasarifApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm the singletons so the notification listener and SMS receiver, which
        // can fire before any Activity exists, never race to build the database.
        Repo.get(this)
        AppState.get(this)
    }
}
