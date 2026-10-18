package com.example.jarvis

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm

/**
 * Email con IMAP e SMTP: funziona con Gmail (con una "password per le app"), Libero, Yahoo, iCloud
 * e qualsiasi casella che permetta IMAP. Le credenziali restano sul telefono.
 */
class Posta(private val prefs: SharedPreferences) {

    private class Server(val imap: String, val smtp: String, val portaSmtp: Int)

    private fun indirizzo() = (prefs.getString("posta_indirizzo", "") ?: "").trim()
    private fun password() = (prefs.getString("posta_password", "") ?: "").replace(" ", "")
    fun configurata() = indirizzo().contains("@") && password().isNotEmpty()

    private val AVVISO = "L'email non è collegata. In SISTEMA, COLLEGAMENTI inserisci il tuo indirizzo e una password per le app " +
        "(per Gmail si crea su myaccount.google.com/apppasswords)."

    private fun server(): Server {
        val imap = (prefs.getString("posta_imap", "") ?: "").trim()
        val smtp = (prefs.getString("posta_smtp", "") ?: "").trim()
        if (imap.isNotEmpty() && smtp.isNotEmpty()) {
            return Server(imap, smtp, prefs.getString("posta_porta_smtp", "465")?.toIntOrNull() ?: 465)
        }
        return when (indirizzo().substringAfter('@').lowercase(Locale.ROOT)) {
            "gmail.com", "googlemail.com" -> Server("imap.gmail.com", "smtp.gmail.com", 465)
            "libero.it", "inwind.it", "iol.it", "blu.it" -> Server("imapmail.libero.it", "smtp.libero.it", 465)
            "yahoo.com", "yahoo.it" -> Server("imap.mail.yahoo.com", "smtp.mail.yahoo.com", 465)
            "icloud.com", "me.com", "mac.com" -> Server("imap.mail.me.com", "smtp.mail.me.com", 587)
            "virgilio.it" -> Server("in.virgilio.it", "out.virgilio.it", 465)
            "tim.it" -> Server("imap.tim.it", "smtp.tim.it", 465)
            "alice.it" -> Server("in.alice.it", "out.alice.it", 465)
            "outlook.com", "outlook.it", "hotmail.com", "hotmail.it", "live.com", "live.it", "msn.com" ->
                Server("outlook.office365.com", "smtp-mail.outlook.com", 587)
            else -> {
                val dominio = indirizzo().substringAfter('@')
                Server("imap.$dominio", "smtp.$dominio", 465)
            }
        }
    }

    private fun sessione(): Session {
        val s = server()
        val p = Properties()
        p["mail.store.protocol"] = "imaps"
        p["mail.imaps.host"] = s.imap
        p["mail.imaps.port"] = "993"
        p["mail.imaps.connectiontimeout"] = "10000"
        p["mail.imaps.timeout"] = "20000"
        p["mail.smtp.host"] = s.smtp
        p["mail.smtp.port"] = s.portaSmtp.toString()
        p["mail.smtp.auth"] = "true"
        p["mail.smtp.connectiontimeout"] = "10000"
        p["mail.smtp.timeout"] = "20000"
        if (s.portaSmtp == 465) p["mail.smtp.ssl.enable"] = "true" else p["mail.smtp.starttls.enable"] = "true"
        return Session.getInstance(p)
    }

