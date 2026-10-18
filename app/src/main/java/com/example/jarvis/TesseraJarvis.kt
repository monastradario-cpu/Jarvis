package com.example.jarvis

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Tessera "Jarvis" nel menu a tendina: un tocco e Jarvis ascolta. */
class TesseraJarvis : TileService() {

    override fun onClick() {
        super.onClick()
        if (ServizioJarvis.attivo) {
            ServizioJarvis.invia(this, ServizioJarvis.AZIONE_PARLA)
            return
        }
        val intento = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("ascolta", true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 3, intento, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intento)
        }
    }
}
