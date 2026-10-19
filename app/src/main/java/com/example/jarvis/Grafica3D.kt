package com.example.jarvis

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * I modelli 3D del cuore, disegnati dalla scheda video del telefono (Android 13 e successivi).
 * Ogni punto è calcolato come su un oggetto vero: luce, riflessi, profondità.
 * Tutti reagiscono alla voce (voce), al lavoro (energia), all'umore (spento) e al colore del tema.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class Grafica3D(modello: String) {

    companion object {
        /** id, nome, descrizione */
        val MODELLI = listOf(
            Triple("olografica", "Sfera olografica", "Gusci di filamenti luminosi come circuiti, che ruotano attorno al nucleo"),
            Triple("nucleo", "Nucleo", "Sfera di energia con anello in orbita"),
            Triple("sole", "Sole", "Stella di plasma con la corona che pulsa con la voce"),
            Triple("pianeta", "Pianeta", "Pianeta con continenti, nuvole e atmosfera"),
            Triple("ologramma", "Ologramma", "Globo a reticolo trasparente con scansione"),
            Triple("buconero", "Buco nero", "Disco di luce che gira attorno al buio")
        )

        fun forma(modello: String) = "3d_$modello"

        /** Da una forma di Tema.FORME al modello 3D (null se non è 3D). */
        fun modelloDaForma(forma: String): String? = when {
            forma == "nucleo3d" -> "nucleo"
            forma.startsWith("3d_") -> forma.removePrefix("3d_").let { m -> if (MODELLI.any { it.first == m }) m else "olografica" }
            else -> null
        }

        /** Disponibile su questo telefono? (Android 13+ e programma grafico compilato senza errori) */
        val disponibile: Boolean by lazy {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && try {
                RuntimeShader(sorgente("olografica")); true
            } catch (e: Throwable) {
                false
            }
        }

        fun sorgente(modello: String): String = TESTA + when (modello) {
            "sole" -> SOLE
            "pianeta" -> PIANETA
            "ologramma" -> OLOGRAMMA
            "buconero" -> BUCO_NERO
            "nucleo" -> NUCLEO
            else -> OLOGRAFICA
        }

        private const val TESTA = """
uniform float2 centro;
uniform float raggio;
uniform float tempo;
uniform float energia;
uniform float voce;
uniform float spento;
uniform float alone;
layout(color) uniform half4 cAccento;
layout(color) uniform half4 cChiaro;

float caso(float3 p) {
    p = fract(p * 0.3183099 + float3(0.11, 0.17, 0.13));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float rumore(float3 x) {
    float3 i = floor(x);
    float3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(caso(i), caso(i + float3(1.0, 0.0, 0.0)), f.x);
    float b = mix(caso(i + float3(0.0, 1.0, 0.0)), caso(i + float3(1.0, 1.0, 0.0)), f.x);
    float c = mix(caso(i + float3(0.0, 0.0, 1.0)), caso(i + float3(1.0, 0.0, 1.0)), f.x);
    float d = mix(caso(i + float3(0.0, 1.0, 1.0)), caso(i + float3(1.0, 1.0, 1.0)), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

float strati(float3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int k = 0; k < 4; k++) {
        v += a * rumore(p);
        p = p * 2.03 + float3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return v;
}

float3 giraY(float3 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
}

float3 giraX(float3 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float3(p.x, c * p.y - s * p.z, s * p.y + c * p.z);
}

float2 gira2(float2 p, float a) {
    return float2(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a));
}

float4 sopra(float4 su, float4 giu) {
    return su + giu * (1.0 - su.a);
}

float3 acc() { return float3(cAccento.rgb); }
float3 chi() { return float3(cChiaro.rgb); }
float forza() { return 0.55 + 0.45 * energia + 0.6 * voce; }

float4 bagliore(float d, float3 col, float quanto) {
    float a = exp(-max(d - 1.0, 0.0) * (3.6 - 1.6 * voce)) * quanto * alone;
    a *= 1.0 - smoothstep(1.9, 2.2, d);
    a = clamp(a, 0.0, 1.0);
    return float4(col * a, a);
}

half4 finale(float4 c) {
    float g = dot(c.rgb, float3(0.3, 0.59, 0.11));
    c.rgb = mix(c.rgb, float3(g) * 0.7, spento);
    return half4(c);
}
"""

        private const val OLOGRAFICA = """
float linee(float3 n, float celle, float spessore, float semi) {
    float lat = asin(clamp(n.y, -1.0, 1.0));
    float lon = atan(n.z, n.x);
    float2 p = float2(lon / 6.2831853 * celle * 2.0, lat / 3.1415926 * celle);
    float2 c = floor(p);
    float2 f = fract(p);
    float r = caso(float3(c, semi));
    float dd = r < 0.5
        ? min(abs(length(f) - 0.5), abs(length(f - float2(1.0, 1.0)) - 0.5))
        : min(abs(length(f - float2(1.0, 0.0)) - 0.5), abs(length(f - float2(0.0, 1.0)) - 0.5));
    float linea = 1.0 - smoothstep(spessore * 0.5, spessore, dd);
    float pieno = step(0.3, caso(float3(c * 1.7, semi + 3.0)));
    return linea * pieno;
}

float3 guscio(float2 uv, float R, float celle, float semi, float giro, float incl, float banda, float3 col) {
    float d = length(uv);
    if (d >= R) return float3(0.0);
    float z = sqrt(R * R - d * d);
    float3 tot = float3(0.0);
    for (int k = 0; k < 2; k++) {
        float zz = k == 0 ? z : -z;
        float3 n = float3(uv.x, -uv.y, zz) / R;
        float3 q = giraY(giraX(n, incl), giro);
        float l = linee(q, celle, 0.17, semi);
        float frammenti = smoothstep(0.35, 0.6, rumore(q * 3.0 + float3(semi, tempo * 0.15, 0.0)));
        float fasce = banda > 0.0 ? smoothstep(banda, banda + 0.15, abs(q.y)) : 1.0;
        float bordo = 0.6 + 0.4 * pow(1.0 - abs(zz) / R, 2.0);
        float peso = k == 0 ? 1.0 : 0.35;
        tot += col * (l * frammenti * fasce * bordo * peso);
    }
    return tot;
}

half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza();
    float3 tinta = mix(a, c2, 0.55);
    float3 luce = float3(0.0);
    luce += guscio(uv, 1.25, 22.0, 1.0, tempo * 0.12, 0.25, 0.55, tinta) * 0.9;
    luce += guscio(uv, 1.0, 18.0, 2.0, -tempo * 0.2, 0.5, 0.0, tinta);
    luce += guscio(uv, 0.72, 16.0, 3.0, tempo * 0.32, -0.3, 0.0, tinta) * 0.9;
    luce += guscio(uv, 0.45, 14.0, 4.0, -tempo * 0.5, 0.8, 0.0, c2) * (0.8 + 0.5 * f);
    float2 r2 = gira2(uv, -0.08);
    float2 e = float2(r2.x, r2.y / 0.12);
    float an = (1.0 - smoothstep(0.0, 0.05, abs(length(e) - 1.12))) * (0.6 + 0.4 * f);
    luce += c2 * an;
    float2 dir = normalize(float2(0.45, 0.85));
    float t2 = clamp(dot(uv, dir), -0.75, 0.75);
    float scia = (1.0 - smoothstep(0.0, 0.018, length(uv - dir * t2))) * (0.5 + 0.5 * sin(tempo * 1.5));
    luce += c2 * scia * (0.6 + 0.6 * f);
    luce += c2 * exp(-d * 7.0) * (0.25 + 0.9 * voce + 0.3 * energia);
    luce *= 0.85 + 0.3 * f;
    float4 alo = bagliore(d / 1.25, a, 0.12 + 0.18 * f);
    float al = clamp(max(luce.r, max(luce.g, luce.b)), 0.0, 1.0);
    float4 colore = sopra(float4(min(luce, float3(al)), al), alo);
    return finale(colore);
}
"""

        private const val NUCLEO = """
half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza();
    float4 colore = bagliore(d, a, 0.28 + 0.35 * f) * step(1.0, d);
    float2 r2 = gira2(uv, -0.35);
    float2 e = float2(r2.x, r2.y / 0.3);
    float le = length(e);
    float scie = pow(0.5 + 0.5 * sin(atan(e.y, e.x) * 3.0 - tempo * 2.0 * (0.6 + energia)), 6.0);
    float an = (1.0 - smoothstep(0.0, 0.07, abs(le - 1.5))) * (0.3 + 0.7 * scie) * (0.6 + 0.4 * f);
    float4 ca = float4(c2 * an, an);
    bool davanti = r2.y > 0.0;
    if (!davanti) colore = sopra(ca, colore);
    if (d < 1.0) {
        float z = sqrt(1.0 - d * d);
        float3 n = float3(uv.x, -uv.y, z);
        float3 q = giraY(n, tempo * 0.35);
        float plasma = strati(q * 2.4 + float3(0.0, tempo * 0.25, 0.0));
        float vene = 1.0 - abs(strati(q * 4.0 - float3(tempo * 0.4)) * 2.0 - 1.0);
        vene = pow(vene, 6.0) * f;
        float centrale = z * z;
        float3 base = mix(a * 0.16, a, plasma * centrale);
        base += c2 * vene * 0.9;
        float3 luce = normalize(float3(-0.4, 0.5, 0.8));
        float diffusa = max(dot(n, luce), 0.0);
        float riflesso = pow(max(dot(reflect(-luce, n), float3(0.0, 0.0, 1.0)), 0.0), 24.0);
        float bordo = pow(1.0 - z, 2.5);
        float3 col = base * (0.35 + 0.75 * diffusa) + c2 * riflesso * 0.8 + a * bordo * 1.2;
        col += c2 * pow(centrale, 6.0) * 0.35 * f;
        float al = 1.0 - smoothstep(0.985, 1.0, d);
        colore = sopra(float4(col * al, al), colore);
    }
    if (davanti) colore = sopra(ca, colore);
    return finale(colore);
}
"""

        private const val SOLE = """
half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza() + 0.15;
    float ang = atan(uv.y, uv.x);
    float raggi = strati(float3(cos(ang) * 2.0, sin(ang) * 2.0, tempo * 0.6 - d * 1.5));
    float corona = exp(-max(d - 1.0, 0.0) * (2.5 - 1.2 * voce)) * (0.4 + 0.9 * raggi) * f * alone;
    corona = clamp(corona * (1.0 - smoothstep(1.9, 2.2, d)) * step(1.0, d), 0.0, 1.0);
    float4 colore = float4(mix(a, c2, raggi) * corona, corona);
    if (d < 1.0) {
        float z = sqrt(1.0 - d * d);
        float3 n = float3(uv.x, -uv.y, z);
        float3 q = giraY(n, tempo * 0.2);
        float granuli = strati(q * 5.0 + float3(0.0, 0.0, tempo * 0.5));
        float macchie = smoothstep(0.55, 0.75, strati(q * 2.0 - float3(tempo * 0.1)));
        float3 col = mix(a * 1.1, c2 * 1.2, granuli);
        col = mix(col, a * 0.35, macchie * 0.6);
        col *= 0.55 + 0.6 * pow(z, 0.6);
        col += c2 * 0.4 * f * pow(z, 3.0);
        float al = 1.0 - smoothstep(0.985, 1.0, d);
        colore = sopra(float4(col * al, al), colore);
    }
    return finale(colore);
}
"""

        private const val PIANETA = """
half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza();
    float4 colore = bagliore(d, a * 0.8 + c2 * 0.2, 0.22 + 0.25 * f) * step(1.0, d);
    if (d < 1.0) {
        float z = sqrt(1.0 - d * d);
        float3 n = float3(uv.x, -uv.y, z);
        float3 q = giraY(n, tempo * 0.18);
        float h = strati(q * 2.2 + float3(3.0, 1.0, 2.0));
        float terra = smoothstep(0.47, 0.53, h);
        float3 mare = a * 0.22 + c2 * 0.05;
        float3 suolo = mix(a * 0.75, c2, smoothstep(0.55, 0.8, h));
        float3 col = mix(mare, suolo, terra);
        float nuvole = smoothstep(0.55, 0.85, strati(q * 3.5 + float3(tempo * 0.06, 0.0, 0.0)));
        col = mix(col, c2 * 1.1, nuvole * 0.6);
        float3 luce = normalize(float3(-0.6, 0.35, 0.7));
        float diffusa = max(dot(n, luce), 0.0);
        col *= 0.12 + 0.95 * diffusa;
        float riflesso = pow(max(dot(reflect(-luce, n), float3(0.0, 0.0, 1.0)), 0.0), 30.0) * (1.0 - terra);
        col += c2 * riflesso * 0.6;
        col += a * pow(1.0 - z, 3.0) * (0.9 + 0.6 * f);
        float al = 1.0 - smoothstep(0.985, 1.0, d);
        colore = sopra(float4(col * al, al), colore);
    }
    return finale(colore);
}
"""


        private const val OLOGRAMMA = """
float griglia(float3 n, float spessore) {
    float3 q = giraY(giraX(n, 0.45), tempo * 0.5);
    float lat = asin(clamp(q.y, -1.0, 1.0));
    float lon = atan(q.z, q.x);
    float gl = abs(fract(lat / 0.2617994 + 0.5) - 0.5);
    float gm = abs(fract(lon / 0.3926991 + 0.5) - 0.5);
    float linee = max(1.0 - smoothstep(0.0, spessore, gl), 1.0 - smoothstep(0.0, spessore, gm));
    float onda = exp(-abs(lat - sin(tempo * 1.3) * 1.2) * 8.0);
    return clamp(linee + onda * 0.6, 0.0, 1.5);
}

half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza();
    float4 colore = bagliore(d, a, 0.18 + 0.3 * f) * step(1.0, d);
    if (d < 1.0) {
        float z = sqrt(1.0 - d * d);
        float sp = 0.06 + 0.04 * (1.0 - z);
        float dietro = griglia(float3(uv.x, -uv.y, -z), sp) * 0.35;
        float avanti = griglia(float3(uv.x, -uv.y, z), sp);
        float riempi = 0.08 + 0.12 * pow(1.0 - z, 2.0) * (1.0 + f);
        float scansione = 0.75 + 0.25 * sin(punto.y * 0.9 + tempo * 12.0);
        float tremolio = 0.85 + 0.15 * sin(tempo * 23.0) * sin(tempo * 7.0);
        float luce = (dietro + avanti + riempi) * scansione * tremolio * (0.7 + 0.5 * f);
        float al = clamp(luce, 0.0, 1.0) * (1.0 - smoothstep(0.985, 1.0, d));
        float3 col = mix(a, c2, clamp(avanti, 0.0, 1.0));
        colore = sopra(float4(col * al, al), colore);
    }
    float anello = (1.0 - smoothstep(0.0, 0.025, abs(d - 1.12))) * (0.4 + 0.6 * f);
    anello = clamp(anello, 0.0, 1.0);
    colore = sopra(float4(c2 * anello, anello), colore);
    return finale(colore);
}
"""



        private const val BUCO_NERO = """
half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza();
    float4 colore = bagliore(d / 0.6, a, 0.15 + 0.2 * f) * step(0.6, d);
    float2 r2 = gira2(uv, -0.25);
    float2 e = float2(r2.x, r2.y / 0.28);
    float le = length(e);
    float th = atan(e.y, e.x);
    float vortice = strati(float3(le * 3.0, th * 1.5 - tempo * 1.8 / max(le, 0.4), tempo * 0.2));
    float disco = smoothstep(0.62, 0.72, le) * (1.0 - smoothstep(1.5, 1.95, le));
    float doppler = 0.6 + 0.6 * cos(th + 0.3);
    float intens = clamp(disco * (0.35 + 0.9 * vortice) * doppler * (0.7 + 0.5 * f), 0.0, 1.0);
    float4 cDisco = float4(mix(a, c2, vortice) * intens, intens);
    bool davanti = r2.y > 0.0;
    if (!davanti) colore = sopra(cDisco, colore);
    float arco = (1.0 - smoothstep(0.0, 0.09, abs(d - 0.72))) * smoothstep(-0.15, 0.25, -r2.y);
    arco *= (0.5 + 0.7 * strati(float3(atan(uv.y, uv.x) * 2.0 - tempo, d * 4.0, 0.0))) * (0.6 + 0.4 * f);
    arco = clamp(arco, 0.0, 1.0);
    colore = sopra(float4(c2 * arco, arco), colore);
    float buio = 1.0 - smoothstep(0.48, 0.5, d);
    colore = sopra(float4(0.0, 0.0, 0.0, buio), colore);
    float fotoni = clamp((1.0 - smoothstep(0.0, 0.025, abs(d - 0.53))) * (0.8 + 0.4 * voce), 0.0, 1.0);
    colore = sopra(float4(c2 * fotoni, fotoni), colore);
    if (davanti) colore = sopra(cDisco, colore);
    return finale(colore);
}
"""
    }

    private val shader = RuntimeShader(sorgente(modello))
    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * @param r raggio dell'oggetto in pixel (l'alone arriva a più del doppio)
     * @param energia 0..1 quanto sta lavorando (pensa, parla)
     * @param voce 0..1 volume della voce
     * @param spento 0..1 quanto togliere colore
     * @param alone 0..2 forza del bagliore
     */
    fun disegna(canvas: Canvas, cx: Float, cy: Float, r: Float, tempo: Float, energia: Float, voce: Float,
                accento: Int, chiaro: Int, spento: Float, alone: Float) {
        shader.setFloatUniform("centro", cx, cy)
        shader.setFloatUniform("raggio", r)
        shader.setFloatUniform("tempo", tempo % 1000f)
        shader.setFloatUniform("energia", energia.coerceIn(0f, 1f))
        shader.setFloatUniform("voce", voce.coerceIn(0f, 1f))
        shader.setFloatUniform("spento", spento.coerceIn(0f, 1f))
        shader.setFloatUniform("alone", alone.coerceIn(0f, 2f))
        shader.setColorUniform("cAccento", accento)
        shader.setColorUniform("cChiaro", chiaro)
        pennello.shader = shader
        canvas.drawRect(cx - r * 2.3f, cy - r * 2.3f, cx + r * 2.3f, cy + r * 2.3f, pennello)
    }
}
