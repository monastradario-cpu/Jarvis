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
    private val alScegliere: (Int) -> Unit,
    private val alChiudere: () -> Unit
) : View(context) {

    private val oro = Color.parseColor("#D4A84B")
    private val bianco = Color.parseColor("#F5F5F5")
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
    private fun raggioVoce() = 31f * densita

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
        canvas.drawColor(Color.argb((215 * apertura).toInt(), 5, 5, 5))

        // Cerchio guida su cui stanno le voci
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 0.8f * densita
        pennello.color = conAlfa(oro, (90 * apertura).toInt())
        canvas.drawCircle(cx(), cy(), raggioMenu() * apertura, pennello)

        val rv = raggioVoce() * (0.6f + 0.4f * apertura)
        pennelloTesto.textSize = 9.5f * densita
        for (i in voci.indices) {
            val (x, y) = posizione(i)
            // linea sottile dal centro
            pennello.style = Paint.Style.STROKE
            pennello.strokeWidth = 0.6f * densita
            pennello.color = conAlfa(oro, (60 * apertura).toInt())
            canvas.drawLine(cx(), cy(), x, y, pennello)
            // bottone
            pennello.style = Paint.Style.FILL
            pennello.color = if (i == premuta) conAlfa(oro, 230) else conAlfa(Color.parseColor("#0D0D0D"), (255 * apertura).toInt())
            canvas.drawCircle(x, y, rv, pennello)
            pennello.style = Paint.Style.STROKE
            pennello.strokeWidth = 1.3f * densita
            pennello.color = conAlfa(oro, (230 * apertura).toInt())
            canvas.drawCircle(x, y, rv, pennello)
            // etichetta
            pennelloTesto.color = conAlfa(if (i == premuta) Color.parseColor("#050505") else bianco, (255 * apertura).toInt())
            val altezza = pennelloTesto.descent() + pennelloTesto.ascent()
            canvas.drawText(voci[i], x, y - altezza / 2f, pennelloTesto)
        }

        // Centro: chiudi
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 1.3f * densita
        pennello.color = conAlfa(oro, (200 * apertura).toInt())
        canvas.drawCircle(cx(), cy(), 22f * densita, pennello)
        pennelloTesto.color = conAlfa(oro, (255 * apertura).toInt())
        pennelloTesto.textSize = 8.5f * densita
        val altezza = pennelloTesto.descent() + pennelloTesto.ascent()
        canvas.drawText("CHIUDI", cx(), cy() - altezza / 2f, pennelloTesto)
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
