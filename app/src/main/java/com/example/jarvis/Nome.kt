package com.example.jarvis

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Il nome dell'assistente: ognuno sceglie il suo (impostazioni › STILE › NOME).
 * È anche la parola di attivazione ("Ehi <nome>"). Sotto l'icona del telefono resta il nome
 * di famiglia dell'app, [PREDEFINITO].
 */
object Nome {

    const val PREDEFINITO = "Atlas"

    /** Il nome attuale, sempre pronto per i testi (si aggiorna quando lo cambi). */
    @Volatile var attuale: String = PREDEFINITO
        private set

    fun leggi(p: SharedPreferences): String = normalizza(p.getString("nome_assistente", PREDEFINITO) ?: PREDEFINITO)

    fun carica(ctx: Context) {
        attuale = leggi(ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE))
    }

    /** Una sola parola, solo lettere, con l'iniziale maiuscola. Vuoto = nome predefinito. */
    fun normalizza(nome: String): String {
        val parola = nome.trim().split(Regex("\\s+")).firstOrNull().orEmpty().replace(Regex("[^\\p{L}]"), "")
        if (parola.isEmpty()) return PREDEFINITO
        return parola.lowercase(Locale.ITALIAN).replaceFirstChar { it.titlecase(Locale.ITALIAN) }.take(20)
    }

    /** Salva il nuovo nome e lo restituisce, già sistemato. */
    fun imposta(ctx: Context, nome: String): String {
        val n = normalizza(nome)
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("nome_assistente", n).commit()
        attuale = n
        return n
    }
}
