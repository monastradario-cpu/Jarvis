package com.example.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Il cuore animato di Jarvis. Colori, forma e stile si scelgono dal menu ASPETTO.
 * - riposo:   rotazione lenta
 * - ascolto:  anelli del colore "ascolto" che pulsano con la voce
 * - pensiero: anelli veloci e particelle in orbita
 * - parla:    il cuore pulsa al ritmo della voce
 *
 * Si può giocare: due dita per lo zoom, trascina per far girare gli anelli, lancia per farli ruotare,
 * tocca per creare onde di luce, doppio tocco per tornare normale, cinque tocchi veloci per la festa.
 */
class ReattoreView(context: Context) : View(context) {

    enum class Stato { RIPOSO, ASCOLTO, PENSIERO, PARLA }

    var stato = Stato.RIPOSO
        set(valore) {
            field = valore
            invalidate()
        }

    var testoCentrale = ""
        set(valore) {
            field = valore
            invalidate()
        }

    /** Animazioni ridotte: più lente, meno fotogrammi e niente particelle (risparmia batteria). */
    var ridotto = false

    /** Colori del tema scelto. */
    var palette: Tema.Palette = Tema.PALETTE[0]
        set(valore) {
            field = valore
            invalidate()
        }

    /** Vedi Tema.FORME. */
    var forma = "sfera"

    /** Vedi Tema.INTERFACCE. */
    var interfaccia = "hud"

    /** Vedi Tema.SFONDI. */
    var sfondo = "nessuno"

    // ---------- regolazioni dell'editor dal vivo ----------
    var scala = 1f              // zoom, da 0.5 a 1.8
    var velocitaBase = 1f       // da 0 a 3
    var numeroParticelle = 40   // da 0 a 160
    var luminosita = 1f         // da 0.3 a 1.6
    var spessore = 1f           // da 0.5 a 2.5
    var tacche = true

    // ---------- Jarvis vivo ----------
    /** calmo, allegro, curioso, assonnato, offeso: cambia ritmo e colori del cuore. */
    var umore = "calmo"
    /** Inclinazione del telefono da -1 a 1: la sfera rotola da quella parte. */
    @Volatile var inclinaX = 0f
    @Volatile var inclinaY = 0f
    /** A faccia in giù: Jarvis dorme. */
    var dorme = false
    private var spostX = 0f
    private var spostY = 0f
    private var agitazione = 0f

    /** Scossa del telefono: la sfera trema e gira. */
    fun scuoti() {
        agitazione = 1.4f
        velocitaGiro += 900f
        invalidate()
    }

    /** Festa di colori (trofei, vittorie). */
    fun festeggia(secondi: Float = 4f) {
        festa = secondi
        velocitaGiro += 540f
        invalidate()
    }

    /** Menu a gesti: "su", "giu", "sinistra", "destra" quando si scorre con decisione sulla sfera. */
    var alGesto: ((String) -> Unit)? = null

    /** Chiamato quando l'utente cambia lo zoom con le dita (per salvarlo). */
    var alCambioZoom: ((Float) -> Unit)? = null

