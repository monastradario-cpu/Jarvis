package com.example.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Menu radiale: le voci si aprono a cerchio attorno alla sfera.
 * Toccando una voce si apre il suo sottomenù; toccando il centro o fuori si chiude.
 */
class MenuRadialeView(
    context: Context,
    private val voci: List<String>,
    private val palette: Tema.Palette,
    private val alScegliere: (Int) -> Unit,
    private val alChiudere: () -> Unit,
    /** Una riga di spiegazione per ogni voce: compare al centro mentre il dito ci passa sopra. */
    private val descrizioni: List<String> = emptyList()
) : View(context) {

    private val oro = palette.accento
    private val bianco = palette.testo
    private val densita = resources.displayMetrics.density

    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pennelloTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }

    private var apertura = 0f
    private var premuta = -1

    init {
        isClickable = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                apertura = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // Stesso centro della sfera (vedi ReattoreView: spazio per la scritta in basso)
    private fun cx() = width / 2f
    private fun cy() = (height - 26f * densita) / 2f
    private fun raggioMenu() = min(width.toFloat(), height - 26f * densita) / 2f * 0.74f
    private fun raggioVoce() = (if (voci.size > 10) 29f else 31f) * densita

    private fun posizione(i: Int): Pair<Float, Float> {
        val angolo = Math.toRadians(-90.0 + i * 360.0 / voci.size)
        val r = raggioMenu() * apertura
        return Pair(cx() + cos(angolo).toFloat() * r, cy() + sin(angolo).toFloat() * r)
    }

    private fun conAlfa(colore: Int, alfa: Int) =
        Color.argb(alfa.coerceIn(0, 255), Color.red(colore), Color.green(colore), Color.blue(colore))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Velo scuro sopra la sfera
        canvas.drawColor(conAlfa(palette.sfondo, (215 * apertura).toInt()))

        // Cerchio guida su cui stanno le voci
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 0.8f * densita
        pennello.color = conAlfa(oro, (90 * apertura).toInt())
        canvas.drawCircle(cx(), cy(), raggioMenu() * apertura, pennello)

        val rv = raggioVoce() * (0.6f + 0.4f * apertura)
        for (i in voci.indices) {
            pennelloTesto.textSize = (if (voci[i].length > 7) 8f else 9.5f) * densita
            val (x, y) = posizione(i)
            // linea sottile dal centro
            pennello.style = Paint.Style.STROKE
            pennello.strokeWidth = 0.6f * densita
            pennello.color = conAlfa(oro, (60 * apertura).toInt())
            canvas.drawLine(cx(), cy(), x, y, pennello)
            // bottone
            pennello.style = Paint.Style.FILL
            pennello.color = if (i == premuta) conAlfa(oro, 230) else conAlfa(palette.superficie, (255 * apertura).toInt())
            canvas.drawCircle(x, y, rv, pennello)
            pennello.style = Paint.Style.STROKE
            pennello.strokeWidth = 1.3f * densita
            pennello.color = conAlfa(oro, (230 * apertura).toInt())
            canvas.drawCircle(x, y, rv, pennello)
            // etichetta
            pennelloTesto.color = conAlfa(if (i == premuta) palette.sfondo else bianco, (255 * apertura).toInt())
            val altezza = pennelloTesto.descent() + pennelloTesto.ascent()
            canvas.drawText(voci[i], x, y - altezza / 2f, pennelloTesto)
        }

        // Centro: nome e spiegazione della voce sotto il dito, altrimenti "chiudi"
        val rCentro = (raggioMenu() * apertura - rv * 1.15f).coerceAtLeast(22f * densita)
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 1.3f * densita
        pennello.color = conAlfa(oro, (200 * apertura).toInt())
        canvas.drawCircle(cx(), cy(), if (premuta >= 0) rCentro * 0.92f else 22f * densita, pennello)
        pennelloTesto.color = conAlfa(oro, (255 * apertura).toInt())
        if (premuta >= 0) {
            pennelloTesto.textSize = 12f * densita
            canvas.drawText(voci[premuta], cx(), cy() - 6f * densita, pennelloTesto)
            val spiegazione = descrizioni.getOrNull(premuta).orEmpty()
            if (spiegazione.isNotEmpty()) {
                pennelloTesto.textSize = 9f * densita
                pennelloTesto.color = conAlfa(bianco, (220 * apertura).toInt())
                val larghezza = rCentro * 1.6f
                val righe = ArrayList<String>()
                var riga = ""
                for (parola in spiegazione.split(" ")) {
                    val prova = if (riga.isEmpty()) parola else "$riga $parola"
                    if (pennelloTesto.measureText(prova) > larghezza && riga.isNotEmpty()) { righe.add(riga); riga = parola } else riga = prova
                }
                if (riga.isNotEmpty()) righe.add(riga)
                righe.take(3).forEachIndexed { k, r -> canvas.drawText(r, cx(), cy() + (12f + k * 12f) * densita, pennelloTesto) }
            }
        } else {
            pennelloTesto.textSize = 8.5f * densita
            val altezza = pennelloTesto.descent() + pennelloTesto.ascent()
            canvas.drawText("CHIUDI", cx(), cy() - altezza / 2f, pennelloTesto)
        }
    }

    private fun vocePremuta(x: Float, y: Float): Int {
        for (i in voci.indices) {
            val (vx, vy) = posizione(i)
            if (hypot(x - vx, y - vy) <= raggioVoce() * 1.25f) return i
        }
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val nuova = vocePremuta(event.x, event.y)
                if (nuova != premuta) {
                    premuta = nuova
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val scelta = vocePremuta(event.x, event.y)
                premuta = -1
                performClick()
                if (scelta >= 0) alScegliere(scelta) else alChiudere()
            }
            MotionEvent.ACTION_CANCEL -> {
                premuta = -1
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
