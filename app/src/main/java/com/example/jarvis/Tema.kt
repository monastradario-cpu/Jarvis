package com.example.jarvis

import android.content.SharedPreferences
import android.graphics.Color

/** Colori e stile dell'interfaccia di Jarvis, scelti dal menu ASPETTO. */
object Tema {

    class Palette(
        val id: String,
        val nome: String,
        sfondo: String,
        accento: String,
        accentoChiaro: String,
        ascolto: String,
        testo: String,
        tenue: String,
        superficie: String
    ) {
        val sfondo = Color.parseColor(sfondo)               // fondo dello schermo
        val accento = Color.parseColor(accento)             // anelli, titoli, pulsanti
        val accentoChiaro = Color.parseColor(accentoChiaro) // luce della sfera
        val ascolto = Color.parseColor(ascolto)             // colore degli anelli mentre ascolta
        val testo = Color.parseColor(testo)                 // risposte di Jarvis
        val tenue = Color.parseColor(tenue)                 // testi secondari
        val superficie = Color.parseColor(superficie)       // campo di testo e pulsanti del menu
    }

    val PALETTE = listOf(
        Palette("oro", "Nero, oro e bianco", "#050505", "#D4A84B", "#F0D58C", "#F5F5F5", "#F5F5F5", "#8A8A8A", "#0D0D0D"),
        Palette("ghiaccio", "Azzurro ghiaccio", "#050B12", "#3FC1FF", "#CDEFFF", "#FFFFFF", "#E6F1FF", "#7A8BA6", "#0E1A26"),
        Palette("rosso", "Rosso e oro", "#0B0505", "#E5484D", "#F2B544", "#F2B544", "#FFE6C7", "#9A8A80", "#1A0A0A"),
        Palette("argento", "Bianco e argento", "#0E0F11", "#C9CED6", "#FFFFFF", "#7FD3FF", "#F5F5F5", "#8A939D", "#1C1E22"),
        Palette("smeraldo", "Verde smeraldo", "#04100B", "#34D399", "#CFFBE6", "#F5F5F5", "#ECFDF5", "#7FA393", "#0C2219"),
        Palette("viola", "Viola neon", "#0B0614", "#B794F6", "#EBDDFF", "#22D3EE", "#F3EEFF", "#8C82A6", "#1A1030"),
        Palette("blu", "Blu notte e oro", "#060A18", "#F4C66B", "#FFF1CC", "#8FB0FF", "#F2F4FA", "#8A93AD", "#111A36"),
        Palette("arancio", "Oro e arancio", "#0A0704", "#FF8A1F", "#FFE2B0", "#FFB547", "#FFF3E0", "#9A8A78", "#1C130B")
    )

    /** Stili dell'interfaccia. */
    val INTERFACCE = listOf(
        "hud" to "HUD: anelli tecnici, riga di dati in alto",
        "minimal" to "Minimal: solo la sfera, niente dati",
        "completa" to "HUD completo: più anelli e particelle sempre visibili",
        "radar" to "Radar: griglia e raggio che scansiona",
        "olografico" to "Olografico: linee di scansione e luce tremolante"
    )

    /** Forme del cuore animato. */
    val FORME = listOf(
        "nucleo3d" to "Nucleo 3D: sfera di energia con profondità, riflessi e anello in orbita",
        "sfera" to "Sfera luminosa",
        "anelli" to "Nucleo ad anelli",
        "onda" to "Onda vocale: barre che danzano con la voce",
        "atomo" to "Atomo: elettroni in orbita",
        "cristallo" to "Cristallo: stella che ruota",
        "galassia" to "Galassia: spirale di particelle",
        "esagono" to "Esagono: nucleo tecnologico"
    )

    /** Sfondi dietro al cuore. */
    val SFONDI = listOf(
        "nessuno" to "Tinta unita",
        "griglia" to "Griglia HUD",
        "stelle" to "Cielo stellato",
        "onde" to "Onde: cerchi che si allargano piano",
        "foto" to "La tua foto (si sceglie in PERSONALIZZA)"
    )

    fun attuale(prefs: SharedPreferences): Palette {
        val base = PALETTE.firstOrNull { it.id == prefs.getString("tema", "oro") } ?: PALETTE[0]
        val tonalita = prefs.getInt("r_tonalita", -1)
        return if (tonalita in 0..360) conTonalita(base, tonalita.toFloat()) else base
    }

    private fun esadecimale(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    /** La stessa palette con il colore principale spostato su un'altra tonalità (editor dal vivo). */
    fun conTonalita(base: Palette, tonalita: Float): Palette {
        val hsv = FloatArray(3)
        Color.colorToHSV(base.accento, hsv)
        val accento = Color.HSVToColor(floatArrayOf(tonalita, hsv[1].coerceAtLeast(0.45f), hsv[2].coerceAtLeast(0.8f)))
        val chiaro = Color.HSVToColor(floatArrayOf(tonalita, 0.3f, 1f))
        return Palette(
            base.id, base.nome, esadecimale(base.sfondo), esadecimale(accento), esadecimale(chiaro),
            esadecimale(base.ascolto), esadecimale(base.testo), esadecimale(base.tenue), esadecimale(base.superficie)
        )
    }

    fun conAlfa(colore: Int, alfa: Int) =
        Color.argb(alfa.coerceIn(0, 255), Color.red(colore), Color.green(colore), Color.blue(colore))
}
