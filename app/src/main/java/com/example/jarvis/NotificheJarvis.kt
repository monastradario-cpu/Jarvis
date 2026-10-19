package com.example.jarvis

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plugin Notifiche: Jarvis legge i messaggi in arrivo e risponde direttamente dalla notifica
 * (WhatsApp, Telegram, SMS e tutte le app che offrono "Rispondi" nella notifica).
 * I messaggi restano solo in memoria sul telefono.
 */
class NotificheJarvis : NotificationListenerService() {

    class Messaggio(
        val chiave: String,
        val app: String,
        val mittente: String,
        val testo: String,
        val ora: Long
    )

    companion object {
        @Volatile var istanza: NotificheJarvis? = null
        private val recenti = ArrayList<Messaggio>()
        private const val MASSIMO = 60
        private const val AVVISO =
            "Non ho accesso alle notifiche. Attivalo dalle impostazioni di ${Nome.attuale}, nella sezione Sempre attivo."

        fun abilitato(ctx: Context): Boolean {
            val elenco = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return elenco.contains(ctx.packageName)
        }

        fun leggi(ctx: Context, filtro: String): String {
            if (!abilitato(ctx)) return AVVISO
            val f = filtro.lowercase(Locale.ITALIAN).trim()
            val elenco = synchronized(recenti) { recenti.toList() }
                .filter {
                    f.isEmpty() ||
                        it.app.lowercase(Locale.ITALIAN).contains(f) ||
                        it.mittente.lowercase(Locale.ITALIAN).contains(f)
                }
                .takeLast(10)
            if (elenco.isEmpty()) return "Non ci sono messaggi nuovi" + (if (f.isEmpty()) "." else " per $filtro.")
            val formato = SimpleDateFormat("HH:mm", Locale.ITALIAN)
            return elenco.joinToString("\n") { "${formato.format(Date(it.ora))}, ${it.app}, da ${it.mittente}: ${it.testo}" }
        }

        fun rispondi(ctx: Context, contatto: String, testo: String, app: String): String {
            if (!abilitato(ctx)) return AVVISO
            val servizio = istanza ?: return AVVISO
            if (testo.isBlank()) return "La risposta è vuota."
            val c = contatto.lowercase(Locale.ITALIAN).trim()
            val a = app.lowercase(Locale.ITALIAN).trim()
            val attive = try {
                servizio.activeNotifications?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val candidata = attive.sortedByDescending { it.postTime }.firstOrNull { sbn ->
                val titolo = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString()?.lowercase(Locale.ITALIAN) ?: ""
                val nomeApp = servizio.nomeApp(sbn.packageName).lowercase(Locale.ITALIAN)
                titolo.contains(c) && (a.isEmpty() || nomeApp.contains(a)) && azioneRisposta(sbn.notification) != null
            } ?: return "Non trovo una notifica di $contatto a cui rispondere. Posso rispondere solo ai messaggi ancora presenti tra le notifiche."

            val azione = azioneRisposta(candidata.notification)
                ?: return "Questa notifica non permette di rispondere."
            val ingressi = azione.remoteInputs ?: return "Questa notifica non permette di rispondere."
            val intento = Intent()
            val dati = Bundle()
            for (ingresso in ingressi) dati.putCharSequence(ingresso.resultKey, testo)
            RemoteInput.addResultsToIntent(ingressi, intento, dati)
            return try {
                azione.actionIntent.send(servizio, 0, intento)
                "Risposta inviata a $contatto."
            } catch (e: Exception) {
                "Non sono riuscito a rispondere: ${e.message}"
            }
        }

        private fun azioneRisposta(n: Notification): Notification.Action? =
            n.actions?.firstOrNull { azione -> azione.remoteInputs?.any { it.allowFreeFormInput } == true }
    }

    fun nomeApp(pacchetto: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pacchetto, 0)).toString()
    } catch (e: Exception) {
        pacchetto
    }

    override fun onListenerConnected() {
        istanza = this
        try {
            activeNotifications?.forEach { registra(it) }
        } catch (e: Exception) {
            // nessuna notifica leggibile
        }
    }

    override fun onListenerDisconnected() {
        istanza = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val messaggio = registra(sbn) ?: return
        // Jarvis proattivo: avvisa se qualcuno scrive più volte di fila
        try { Proattivo.messaggioRicevuto(this, sbn.packageName, messaggio.app, messaggio.mittente) } catch (e: Exception) { }
    }

    private fun registra(sbn: StatusBarNotification): Messaggio? {
        if (sbn.packageName == packageName) return null
        val n = sbn.notification
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val extra = n.extras
        val mittente = extra.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val testo = (extra.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extra.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return null
        if (testo.isBlank()) return null
        val messaggio = Messaggio(sbn.key, nomeApp(sbn.packageName), mittente, testo.take(300), sbn.postTime)
        synchronized(recenti) {
            recenti.removeAll { it.chiave == messaggio.chiave && it.testo == messaggio.testo }
            recenti.add(messaggio)
            while (recenti.size > MASSIMO) recenti.removeAt(0)
        }
        return messaggio
    }
}
