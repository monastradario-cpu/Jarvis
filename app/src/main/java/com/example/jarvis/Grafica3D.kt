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
            Triple("nucleo", "Nucleo", "Sfera di energia con anello in orbita"),
            Triple("sole", "Sole", "Stella di plasma con la corona che pulsa con la voce"),
            Triple("pianeta", "Pianeta", "Pianeta con continenti, nuvole, atmosfera e anelli"),
            Triple("cristallo", "Cristallo", "Gemma sfaccettata che ruota, con spigoli di luce"),
            Triple("ologramma", "Ologramma", "Globo a reticolo trasparente con scansione"),
            Triple("liquido", "Oro liquido", "Gocce di metallo liquido che si fondono"),
            Triple("atomo", "Atomo", "Nucleo con tre elettroni in orbita, davanti e dietro"),
            Triple("buconero", "Buco nero", "Disco di luce che gira attorno al buio")
        )

        fun forma(modello: String) = "3d_$modello"

        /** Da una forma di Tema.FORME al modello 3D (null se non è 3D). */
        fun modelloDaForma(forma: String): String? = when {
            forma == "nucleo3d" -> "nucleo"
            forma.startsWith("3d_") -> forma.removePrefix("3d_").takeIf { m -> MODELLI.any { it.first == m } }
            else -> null
        }

        /** Disponibile su questo telefono? (Android 13+ e programma grafico compilato senza errori) */
        val disponibile: Boolean by lazy {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && try {
                RuntimeShader(sorgente("nucleo")); true
            } catch (e: Throwable) {
                false
            }
        }

        fun sorgente(modello: String): String = TESTA + when (modello) {
            "sole" -> SOLE
            "pianeta" -> PIANETA
            "cristallo" -> CRISTALLO
            "ologramma" -> OLOGRAMMA
            "liquido" -> LIQUIDO
            "atomo" -> ATOMO
            "buconero" -> BUCO_NERO
            else -> NUCLEO
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
    float2 r2 = gira2(uv, 0.42);
    float2 e = float2(r2.x, r2.y / 0.26);
    float le = length(e);
    float banda = smoothstep(1.3, 1.36, le) * (1.0 - smoothstep(1.85, 1.95, le));
    float righe = 0.55 + 0.45 * sin(le * 38.0) * sin(le * 11.0 + 1.3);
    float an = clamp(banda * righe * (0.55 + 0.25 * f), 0.0, 1.0);
    float4 ca = float4(mix(a, c2, righe) * an, an);
    bool davanti = r2.y > 0.0;
    if (!davanti) colore = sopra(ca, colore);
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
    if (davanti) colore = sopra(ca, colore);
    return finale(colore);
}
"""

        private const val CRISTALLO = """
float scena(float3 p) {
    float3 q = giraY(giraX(p, tempo * 0.45), tempo * 0.7);
    q = abs(q);
    return (q.x + q.y + q.z - (1.05 + 0.12 * voce)) * 0.57735027;
}

float3 normale(float3 p) {
    float e = 0.002;
    return normalize(float3(
        scena(p + float3(e, 0.0, 0.0)) - scena(p - float3(e, 0.0, 0.0)),
        scena(p + float3(0.0, e, 0.0)) - scena(p - float3(0.0, e, 0.0)),
        scena(p + float3(0.0, 0.0, e)) - scena(p - float3(0.0, 0.0, e))));
}