    private fun spiegaErrore(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("AUTHENTICATE", true) || m.contains("Invalid credentials", true) || m.contains("authentication", true) ->
                "La casella ha rifiutato l'accesso: controlla l'indirizzo e la password per le app."
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) ->
                "Non trovo il server della posta: controlla la connessione o imposta i server a mano."
            else -> "Problema con la posta: $m"
        }
    }

    private fun mittente(m: Message): String {
        val a = m.from?.firstOrNull() as? InternetAddress ?: return "sconosciuto"
        return a.personal?.ifBlank { null } ?: a.address ?: "sconosciuto"
    }

    /** Il testo semplice di un'email (anche se è in HTML o divisa in parti). */
    private fun testo(p: Part, profondita: Int = 0): String {
        if (profondita > 5) return ""
        return try {
            when {
                p.isMimeType("text/plain") -> p.content?.toString() ?: ""
                p.isMimeType("text/html") -> (p.content?.toString() ?: "")
                    .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                p.isMimeType("multipart/*") -> {
                    val mp = p.content as Multipart
                    val parti = (0 until mp.count).map { mp.getBodyPart(it) }
                    val semplice = parti.firstOrNull { it.isMimeType("text/plain") }
                    if (semplice != null) testo(semplice, profondita + 1)
                    else parti.joinToString(" ") { testo(it, profondita + 1) }
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }.replace(Regex("\\s+"), " ").trim()
    }

    fun leggi(quante: Int, soloNonLette: Boolean, cerca: String): String {
        if (!configurata()) return AVVISO
        return try {
            val store = sessione().getStore("imaps")
            store.connect(server().imap, indirizzo(), password())
            val cartella = store.getFolder("INBOX")
            cartella.open(Folder.READ_ONLY)
            val n = quante.coerceIn(1, 15)
            val c = cerca.trim()
            val messaggi: List<Message> = when {
                c.isNotEmpty() -> cartella.search(OrTerm(FromStringTerm(c), SubjectTerm(c))).toList().takeLast(n)
                soloNonLette -> cartella.search(FlagTerm(Flags(Flags.Flag.SEEN), false)).toList().takeLast(n)
                else -> {
                    val totale = cartella.messageCount
                    if (totale == 0) emptyList() else cartella.getMessages(maxOf(1, totale - n + 1), totale).toList()
                }
            }
            val uid = cartella as UIDFolder
            val formato = SimpleDateFormat("EEE d MMM HH:mm", Locale.ITALIAN)
            val righe = messaggi.reversed().map { m ->
                val data = m.receivedDate ?: m.sentDate
                val letta = m.isSet(Flags.Flag.SEEN)
                "[${uid.getUID(m)}] ${if (data != null) formato.format(data) else ""}, da ${mittente(m)}" +
                    (if (!letta) " (non letta)" else "") + ": ${m.subject ?: "(senza oggetto)"} — ${testo(m).take(220)}"
            }
            cartella.close(false)
            store.close()
            if (righe.isEmpty()) {
                if (soloNonLette) "Nessuna email non letta." else if (c.isNotEmpty()) "Nessuna email trovata per \"$c\"." else "La casella è vuota."
            } else righe.joinToString("\n")
        } catch (e: Exception) {
            spiegaErrore(e)
        }
    }

    fun invia(destinatario: String, oggetto: String, testo: String): String {
        if (!configurata()) return AVVISO
        if (testo.isBlank()) return "Il testo dell'email è vuoto."
        return try {
            val s = sessione()
            val m = MimeMessage(s)
            m.setFrom(InternetAddress(indirizzo()))
            m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destinatario))
            m.setSubject(oggetto.ifBlank { "Messaggio" }, "UTF-8")
            m.setText(testo, "UTF-8")
            Transport.send(m, indirizzo(), password())
            "Email inviata a $destinatario."
        } catch (e: Exception) {
            spiegaErrore(e)
        }
    }

    fun rispondi(numero: Long, testo: String): String {
        if (!configurata()) return AVVISO
        if (numero < 0) return "Dimmi a quale email rispondere: prima leggile."
        if (testo.isBlank()) return "La risposta è vuota."
        return try {
            val s = sessione()
            val store = s.getStore("imaps")
            store.connect(server().imap, indirizzo(), password())
            val cartella = store.getFolder("INBOX")
            cartella.open(Folder.READ_WRITE)
            val originale = (cartella as UIDFolder).getMessageByUID(numero)
                ?: return "Non trovo più quell'email.".also { cartella.close(false); store.close() }
            val risposta = originale.reply(false) as MimeMessage
            risposta.setFrom(InternetAddress(indirizzo()))
            risposta.setText(testo, "UTF-8")
            Transport.send(risposta, indirizzo(), password())
            originale.setFlag(Flags.Flag.ANSWERED, true)
            val chi = mittente(originale)
            cartella.close(false)
            store.close()
            "Risposta inviata a $chi."
        } catch (e: Exception) {
            spiegaErrore(e)
        }
    }

    /** Numero di email non lette (per il riepilogo e gli avvisi). */
    fun nonLette(): Int = try {
        if (!configurata()) -1 else {
            val store = sessione().getStore("imaps")
            store.connect(server().imap, indirizzo(), password())
            val cartella = store.getFolder("INBOX")
            val n = cartella.unreadMessageCount
            store.close()
            n
        }
    } catch (e: Exception) {
        -1
    }
}
