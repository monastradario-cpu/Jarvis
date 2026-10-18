package com.example.jarvis

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Collegamento con il PC Windows: sul computer gira il programmino jarvis_pc.py,
 * che riceve i comandi dal telefono sulla rete di casa (Wi-Fi).
 * Per accendere il PC si usa il Wake-on-LAN (va attivato nel BIOS e nella scheda di rete).
 */
class PontePc(private val prefs: SharedPreferences) {

    companion object {
        const val PORTA = 8765
    }

    private fun ip() = (prefs.getString("pc_ip", "") ?: "").trim()
    private fun token() = Cassaforte.leggi(prefs, "pc_token").trim()
    private fun mac() = (prefs.getString("pc_mac", "") ?: "").trim()
    fun configurato() = ip().isNotEmpty() && token().isNotEmpty()

    private val AVVISO = "Il PC non è collegato. Avvia jarvis_pc.py sul computer e inserisci in SISTEMA, COLLEGAMENTI " +
        "l'indirizzo e il codice che ti mostra."

    private fun codifica(t: String) = URLEncoder.encode(t, "UTF-8")

    private val AZIONI = setOf(
        "apri", "sito", "cerca", "volume_su", "volume_giu", "muto", "play_pausa", "avanti", "indietro",
        "blocca", "spegni", "riavvia", "annulla_spegnimento", "scrivi", "copia", "messaggio", "stato"
    )

    fun comando(azione: String, valore: String): String {
        if (!configurato()) return AVVISO
        val a = azione.lowercase().trim().replace(' ', '_').replace("giù", "giu")
        if (a !in AZIONI) return "Azione per il PC non riconosciuta: $azione."
        return try {
            val c = URL("http://${ip()}:$PORTA/comando?azione=${codifica(a)}&valore=${codifica(valore)}")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 15000
            c.setRequestProperty("X-Jarvis", token())
            val codice = c.responseCode
            val testo = (if (codice in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            when (codice) {
                in 200..299 -> try { JSONObject(testo).optString("esito", "Fatto sul PC.") } catch (e: Exception) { "Fatto sul PC." }
                401, 403 -> "Il PC ha rifiutato il comando: il codice di collegamento non è giusto."
                else -> "Il PC ha risposto con errore $codice."
            }
        } catch (e: Exception) {
            "Non raggiungo il PC: è acceso, collegato alla stessa rete e con jarvis_pc.py avviato? " +
                (if (mac().isNotEmpty()) "Posso provare ad accenderlo." else "")
        }
    }

    /** Wake-on-LAN: il "pacchetto magico" che accende il PC dalla rete. */
    fun accendi(): String {
        val m = mac().replace(Regex("[^0-9A-Fa-f]"), "")
        if (m.length != 12) return "Per accendere il PC mi serve il suo indirizzo MAC: lo trovi in SISTEMA, COLLEGAMENTI (te lo mostra jarvis_pc.py)."
        return try {
            val macByte = ByteArray(6) { m.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val pacchetto = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) pacchetto[i] = 0xFF.toByte()
            for (i in 0 until 16) System.arraycopy(macByte, 0, pacchetto, 6 + i * 6, 6)
            val destinazioni = mutableListOf("255.255.255.255")
            val ip = ip()
            if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(ip)) destinazioni.add(ip.substringBeforeLast('.') + ".255")
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (d in destinazioni) {
                    for (porta in listOf(9, 7)) {
                        socket.send(DatagramPacket(pacchetto, pacchetto.size, InetAddress.getByName(d), porta))
                    }
                }
            }
            "Ho mandato il segnale di accensione al PC. Se è configurato, si accende in qualche secondo."
        } catch (e: Exception) {
            "Non sono riuscito a mandare il segnale di accensione: ${e.message}."
        }
    }

    /** Manda un file al PC: finisce nella cartella Download\Jarvis. */
    fun inviaFile(ctx: Context, uri: Uri): String {
        if (!configurato()) return AVVISO
        return try {
            var nome = "file"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) nome = it.getString(0) ?: nome
            }
            val c = URL("http://${ip()}:$PORTA/file?nome=${codifica(nome)}").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = 120000
            c.doOutput = true
            c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("X-Jarvis", token())
            c.setRequestProperty("Content-Type", "application/octet-stream")
            ctx.contentResolver.openInputStream(uri)?.use { ingresso -> c.outputStream.use { ingresso.copyTo(it) } }
                ?: return "Non riesco a leggere il file."
            val codice = c.responseCode
            c.disconnect()
            if (codice in 200..299) "$nome inviato al PC, nella cartella Download\\Jarvis." else "Il PC ha risposto con errore $codice."
        } catch (e: Exception) {
            "Non raggiungo il PC: ${e.message}"
        }
    }
}
