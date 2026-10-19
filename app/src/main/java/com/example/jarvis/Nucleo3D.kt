package com.example.jarvis

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Nucleo 3D: una sfera di energia disegnata dalla scheda video del telefono (Android 13 e successivi).
 * Ogni punto è calcolato come su una sfera vera: luce, riflesso, bordo luminoso, energia che scorre
 * all'interno, un anello in orbita che passa davanti e dietro, e un alone che si allarga con la voce.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class Nucleo3D {

    companion object {
        /** Disponibile su questo telefono? (Android 13+ e programma grafico compilato senza errori) */
        val disponibile: Boolean by lazy {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && try {
                RuntimeShader(SORGENTE); true
            } catch (e: Throwable) {
                false
            }
        }

        const val SORGENTE = """
uniform float2 centro;
uniform float raggio;
uniform float tempo;
uniform float energia;
uniform float voce;
uniform float spento;
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

float4 sopra(float4 su, float4 giu) {
    return su + giu * (1.0 - su.a);
}

half4 main(float2 punto) {
    float2 uv = (punto - centro) / raggio;
    float d = length(uv);
    float3 acc = float3(cAccento.rgb);
    float3 chi = float3(cChiaro.rgb);
    float forza = 0.55 + 0.45 * energia + 0.6 * voce;

    // alone attorno alla sfera
    float alone = exp(-max(d - 1.0, 0.0) * (3.6 - 1.6 * voce)) * (0.28 + 0.35 * forza);
    alone *= 1.0 - smoothstep(2.0, 2.2, d);
    float4 colore = float4(acc * alone, alone) * step(1.0, d);

    // anello in orbita, inclinato: metà passa dietro la sfera, metà davanti
    float ang = -0.35;
    float2 r2 = float2(uv.x * cos(ang) - uv.y * sin(ang), uv.x * sin(ang) + uv.y * cos(ang));
    float2 e = float2(r2.x, r2.y / 0.3);
    float le = length(e);
    float scie = pow(0.5 + 0.5 * sin(atan(e.y, e.x) * 3.0 - tempo * 2.0 * (0.6 + energia)), 6.0);
    float anello = (1.0 - smoothstep(0.0, 0.07, abs(le - 1.5))) * (0.3 + 0.7 * scie) * (0.6 + 0.4 * forza);
    float4 coloreAnello = float4(chi * anello, anello);
    bool davanti = r2.y > 0.0;
    if (!davanti) colore = sopra(coloreAnello, colore);

    // la sfera vera e propria
    if (d < 1.0) {
        float z = sqrt(1.0 - d * d);
        float3 n = float3(uv.x, -uv.y, z);
        float3 q = giraY(n, tempo * 0.35);
        float plasma = strati(q * 2.4 + float3(0.0, tempo * 0.25, 0.0));
        float vene = 1.0 - abs(strati(q * 4.0 - float3(tempo * 0.4)) * 2.0 - 1.0);
        vene = pow(vene, 6.0) * forza;
        float centrale = z * z;
        float3 base = mix(acc * 0.16, acc, plasma * centrale);
        base += chi * vene * 0.9;
        float3 luce = normalize(float3(-0.4, 0.5, 0.8));
        float diffusa = max(dot(n, luce), 0.0);
        float riflesso = pow(max(dot(reflect(-luce, n), float3(0.0, 0.0, 1.0)), 0.0), 24.0);
        float bordo = pow(1.0 - z, 2.5);
        float3 c = base * (0.35 + 0.75 * diffusa) + chi * riflesso * 0.8 + acc * bordo * 1.2;
        c += chi * pow(centrale, 6.0) * 0.35 * forza;
        float a = 1.0 - smoothstep(0.985, 1.0, d);
        colore = sopra(float4(c * a, a), colore);
    }
    if (davanti) colore = sopra(coloreAnello, colore);

    // Jarvis spento (dorme, assonnato, offeso): meno colore
    float grigio = dot(colore.rgb, float3(0.3, 0.59, 0.11));
    colore.rgb = mix(colore.rgb, float3(grigio) * 0.7, spento);
    return half4(colore);
}
"""
    }

    private val shader = RuntimeShader(SORGENTE)
    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * @param r raggio della sfera in pixel (l'alone arriva al doppio)
     * @param energia 0..1 quanto sta lavorando (pensa, parla)
     * @param voce 0..1 volume della voce
     * @param spento 0..1 quanto togliere colore
     */
    fun disegna(canvas: Canvas, cx: Float, cy: Float, r: Float, tempo: Float, energia: Float, voce: Float,
                accento: Int, chiaro: Int, spento: Float) {
        shader.setFloatUniform("centro", cx, cy)
        shader.setFloatUniform("raggio", r)
        shader.setFloatUniform("tempo", tempo % 1000f)
        shader.setFloatUniform("energia", energia.coerceIn(0f, 1f))
        shader.setFloatUniform("voce", voce.coerceIn(0f, 1f))
        shader.setFloatUniform("spento", spento.coerceIn(0f, 1f))
        shader.setColorUniform("cAccento", accento)
        shader.setColorUniform("cChiaro", chiaro)
        pennello.shader = shader
        canvas.drawRect(cx - r * 2.2f, cy - r * 2.2f, cx + r * 2.2f, cy + r * 2.2f, pennello)
    }
}
