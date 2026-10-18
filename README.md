# Jarvis per Android

Assistente vocale personale in italiano: ascolta, ragiona, agisce sul telefono e risponde a voce.
Gratuito con Gemini di Google; Claude di Anthropic è un'opzione facoltativa a consumo.

## Come ottenere l'APK
Ogni modifica caricata su `main` costruisce automaticamente l'app.
Apri la sezione **Releases**, scarica **Jarvis.apk** dall'ultima versione e installalo sopra la precedente.

## Primo avvio
1. Crea una chiave gratuita su aistudio.google.com/apikey
2. Apri Jarvis, tieni premuta la sfera, scegli **CERVELLO** e incolla la chiave
3. Tocca la sfera e parla, oppure di' "Ehi Jarvis" con la modalità sempre attiva

## Cosa sa fare
- **Voce**: "Ehi Jarvis" / "Ciao Jarvis" offline (modello italiano), riconoscimento della tua voce,
  risposte in streaming (parla mentre pensa), interrompibile con "basta" o "Jarvis", dialogo continuo
- **Comandi istantanei**, anche senza internet: torcia, volume, ora, data, batteria, sveglie, timer, musica, chiamate, note, aprire app
- **Telefono e app**: apre app, chiama, WhatsApp, SMS, legge e risponde alle notifiche, controlla le altre app
  passo per passo verificando lo schermo dopo ogni azione
- **Calendario, promemoria** (a orario, ripetuti, di luogo), **navigazione**, **meteo**, **notizie** (ANSA, Google News), **Wikipedia**
- **Vista**: foto descritte, oppure fotocamera dal vivo con domande a voce e modalità continua
- **Proattivo**: avvisa per gli appuntamenti (e quando partire), i messaggi insistenti, la pioggia
- **Assistente predefinito** del telefono, **widget**, tessera nel menu a tendina
- **Auto e cuffie**: si attiva da solo col Bluetooth dell'auto e risponde in breve
- **Casa intelligente** con Home Assistant e scorciatoie web
- **Email** (Gmail con password per le app, Libero, Yahoo, iCloud...)
- **PC Windows**: apre programmi e siti, volume, musica, blocca, spegne, scrive testo, riceve file, si accende con Wake-on-LAN
- **Memoria**: ricordi, lezioni, routine, diario, riassunto; usa solo i ricordi pertinenti e impara dalle conversazioni
- **Statistiche** e **backup** della memoria (anche automatico ogni settimana)
- **Aspetto**: 8 colori più tonalità libera, 7 forme del nucleo, 5 stili, 3 sfondi, editor dal vivo;
  zoom con due dita, anelli da far girare col dito, onde di luce al tocco

## Novità della versione 3
- **Aggiornamento automatico**: Jarvis avvisa quando c'è una versione nuova e la installa con un tocco
- **Voce neurale offline** (Piper): Miro, Paola, Riccardo o Dii, da scaricare in VOCE
- **Automazioni** create a voce: "quando arrivo a casa accendi le luci", "ogni sera alle 23 metti il non disturbare"
- **Non disturbare, suoneria e luminosità** a voce
- **Condividi → Jarvis: chiedi** per riassumere, tradurre o spiegare testi, link, foto, PDF e messaggi vocali
- **Traduttore simultaneo** con lo schermo diviso tra le due persone
- **Nome libero** dell'assistente e **voci della famiglia** riconosciute
- **Resoconto della settimana** la domenica sera, **modalità privata**, **liste** della spesa da mandare su WhatsApp

## Sicurezza
- Chiavi API, password e token sono cifrati con il chip di sicurezza del telefono (Android Keystore)
- Gli acquisti richiedono sempre due conferme (controllo nel codice, non aggirabile dall'IA)
- La cancellazione di appuntamenti richiede due conferme
- Chiavi API e password restano solo sul telefono e non finiscono nei backup

## Jarvis sul PC
Nella cartella `pc` c'è `jarvis_pc.py`: avvialo sul computer Windows (serve Python 3) e scrivi nell'app,
in **COLLEGA**, l'indirizzo, il codice e il MAC che ti mostra. `py jarvis_pc.py --avvio-automatico` lo fa partire a ogni accensione.