    /** Volume della voce da 0 a 1, aggiornato dal microfono. */
    @Volatile var livelloVoce = 0f

    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pennelloTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.3f
    }
    private val rettangolo = RectF()
    private val percorso = Path()
    private val densita = resources.displayMetrics.density

    private class Particella(var angolo: Float, val raggio: Float, val velocita: Float, val dimensione: Float, val fase: Float)

    private val casuale = Random(11)
    private val particelle = List(160) {
        Particella(
            angolo = casuale.nextFloat() * 360f,
            raggio = 0.5f + casuale.nextFloat() * 0.45f,
            velocita = (10f + casuale.nextFloat() * 25f) * (if (casuale.nextBoolean()) 1f else -1f),
            dimensione = 0.8f + casuale.nextFloat() * 1.4f,
            fase = casuale.nextFloat() * 6.28f
        )
    }
    private val stelle = List(90) { Triple(casuale.nextFloat(), casuale.nextFloat(), casuale.nextFloat()) }

    private class Onda(val x: Float, val y: Float, var eta: Float = 0f)
    private val onde = ArrayList<Onda>()

    private var tempoPrecedente = 0L
    private var rotazione1 = 0f
    private var rotazione2 = 0f
    private var rotazione3 = 0f
    private var giroUtente = 0f          // rotazione data con il dito
    private var velocitaGiro = 0f        // gradi al secondo, rallenta da sola
    private var livelloLisciato = 0f
    private var miscelaAscolto = 0f   // 0 = colore accento, 1 = colore ascolto
    private var energia = 0f          // quanto è "acceso" (pensiero e parlato)
    private var festa = 0f            // secondi di festa rimasti
    private val tocchi = ArrayList<Long>()

    private fun mescola(a: Int, b: Int, f: Float): Int {
        val g = 1f - f
        return Color.rgb(
            (Color.red(a) * g + Color.red(b) * f).toInt(),
            (Color.green(a) * g + Color.green(b) * f).toInt(),
            (Color.blue(a) * g + Color.blue(b) * f).toInt()
        )
    }

    private fun conAlfa(colore: Int, alfa: Int) = Tema.conAlfa(colore, (alfa * luminosita).toInt())

    // ================================================================
    //  GESTI
    // ================================================================

    private fun centroX() = width / 2f
    private fun centroY() = (height - 26f * densita) / 2f

    private val zoom = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(rilevatore: ScaleGestureDetector): Boolean {
            scala = (scala * rilevatore.scaleFactor).coerceIn(0.5f, 1.8f)
            invalidate()
            return true
        }

        override fun onScaleEnd(rilevatore: ScaleGestureDetector) {
            alCambioZoom?.invoke(scala)
        }
    })

    private val gesti = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onde.add(Onda(e.x, e.y))
            if (onde.size > 6) onde.removeAt(0)
            val adesso = System.currentTimeMillis()
            tocchi.add(adesso)
            tocchi.removeAll { adesso - it > 2000 }
            if (tocchi.size >= 5) {
                tocchi.clear()
                festa = 6f
                velocitaGiro += 720f
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            scala = 1f
            velocitaGiro = 0f
            giroUtente = 0f
            alCambioZoom?.invoke(scala)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performLongClick()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1) return false
            // trascinare attorno al centro fa girare gli anelli
            val cx = centroX()
            val cy = centroY()
            val prima = Math.toDegrees(atan2((e2.y + dy - cy).toDouble(), (e2.x + dx - cx).toDouble()))
            val dopo = Math.toDegrees(atan2((e2.y - cy).toDouble(), (e2.x - cx).toDouble()))
            var delta = (dopo - prima).toFloat()
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            giroUtente += delta
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val gesto = alGesto
            if (gesto != null && e1 != null) {
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val lungo = maxOf(abs(dx), abs(dy))
                val corto = minOf(abs(dx), abs(dy))
                if (lungo > 70f * densita && corto < lungo * 0.55f && hypot(vx, vy) > 600f) {
                    gesto(if (abs(dx) > abs(dy)) (if (dx > 0) "destra" else "sinistra") else (if (dy > 0) "giu" else "su"))
                    return true
                }
            }
            val cx = centroX()
            val cy = centroY()
            // componente tangenziale della velocità: decide il verso della rotazione
            val rx = e2.x - cx
            val ry = e2.y - cy
            val distanza = hypot(rx, ry).coerceAtLeast(40f)
            val tangenziale = (rx * vy - ry * vx) / distanza
            velocitaGiro += (tangenziale / distanza * 57f).coerceIn(-1500f, 1500f)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        zoom.onTouchEvent(event)
        if (!zoom.isInProgress) gesti.onTouchEvent(event)
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ================================================================
    //  DISEGNO
    // ================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // ---- tempo e stato ----
        val adesso = System.nanoTime()
        val dt = if (tempoPrecedente == 0L) 0.033f else ((adesso - tempoPrecedente) / 1e9f).coerceAtMost(0.1f)
        tempoPrecedente = adesso
        val t = (adesso % 1_000_000_000_000L) / 1e9f

        val ritmoUmore = when {
            dorme -> 0.15f
            umore == "allegro" -> 1.5f
            umore == "assonnato" -> 0.45f
            umore == "offeso" -> 0.7f
            umore == "curioso" -> 1.15f
            else -> 1f
        }
        val velocita = ritmoUmore * velocitaBase * (if (ridotto) 0.5f else 1f) * when (stato) {
            Stato.RIPOSO -> 1f
            Stato.ASCOLTO -> 1.5f
            Stato.PENSIERO -> 4f
            Stato.PARLA -> 2f
        }
        giroUtente += velocitaGiro * dt
        velocitaGiro *= (1f - min(1f, dt * 1.2f))         // attrito
        if (abs(velocitaGiro) < 1f) velocitaGiro = 0f
        rotazione1 += 6f * velocita * dt
        rotazione2 -= 14f * velocita * dt
        rotazione3 += 28f * velocita * dt
        livelloLisciato += (livelloVoce - livelloLisciato) * min(1f, dt * 12f)
        val obiettivoAscolto = if (stato == Stato.ASCOLTO) 1f else 0f
        miscelaAscolto += (obiettivoAscolto - miscelaAscolto) * min(1f, dt * 5f)
        val obiettivoEnergia = if (stato == Stato.PENSIERO || stato == Stato.PARLA || festa > 0f) 1f else 0f
        energia += (obiettivoEnergia - energia) * min(1f, dt * 4f)
        if (festa > 0f) festa -= dt

        val p = palette
        var anello = mescola(p.accento, p.ascolto, miscelaAscolto)
        var anelloChiaro = mescola(p.accentoChiaro, p.ascolto, miscelaAscolto)
        val spento = when {
            dorme -> 0.65f
            umore == "offeso" -> 0.45f
            umore == "assonnato" -> 0.35f
            else -> 0f
        }
        if (spento > 0f) {
            anello = mescola(anello, p.tenue, spento)
            anelloChiaro = mescola(anelloChiaro, p.tenue, spento * 0.8f)
        }
        if (festa > 0f) {
            // festa: i colori girano su tutto l'arcobaleno
            anello = Color.HSVToColor(floatArrayOf((t * 120f) % 360f, 0.75f, 1f))
            anelloChiaro = Color.HSVToColor(floatArrayOf((t * 120f + 60f) % 360f, 0.35f, 1f))
        }
        val minimal = interfaccia == "minimal"
        val completa = interfaccia == "completa"
        val radar = interfaccia == "radar"
        val olografico = interfaccia == "olografico"

        // spazio per la scritta sotto il cuore
        val spazioTesto = 26f * densita
        // inclinazione (la sfera rotola), curiosità (ondeggia) e scossa (trema)
        val dondolio = if (umore == "curioso" && !dorme) sin(t * 1.3f) * 0.12f else 0f
        spostX += (inclinaX + dondolio - spostX) * min(1f, dt * 4f)
        spostY += (inclinaY - spostY) * min(1f, dt * 4f)
        var tremaX = 0f
        var tremaY = 0f
        if (agitazione > 0f) {
            agitazione -= dt
            tremaX = sin(t * 71f) * 9f * densita * agitazione
            tremaY = cos(t * 53f) * 9f * densita * agitazione
        }
        val r = min(width.toFloat(), height - spazioTesto) / 2f * 0.9f * scala *
            (if (umore == "allegro" && !dorme) 1.04f else if (dorme || umore == "assonnato") 0.94f else 1f)
        val cx = width / 2f + spostX * width * 0.22f + tremaX
        val cy = (height - spazioTesto) / 2f + spostY * height * 0.18f + tremaY
        val sp = spessore * densita

        disegnaSfondo(canvas, cx, cy, r, anello, t)

        pennello.shader = null
        pennello.style = Paint.Style.STROKE
        pennello.strokeCap = Paint.Cap.BUTT

        canvas.save()
        canvas.rotate(giroUtente, cx, cy)

        // ---- 1. anello esterno di tacche (HUD) ----
        if (!minimal && tacche) {
            canvas.save()
            canvas.rotate(rotazione1, cx, cy)
            val numero = if (completa) 180 else 120
            for (i in 0 until numero) {
                val lunga = i % 10 == 0
                val lunghezza = if (lunga) r * 0.06f else r * 0.025f
                pennello.strokeWidth = (if (lunga) 1.6f else 0.9f) * sp
                pennello.color = conAlfa(anello, if (lunga) 200 else 90)
                val a = Math.toRadians(i * 360.0 / numero)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                canvas.drawLine(cx + ca * r, cy + sa * r, cx + ca * (r - lunghezza), cy + sa * (r - lunghezza), pennello)
            }
            canvas.restore()
        }

        // ---- 2. cerchio sottile ----
        pennello.strokeWidth = 0.8f * sp
        pennello.color = conAlfa(anello, if (minimal) 50 else 70)
        canvas.drawCircle(cx, cy, r * 0.9f, pennello)
        if (completa) canvas.drawCircle(cx, cy, r * 0.95f, pennello)

        // ---- 3. archi HUD che ruotano al contrario ----
        if (!minimal) {
            canvas.save()
            canvas.rotate(rotazione2, cx, cy)
            val rArchi = r * 0.82f
            rettangolo.set(cx - rArchi, cy - rArchi, cx + rArchi, cy + rArchi)
            pennello.strokeWidth = 1.6f * sp
            pennello.color = conAlfa(anello, 210)
            canvas.drawArc(rettangolo, -90f, 90f, false, pennello)
            pennello.color = conAlfa(anello, 140)
            canvas.drawArc(rettangolo, 110f, 45f, false, pennello)
            canvas.drawArc(rettangolo, 175f, 25f, false, pennello)
            canvas.drawArc(rettangolo, 40f, 15f, false, pennello)
            if (completa) {
                val rInterno = r * 0.62f
                rettangolo.set(cx - rInterno, cy - rInterno, cx + rInterno, cy + rInterno)
                pennello.strokeWidth = r * 0.05f
                for (i in 0 until 18) {
                    val onda = 0.5f + 0.5f * sin(i * 0.7f + t * 2f * velocita)
                    pennello.color = conAlfa(anello, (40 + 110 * onda).toInt())
                    canvas.drawArc(rettangolo, i * 20f, 14f, false, pennello)
                }
            }
            canvas.restore()
        }

        // ---- 3b. radar: croce, cerchi e raggio che scansiona ----
        if (radar) {
            pennello.strokeWidth = 0.7f * sp
            pennello.color = conAlfa(anello, 60)
            for (k in 1..3) canvas.drawCircle(cx, cy, r * 0.25f * k, pennello)
            canvas.drawLine(cx - r * 0.9f, cy, cx + r * 0.9f, cy, pennello)
            canvas.drawLine(cx, cy - r * 0.9f, cx, cy + r * 0.9f, pennello)
            val angolo = (t * 90f * velocitaBase) % 360f
            pennello.style = Paint.Style.FILL
            rettangolo.set(cx - r * 0.88f, cy - r * 0.88f, cx + r * 0.88f, cy + r * 0.88f)
            for (k in 0 until 12) {
                pennello.color = conAlfa(anelloChiaro, 60 - k * 5)
                canvas.drawArc(rettangolo, angolo - k * 3f, 3f, true, pennello)
            }
            pennello.style = Paint.Style.STROKE
        }

        // ---- 4. arco luminoso veloce (si allarga con la voce) ----
        canvas.save()
        canvas.rotate(rotazione3, cx, cy)
        val rLuce = r * (0.72f + livelloLisciato * 0.06f)
        rettangolo.set(cx - rLuce, cy - rLuce, cx + rLuce, cy + rLuce)
        pennello.strokeWidth = (2.5f + livelloLisciato * 2f) * sp
        pennello.strokeCap = Paint.Cap.ROUND
        pennello.color = conAlfa(anelloChiaro, (150 + 100 * maxOf(energia, miscelaAscolto)).toInt())
        canvas.drawArc(rettangolo, 0f, 40f + 30f * livelloLisciato, false, pennello)
        canvas.drawArc(rettangolo, 180f, 18f, false, pennello)
        canvas.restore()

        // ---- 5. particelle ----
        val forzaParticelle = if (completa || forma == "galassia") maxOf(energia, 0.4f) else energia
        if (forzaParticelle > 0.05f && !ridotto && forma != "galassia") {
            pennello.style = Paint.Style.FILL
            for (part in particelle.take(numeroParticelle.coerceIn(0, particelle.size))) {
                part.angolo += part.velocita * velocita * dt
                val a = Math.toRadians(part.angolo.toDouble())
                val rp = r * part.raggio
                val luce = 0.5f + 0.5f * sin(t * 4f + part.fase)
                pennello.color = conAlfa(anelloChiaro, ((60 + 160 * luce) * forzaParticelle).toInt())
                canvas.drawCircle(cx + cos(a).toFloat() * rp, cy + sin(a).toFloat() * rp, part.dimensione * densita, pennello)
            }
        }

        // ---- 6. il cuore ----
        val respiro = when (stato) {
            Stato.PARLA -> 1f + 0.07f * sin(t * 10f) * sin(t * 3.3f)
            Stato.PENSIERO -> 1f + 0.03f * sin(t * 6f)
            Stato.ASCOLTO -> 1f + livelloLisciato * 0.12f
            Stato.RIPOSO -> 1f + 0.025f * sin(t * 1.4f)
        }
        val rCuore = r * 0.42f * respiro
        when (forma) {
            "anelli" -> disegnaAnelli(canvas, cx, cy, rCuore, anello, anelloChiaro, t)
            "onda" -> disegnaOnda(canvas, cx, cy, rCuore, anello, anelloChiaro, t)
            "atomo" -> disegnaAtomo(canvas, cx, cy, rCuore, anello, anelloChiaro, t, velocita)
            "cristallo" -> disegnaCristallo(canvas, cx, cy, rCuore, anello, anelloChiaro, t, velocita)
            "galassia" -> disegnaGalassia(canvas, cx, cy, rCuore, anelloChiaro, t, velocita, dt)
            "esagono" -> disegnaEsagono(canvas, cx, cy, rCuore, anello, anelloChiaro, t)
            else -> disegnaSfera(canvas, cx, cy, rCuore, anello, anelloChiaro)
        }
        canvas.restore()

        // ---- 6b. olografico: linee di scansione ----
        if (olografico) {
            pennello.style = Paint.Style.FILL
            val passo = 4f * densita
            var y = cy - r
            val tremolio = 0.6f + 0.4f * sin(t * 23f) * sin(t * 7f)
            while (y < cy + r) {
                pennello.color = conAlfa(anello, (14 * tremolio).toInt())
                canvas.drawRect(cx - r, y, cx + r, y + passo / 2f, pennello)
                y += passo
            }
            val scansione = cy - r + ((t * 0.4f) % 1f) * 2f * r
            pennello.color = conAlfa(anelloChiaro, 70)
            canvas.drawRect(cx - r, scansione, cx + r, scansione + 2f * densita, pennello)
        }

        // ---- 7. onde di luce dove tocchi ----
        if (onde.isNotEmpty()) {
            pennello.style = Paint.Style.STROKE
            pennello.strokeWidth = 2f * sp
            val iteratore = onde.iterator()
            while (iteratore.hasNext()) {
                val o = iteratore.next()
                o.eta += dt
                if (o.eta > 1.2f) {
                    iteratore.remove()
                    continue
                }
                val alfa = (220 * (1f - o.eta / 1.2f)).toInt()
                pennello.color = conAlfa(anelloChiaro, alfa)
                canvas.drawCircle(o.x, o.y, o.eta * 180f * densita, pennello)
                pennello.color = conAlfa(anello, alfa / 2)
                canvas.drawCircle(o.x, o.y, o.eta * 110f * densita, pennello)
            }
        }

        // ---- 8. scritta di stato ----
        if (testoCentrale.isNotEmpty() || dorme) {
            pennelloTesto.color = if (stato == Stato.ASCOLTO) p.ascolto else p.accento
            pennelloTesto.textSize = 11f * densita
            val scritta = when {
                festa > 0f -> "★ FESTA ★"
                dorme -> "Z z z  ·  DORMO"
                else -> testoCentrale
            }
            canvas.drawText(scritta, width / 2f, height - 8f * densita, pennelloTesto)
        }

        // Prossimo fotogramma: 30 al secondo (15 con animazioni ridotte) per consumare poco.
        // Si ferma da solo quando l'app non è visibile.
        postInvalidateDelayed(if (ridotto) 66L else 33L)
    }

    private fun disegnaSfondo(canvas: Canvas, cx: Float, cy: Float, r: Float, anello: Int, t: Float) {
        when (sfondo) {
            "griglia" -> {
                pennello.shader = null
                pennello.style = Paint.Style.STROKE
                pennello.strokeWidth = 0.6f * densita
                pennello.color = conAlfa(anello, 22)
                val passo = 28f * densita
                val scorrimento = (t * 6f * densita) % passo
                var x = (cx % passo) - passo
                while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), pennello); x += passo }
                var y = scorrimento - passo
                while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, pennello); y += passo }
            }
            "stelle" -> {
                pennello.shader = null
                pennello.style = Paint.Style.FILL
                for ((sx, sy, luce) in stelle) {
                    val scintilla = 0.4f + 0.6f * abs(sin(t * (0.5f + luce * 2f) + luce * 10f))
                    pennello.color = conAlfa(palette.testo, (40 + 150 * scintilla * luce).toInt())
                    canvas.drawCircle(sx * width, sy * height, (0.6f + luce * 1.2f) * densita, pennello)
                }
            }
            "onde" -> {
                pennello.shader = null
                pennello.style = Paint.Style.STROKE
                pennello.strokeWidth = 0.8f * densita
                val passo = 34f * densita
                val inizio = (t * 8f * densita) % passo
                val massimo = hypot(width.toFloat(), height.toFloat())
                var raggioOnda = inizio
                while (raggioOnda < massimo) {
                    val sbiadito = 1f - raggioOnda / massimo
                    pennello.color = conAlfa(anello, (34 * sbiadito).toInt())
                    canvas.drawCircle(cx, cy, raggioOnda, pennello)
                    raggioOnda += passo
                }
            }
        }
    }

    private fun disegnaSfera(canvas: Canvas, cx: Float, cy: Float, rSfera: Float, accento: Int, chiaro: Int) {
        val p = palette
        pennello.style = Paint.Style.FILL
        pennello.shader = RadialGradient(
            cx, cy, rSfera * 1.25f,
            intArrayOf(conAlfa(accento, (70 + 50 * energia).toInt()), Tema.conAlfa(accento, 0)),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rSfera * 1.25f, pennello)
        pennello.shader = RadialGradient(
            cx - rSfera * 0.15f, cy - rSfera * 0.2f, rSfera,
            intArrayOf(p.testo, chiaro, accento, Tema.conAlfa(accento, 0)),
            floatArrayOf(0f, 0.25f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rSfera, pennello)
        pennello.shader = null
    }

    private fun disegnaAnelli(canvas: Canvas, cx: Float, cy: Float, rCuore: Float, anello: Int, chiaro: Int, t: Float) {
        pennello.shader = null
        pennello.style = Paint.Style.STROKE
        val anelli = 4
        for (i in 0 until anelli) {
            val frazione = 1f - i / anelli.toFloat()
            val pulsazione = 1f + 0.04f * sin(t * (2f + i) + i) * (0.5f + energia + livelloLisciato)
            pennello.strokeWidth = (if (i % 2 == 0) 1.4f else 3f) * densita * spessore
            pennello.color = conAlfa(if (i % 2 == 0) anello else chiaro, (110 + 120 * frazione).toInt())
            canvas.drawCircle(cx, cy, rCuore * frazione * pulsazione, pennello)
        }
        pennello.style = Paint.Style.FILL
        pennello.color = palette.testo
        canvas.drawCircle(cx, cy, rCuore * 0.14f * (1f + 0.3f * energia), pennello)
    }

    private fun disegnaOnda(canvas: Canvas, cx: Float, cy: Float, rCuore: Float, anello: Int, chiaro: Int, t: Float) {
        pennello.shader = null
        val barre = 64
        val base = rCuore * 0.7f
        pennello.style = Paint.Style.STROKE
        pennello.strokeCap = Paint.Cap.ROUND
        pennello.strokeWidth = 2.2f * densita * spessore
        val intensita = 0.25f + 0.75f * maxOf(livelloLisciato, energia * 0.8f)
        for (i in 0 until barre) {
            val a = Math.toRadians(i * 360.0 / barre)
            val onda = abs(sin(t * 3f + i * 0.45f) * cos(t * 1.7f + i * 0.2f))
            val lunghezza = rCuore * (0.12f + 0.55f * onda * intensita)
            pennello.color = conAlfa(if (i % 8 == 0) chiaro else anello, (140 + 100 * onda).toInt())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            canvas.drawLine(cx + ca * base, cy + sa * base, cx + ca * (base + lunghezza), cy + sa * (base + lunghezza), pennello)
        }
        pennello.style = Paint.Style.FILL
        pennello.color = conAlfa(anello, 70)
        canvas.drawCircle(cx, cy, base * 0.85f, pennello)
        pennello.color = palette.testo
        canvas.drawCircle(cx, cy, base * 0.3f, pennello)
    }

    /** Tre orbite ellittiche con un elettrone ciascuna, nucleo luminoso al centro. */
    private fun disegnaAtomo(canvas: Canvas, cx: Float, cy: Float, rCuore: Float, anello: Int, chiaro: Int, t: Float, v: Float) {
        pennello.shader = null
        val raggioX = rCuore * 1.05f
        val raggioY = rCuore * 0.38f
        for (k in 0 until 3) {
            canvas.save()
            canvas.rotate(k * 60f + t * 8f * v, cx, cy)
            pennello.style = Paint.Style.STROKE
            pennello.strokeWidth = 1.3f * densita * spessore
            pennello.color = conAlfa(anello, 170)
            rettangolo.set(cx - raggioX, cy - raggioY, cx + raggioX, cy + raggioY)
            canvas.drawOval(rettangolo, pennello)
            val a = t * (1.6f + k * 0.5f) * v + k * 2f
            pennello.style = Paint.Style.FILL
            pennello.color = conAlfa(chiaro, 255)
            canvas.drawCircle(cx + cos(a) * raggioX, cy + sin(a) * raggioY, (2.6f + energia * 1.5f) * densita, pennello)
            canvas.restore()
        }
        disegnaSfera(canvas, cx, cy, rCuore * 0.38f, anello, chiaro)
    }

    /** Stella a punte che ruota, con un poligono interno che gira al contrario. */
    private fun disegnaCristallo(canvas: Canvas, cx: Float, cy: Float, rCuore: Float, anello: Int, chiaro: Int, t: Float, v: Float) {
        pennello.shader = null
        fun stella(punte: Int, esterno: Float, interno: Float, rotazione: Float) {
            percorso.reset()
            for (i in 0 until punte * 2) {
                val raggio = if (i % 2 == 0) esterno else interno
                val a = Math.toRadians((i * 180.0 / punte) + rotazione)
                val x = cx + cos(a).toFloat() * raggio
                val y = cy + sin(a).toFloat() * raggio
                if (i == 0) percorso.moveTo(x, y) else percorso.lineTo(x, y)
            }
            percorso.close()
        }
        val pulsa = 1f + 0.06f * (energia + livelloLisciato) * sin(t * 8f)
        stella(6, rCuore * 1.0f * pulsa, rCuore * 0.5f, t * 20f * v)
        pennello.style = Paint.Style.FILL
        pennello.color = conAlfa(anello, 60)
        canvas.drawPath(percorso, pennello)
        pennello.style = Paint.Style.STROKE
        pennello.strokeWidth = 1.6f * densita * spessore
        pennello.color = conAlfa(anello, 220)
        canvas.drawPath(percorso, pennello)
        stella(4, rCuore * 0.55f, rCuore * 0.25f, -t * 45f * v)
        pennello.color = conAlfa(chiaro, 240)
        canvas.drawPath(percorso, pennello)
        pennello.style = Paint.Style.FILL
        pennello.color = palette.testo
        canvas.drawCircle(cx, cy, rCuore * 0.1f * (1f + energia * 0.4f), pennello)
    }

    /** Spirale di particelle: con la voce i bracci si allargano. */
    private fun disegnaGalassia(canvas: Canvas, cx: Float, cy: Float, rCuore: Float, chiaro: Int, t: Float, v: Float, dt: Float) {
        pennello.shader = null
        pennello.style = Paint.Style.FILL
        val quante = numeroParticelle.coerceIn(30, particelle.size)
        val apertura = 1f + 0.4f * maxOf(livelloLisciato, energia * 0.6f)
        for ((i, part) in particelle.take(quante).withIndex()) {
            part.angolo += abs(part.velocita) * 0.6f * v * dt
            val braccio = (i % 3) * 120f
            val distanza = part.raggio * rCuore * 1.9f * apertura
            val a = Math.toRadians((part.angolo * 0.2f + braccio + distanza / rCuore * 140f).toDouble())
            val luce = 0.5f + 0.5f * sin(t * 3f + part.fase)
            pennello.color = conAlfa(chiaro, (70 + 170 * luce).toInt())
            canvas.drawCircle(cx + cos(a).toFloat() * distanza, cy + sin(a).toFloat() * distanza * 0.85f, part.dimensione * densita, pennello)
        }
        disegnaSfera(canvas, cx, cy, rCuore * 0.35f, palette.accento, chiaro)
    }

    /** Esagono tecnologico: anelli esagonali concentrici che si accendono a turno. */
    private fun disegnaEsagono(canvas: Canvas, cx: Float, cy: Float, rCuore: Float, anello: Int, chiaro: Int, t: Float) {
        pennello.shader = null
        for (k in 0 until 4) {
            val raggio = rCuore * (1f - k * 0.22f)
            percorso.reset()
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0 + 30.0 + k * 15.0)
                val x = cx + cos(a).toFloat() * raggio
                val y = cy + sin(a).toFloat() * raggio
                if (i == 0) percorso.moveTo(x, y) else percorso.lineTo(x, y)
            }
            percorso.close()
            val acceso = 0.5f + 0.5f * sin(t * 3f - k * 1.2f) * (0.4f + energia + livelloLisciato)
            pennello.style = Paint.Style.STROKE
            pennello.strokeWidth = (if (k == 0) 2.4f else 1.3f) * densita * spessore
            pennello.color = conAlfa(if (k % 2 == 0) anello else chiaro, (90 + 160 * acceso).toInt())
            canvas.drawPath(percorso, pennello)
        }
        pennello.style = Paint.Style.FILL
        pennello.color = conAlfa(chiaro, 230)
        canvas.drawCircle(cx, cy, rCuore * 0.16f * (1f + 0.3f * energia), pennello)
    }
}
