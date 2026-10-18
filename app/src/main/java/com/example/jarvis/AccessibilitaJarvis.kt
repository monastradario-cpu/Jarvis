package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Plugin Controllo delle app: tramite l'accessibilità di Android Jarvis legge lo schermo,
 * tocca pulsanti, scrive nei campi, scorre e usa i tasti di sistema.
 * Si attiva solo se l'utente lo abilita in Impostazioni > Accessibilità.
 */
class AccessibilitaJarvis : AccessibilityService() {

    companion object {
        @Volatile var istanza: AccessibilitaJarvis? = null
        const val AVVISO =
            "Il controllo delle app non è attivo. Attiva Jarvis in Impostazioni, Accessibilità, dalle impostazioni di Jarvis."
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        istanza = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        istanza = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        istanza = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---------- lettura dello schermo ----------

    private fun nodi(): List<AccessibilityNodeInfo> {
        val radice = rootInActiveWindow ?: return emptyList()
        val tutti = ArrayList<AccessibilityNodeInfo>()
        val coda = ArrayDeque<AccessibilityNodeInfo>()
        coda.add(radice)
        while (coda.isNotEmpty() && tutti.size < 1500) {
            val nodo = coda.removeFirst()
            tutti.add(nodo)
            for (i in 0 until nodo.childCount) nodo.getChild(i)?.let { coda.add(it) }
        }
        return tutti
    }

    private fun etichetta(n: AccessibilityNodeInfo): String {
        val testo = n.text?.toString()?.trim().orEmpty()
        val descrizione = n.contentDescription?.toString()?.trim().orEmpty()
        val suggerimento = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
        return listOf(testo, descrizione, suggerimento).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
    }

    fun leggiSchermo(): String {
        Thread.sleep(1200) // lascia il tempo all'app di aprirsi o aggiornarsi
        val radice = rootInActiveWindow ?: return "Non riesco a leggere lo schermo in questo momento."
        val app = radice.packageName?.toString() ?: "sconosciuta"
        val righe = LinkedHashSet<String>()
        for (n in nodi()) {
            if (!n.isVisibleToUser) continue
            val e = etichetta(n)
            when {
                n.isEditable -> righe.add("[campo] ${e.ifEmpty { "senza nome" }}")
                e.isEmpty() -> {}
                n.isClickable -> righe.add("[pulsante] $e")
                else -> righe.add(e)
            }
        }
        val testo = righe.joinToString("\n")
        return "App aperta: $app\n" + (if (testo.isBlank()) "Nessun testo visibile." else testo.take(3500))
    }

    // ---------- azioni ----------

    private fun cliccabile(nodo: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = nodo
        while (n != null && !n.isClickable) n = n.parent
        return n
    }

    fun tocca(cercato: String): String {
        val c = cercato.lowercase(Locale.ITALIAN).trim()
        if (c.isEmpty()) return "Dimmi cosa toccare."
        val candidati = nodi().filter { it.isVisibleToUser && etichetta(it).lowercase(Locale.ITALIAN).contains(c) }
        val nodo = candidati.firstOrNull { etichetta(it).lowercase(Locale.ITALIAN) == c }
            ?: candidati.firstOrNull()
            ?: return "Non trovo \"$cercato\" sullo schermo."
        val bersaglio = cliccabile(nodo)
        val riuscito = bersaglio?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: toccaCoordinate(nodo)
        Thread.sleep(900)
        return if (riuscito) "Ho toccato \"${etichetta(nodo)}\"." else "Non sono riuscito a toccare \"$cercato\"."
    }

    private fun toccaCoordinate(nodo: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val area = Rect()
        nodo.getBoundsInScreen(area)
        val percorso = Path().apply { moveTo(area.exactCenterX(), area.exactCenterY()) }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(percorso, 0, 80))
            .build()
        return dispatchGesture(gesto, null, null)
    }

    fun scrivi(testo: String, campo: String): String {
        val campi = nodi().filter { it.isEditable && it.isVisibleToUser }
        val c = campo.lowercase(Locale.ITALIAN).trim()
        val nodo = (if (c.isNotEmpty()) campi.firstOrNull { etichetta(it).lowercase(Locale.ITALIAN).contains(c) } else null)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: campi.firstOrNull()
            ?: return "Non trovo un campo dove scrivere."
        nodo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val dati = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, testo)
        }
        val riuscito = nodo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, dati)
        Thread.sleep(400)
        return if (riuscito) "Ho scritto il testo." else "Non sono riuscito a scrivere."
    }

    fun scorri(direzione: String): String {
        val d = direzione.lowercase(Locale.ITALIAN)
        val versoSu = d.contains("su") || d.contains("alto") || d.contains("indietro")
        val nodo = nodi().firstOrNull { it.isScrollable && it.isVisibleToUser } ?: return "Non c'è niente da scorrere."
        val riuscito = nodo.performAction(
            if (versoSu) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
        Thread.sleep(600)
        return if (riuscito) "Ho fatto scorrere lo schermo." else "Non si può scorrere oltre."
    }

    fun azioneGlobale(azione: String): String {
        val a = azione.lowercase(Locale.ITALIAN)
        val (codice, messaggio) = when {
            a.contains("indietro") -> AccessibilityService.GLOBAL_ACTION_BACK to "Indietro."
            a.contains("home") || a.contains("inizio") -> AccessibilityService.GLOBAL_ACTION_HOME to "Schermata home."
            a.contains("recent") -> AccessibilityService.GLOBAL_ACTION_RECENTS to "App recenti."
            a.contains("rapid") -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Impostazioni rapide."
            a.contains("notific") -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Notifiche aperte."
            a.contains("blocc") && Build.VERSION.SDK_INT >= 28 ->
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Schermo bloccato."
            (a.contains("screenshot") || a.contains("schermat")) && Build.VERSION.SDK_INT >= 28 ->
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT to "Screenshot fatto."
            else -> return "Azione non riconosciuta."
        }
        return if (performGlobalAction(codice)) messaggio else "Non ci sono riuscito."
    }

    /** Dopo aver aperto una chat WhatsApp con il testo già scritto, preme Invia. */
    fun toccaInvioWhatsapp(): Boolean {
        repeat(8) {
            Thread.sleep(700)
            val perId = rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
            val nodo = perId ?: nodi().firstOrNull {
                val e = etichetta(it).lowercase(Locale.ITALIAN)
                e == "invia" || e == "send"
            }
            if (nodo != null && cliccabile(nodo)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }
}