half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza();
    float4 colore = bagliore(d * 0.95, a, 0.22 + 0.3 * f);
    float3 ro = float3(uv.x * 1.2, -uv.y * 1.2, 3.0);
    float3 rd = float3(0.0, 0.0, -1.0);
    float t = 0.0;
    bool colpito = false;
    for (int i = 0; i < 48; i++) {
        float h = scena(ro + rd * t);
        if (h < 0.001) { colpito = true; break; }
        t += h;
        if (t > 6.0) break;
    }
    if (colpito) {
        float3 p = ro + rd * t;
        float3 n = normale(p);
        float3 q = abs(giraY(giraX(p, tempo * 0.45), tempo * 0.7));
        float spigolo = 1.0 - smoothstep(0.0, 0.05, min(q.x, min(q.y, q.z)));
        float3 luce = normalize(float3(-0.5, 0.6, 0.7));
        float diffusa = max(dot(n, luce), 0.0);
        float riflesso = pow(max(dot(reflect(-luce, n), -rd), 0.0), 40.0);
        float bordo = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
        float3 dentro = mix(a * 0.25, c2, strati(reflect(rd, n) * 2.0 + float3(tempo * 0.3)));
        float3 col = dentro * (0.35 + 0.7 * diffusa) + c2 * riflesso + a * bordo * 1.1 + c2 * spigolo * (0.6 + 0.6 * f);
        colore = sopra(float4(col, 1.0), colore);
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

        private const val LIQUIDO = """
float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float scena(float3 p) {
    float t = tempo;
    float r = 0.48 + 0.08 * voce + 0.04 * energia;
    float3 p1 = float3(sin(t * 0.9) * 0.42, cos(t * 1.1) * 0.32, sin(t * 0.7) * 0.3);
    float3 p2 = float3(cos(t * 0.8) * 0.4, sin(t * 1.3) * 0.38, cos(t * 0.9) * 0.3);
    float3 p3 = float3(sin(t * 1.2 + 2.0) * 0.36, sin(t * 0.6 + 1.0) * 0.4, cos(t * 1.1) * 0.3);
    float d0 = length(p) - 0.55;
    float d1 = length(p - p1) - r;
    float d2 = length(p - p2) - r * 0.9;
    float d3 = length(p - p3) - r * 0.8;
    return smin(smin(smin(d0, d1, 0.35), d2, 0.35), d3, 0.35);
}

float3 normale(float3 p) {
    float e = 0.002;
    return normalize(float3(
        scena(p + float3(e, 0.0, 0.0)) - scena(p - float3(e, 0.0, 0.0)),
        scena(p + float3(0.0, e, 0.0)) - scena(p - float3(0.0, e, 0.0)),
        scena(p + float3(0.0, 0.0, e)) - scena(p - float3(0.0, 0.0, e))));
}

half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza();
    float4 colore = bagliore(d * 0.9, a, 0.2 + 0.3 * f);
    float3 ro = float3(uv.x * 1.15, -uv.y * 1.15, 3.0);
    float3 rd = float3(0.0, 0.0, -1.0);
    float t = 0.0;
    bool colpito = false;
    for (int i = 0; i < 48; i++) {
        float h = scena(ro + rd * t);
        if (h < 0.001) { colpito = true; break; }
        t += h;
        if (t > 6.0) break;
    }
    if (colpito) {
        float3 p = ro + rd * t;
        float3 n = normale(p);
        float3 r = reflect(rd, n);
        float cielo = smoothstep(-0.3, 0.9, r.y);
        float3 amb = mix(a * 0.15, c2 * 1.1, cielo) + a * strati(r * 2.5 + float3(tempo * 0.2)) * 0.4;
        float bordo = pow(1.0 - max(dot(n, -rd), 0.0), 4.0);
        float riflesso = pow(max(dot(r, normalize(float3(-0.4, 0.6, 0.7))), 0.0), 50.0);
        float3 col = amb * (0.6 + 0.4 * bordo) + c2 * riflesso * 1.2 + a * bordo * 0.5;
        colore = sopra(float4(col, 1.0), colore);
    }
    return finale(colore);
}
"""

        private const val ATOMO = """
float4 orbita(float2 uv, float k, float3 a, float3 c2, float f) {
    float incl = 1.15 + k * 0.35;
    float rot = k * 2.094 + tempo * 0.25;
    float3 U = giraY(giraX(float3(1.0, 0.0, 0.0), incl), rot);
    float3 V = giraY(giraX(float3(0.0, 1.0, 0.0), incl), rot);
    float R = 1.55;
    float2 u2 = float2(U.x, -U.y) * R;
    float2 v2 = float2(V.x, -V.y) * R;
    float det = u2.x * v2.y - u2.y * v2.x;
    if (abs(det) < 0.05) det = det < 0.0 ? -0.05 : 0.05;
    float al = (uv.x * v2.y - uv.y * v2.x) / det;
    float be = (u2.x * uv.y - u2.y * uv.x) / det;
    float th = atan(be, al);
    float2 pt = u2 * cos(th) + v2 * sin(th);
    float dist = length(uv - pt);
    float z = (U.z * cos(th) + V.z * sin(th)) * R;
    float te = tempo * (1.4 + k * 0.3) + k * 2.0;
    float scia = exp(-mod(te - th, 6.2831853) * 1.6);
    float linea = (1.0 - smoothstep(0.0, 0.03, dist)) * (0.25 + 0.75 * scia) * (0.6 + 0.4 * f);
    float2 pe = u2 * cos(te) + v2 * sin(te);
    float ze = (U.z * cos(te) + V.z * sin(te)) * R;
    float de = length(uv - pe);
    float elettrone = exp(-de * 30.0) * 1.3 + exp(-de * 9.0) * 0.3 * f;
    float davantiLinea = step(0.0, z);
    float davantiElettrone = step(0.0, ze);
    // x: linea davanti, y: linea dietro, z: elettrone davanti, w: elettrone dietro
    return float4(linea * davantiLinea, linea * (1.0 - davantiLinea), elettrone * davantiElettrone, elettrone * (1.0 - davantiElettrone));
}

half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 a = acc();
    float3 c2 = chi();
    float f = forza();
    float rn = 0.45 + 0.05 * voce;
    float4 colore = bagliore(d / rn, a, 0.3 + 0.4 * f) * step(rn, d);
    float4 o0 = orbita(uv, 0.0, a, c2, f);
    float4 o1 = orbita(uv, 1.0, a, c2, f);
    float4 o2 = orbita(uv, 2.0, a, c2, f);
    float dietro = clamp((o0.y + o1.y + o2.y) * 0.5 + o0.w + o1.w + o2.w, 0.0, 1.0);
    float avanti = clamp(o0.x + o1.x + o2.x + o0.z + o1.z + o2.z, 0.0, 1.0);
    colore = sopra(float4(mix(a, c2, 0.6) * dietro, dietro), colore);
    float dn = d / rn;
    if (dn < 1.0) {
        float z = sqrt(1.0 - dn * dn);
        float3 n = float3(uv.x / rn, -uv.y / rn, z);
        float3 q = giraY(n, tempo * 0.6);
        float plasma = strati(q * 3.0 + float3(tempo * 0.4));
        float3 luce = normalize(float3(-0.4, 0.5, 0.8));
        float diffusa = max(dot(n, luce), 0.0);
        float3 col = mix(a * 0.3, c2, plasma * z) * (0.45 + 0.7 * diffusa) + a * pow(1.0 - z, 2.0) + c2 * pow(z, 8.0) * 0.4 * f;
        float al = 1.0 - smoothstep(0.97, 1.0, dn);
        colore = sopra(float4(col * al, al), colore);
    }
    colore = sopra(float4(c2 * avanti, avanti), colore);
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
