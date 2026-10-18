"""
Jarvis PC - il ponte tra Jarvis sul telefono e il computer Windows.

Avvio:      doppio clic su jarvis_pc.py  (serve Python 3 installato da python.org)
            oppure:  py jarvis_pc.py
Avvio automatico all'accensione del PC:
            py jarvis_pc.py --avvio-automatico

All'avvio mostra INDIRIZZO, CODICE e MAC da scrivere nell'app Jarvis
(SISTEMA > COLLEGA > PC Windows). Telefono e PC devono essere sulla stessa rete Wi-Fi.
Usa solo librerie già incluse in Python: niente da installare.
"""

import ctypes
import json
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORTA = 8765
CARTELLA = Path(os.environ.get("APPDATA", Path.home())) / "JarvisPC"
CONFIG = CARTELLA / "config.json"
DOWNLOAD = Path.home() / "Downloads" / "Jarvis"


# ---------------------------------------------------------------- configurazione

def carica_config():
    CARTELLA.mkdir(parents=True, exist_ok=True)
    if CONFIG.exists():
        try:
            return json.loads(CONFIG.read_text(encoding="utf-8"))
        except Exception:
            pass
    config = {"codice": secrets.token_hex(4).upper()}
    CONFIG.write_text(json.dumps(config), encoding="utf-8")
    return config


def ip_locale():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return socket.gethostbyname(socket.gethostname())
    finally:
        s.close()


def mac():
    n = uuid.getnode()
    return ":".join(f"{(n >> i) & 0xFF:02X}" for i in range(40, -1, -8))


# ---------------------------------------------------------------- azioni su Windows

WINDOWS = sys.platform.startswith("win")

TASTI = {
    "volume_su": 0xAF, "volume_giu": 0xAE, "muto": 0xAD,
    "play_pausa": 0xB3, "avanti": 0xB0, "indietro": 0xB1,
}


def premi(tasto, volte=1):
    if not WINDOWS:
        return
    for _ in range(volte):
        ctypes.windll.user32.keybd_event(tasto, 0, 0, 0)
        ctypes.windll.user32.keybd_event(tasto, 0, 2, 0)
        time.sleep(0.03)


def cartelle_start():
    cartelle = [
        Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs",
        Path(os.environ.get("PROGRAMDATA", "C:/ProgramData")) / "Microsoft/Windows/Start Menu/Programs",
        Path.home() / "Desktop",
        Path(os.environ.get("PUBLIC", "C:/Users/Public")) / "Desktop",
    ]
    return [c for c in cartelle if c.exists()]


def apri_programma(nome):
    """Cerca il collegamento nel menu Start o sul desktop e lo apre."""
    cercato = nome.lower().strip()
    if not cercato:
        return "Dimmi quale programma aprire."
    candidati = []
    for cartella in cartelle_start():
        for percorso in cartella.rglob("*"):
            if percorso.suffix.lower() in (".lnk", ".url", ".exe", ".appref-ms"):
                titolo = percorso.stem.lower()
                if titolo == cercato:
                    candidati.insert(0, percorso)
                elif cercato in titolo:
                    candidati.append(percorso)
    if candidati:
        scelto = min(candidati, key=lambda p: (p.stem.lower() != cercato, len(p.stem)))
        os.startfile(str(scelto))
        return f"Ho aperto {scelto.stem} sul PC."
    # protocolli noti (app dello Store)
    protocolli = {
        "spotify": "spotify:", "impostazioni": "ms-settings:", "calcolatrice": "calculator:",
        "store": "ms-windows-store:", "posta": "outlookmail:", "foto": "ms-photos:", "mappe": "bingmaps:",
        "whatsapp": "whatsapp:", "esplora file": "explorer", "blocco note": "notepad", "terminale": "wt",
    }
    for chiave, comando in protocolli.items():
        if chiave in cercato:
            if comando.endswith(":"):
                os.startfile(comando)
            else:
                subprocess.Popen(comando, shell=True)
            return f"Ho aperto {chiave} sul PC."
    try:
        subprocess.Popen(f'start "" "{nome}"', shell=True)
        return f"Provo ad aprire {nome} sul PC."
    except Exception:
        return f"Non trovo il programma {nome} sul PC."


def scrivi_testo(testo):
    """Incolla il testo dove c'è il cursore sul PC."""
    copia(testo)
    time.sleep(0.2)
    if WINDOWS:
        u = ctypes.windll.user32
        u.keybd_event(0x11, 0, 0, 0)          # Ctrl
        u.keybd_event(0x56, 0, 0, 0)          # V
        u.keybd_event(0x56, 0, 2, 0)
        u.keybd_event(0x11, 0, 2, 0)
    return "Ho scritto il testo sul PC."


def copia(testo):
    try:
        import tkinter
        finestra = tkinter.Tk()
        finestra.withdraw()
        finestra.clipboard_clear()
        finestra.clipboard_append(testo)
        finestra.update()
        finestra.destroy()
    except Exception:
        subprocess.run("clip", input=testo.encode("utf-16"), shell=True)
    return "Testo copiato negli appunti del PC."


def messaggio(testo):
    if WINDOWS:
        threading.Thread(
            target=lambda: ctypes.windll.user32.MessageBoxW(0, testo, "Jarvis", 0x40 | 0x40000), daemon=True
        ).start()
    else:
        print("MESSAGGIO:", testo)
    return "Messaggio mostrato sul PC."


def esegui(azione, valore):
    if azione == "apri":
        if valore.startswith("http") or "." in valore and " " not in valore:
            webbrowser.open(valore if valore.startswith("http") else "https://" + valore)
            return "Ho aperto il sito sul PC."
        return apri_programma(valore)
    if azione == "sito":
        webbrowser.open(valore if valore.startswith("http") else "https://" + valore)
        return "Ho aperto il sito sul PC."
    if azione == "cerca":
        webbrowser.open("https://www.google.com/search?q=" + urllib.parse.quote(valore))
        return "Ho aperto la ricerca sul PC."
    if azione in TASTI:
        volte = 5 if azione in ("volume_su", "volume_giu") else 1
        premi(TASTI[azione], volte)
        return {"volume_su": "Volume del PC alzato.", "volume_giu": "Volume del PC abbassato.", "muto": "Audio del PC silenziato o riattivato.",
                "play_pausa": "Play o pausa sul PC.", "avanti": "Brano successivo sul PC.", "indietro": "Brano precedente sul PC."}[azione]
    if azione == "blocca":
        if WINDOWS:
            ctypes.windll.user32.LockWorkStation()
        return "PC bloccato."
    if azione == "spegni":
        subprocess.run("shutdown /s /t 30", shell=True)
        return "Il PC si spegne tra 30 secondi. Puoi ancora annullare."
    if azione == "riavvia":
        subprocess.run("shutdown /r /t 30", shell=True)
        return "Il PC si riavvia tra 30 secondi. Puoi ancora annullare."
    if azione == "annulla_spegnimento":
        subprocess.run("shutdown /a", shell=True)
        return "Spegnimento annullato."
    if azione == "scrivi":
        return scrivi_testo(valore)
    if azione == "copia":
        return copia(valore)
    if azione == "messaggio":
        return messaggio(valore)
    if azione == "stato":
        return f"Il PC {socket.gethostname()} è acceso e collegato."
    return f"Azione sconosciuta: {azione}"


# ---------------------------------------------------------------- server

class Gestore(BaseHTTPRequestHandler):
    codice = ""

    def _autorizzato(self):
        return secrets.compare_digest(self.headers.get("X-Jarvis", ""), self.codice)

    def _rispondi(self, stato, dati):
        corpo = json.dumps(dati, ensure_ascii=False).encode("utf-8")
        self.send_response(stato)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(corpo)))
        self.end_headers()
        self.wfile.write(corpo)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path != "/comando":
            return self._rispondi(404, {"esito": "Non trovato"})
        if not self._autorizzato():
            return self._rispondi(401, {"esito": "Codice sbagliato"})
        q = urllib.parse.parse_qs(url.query)
        azione = q.get("azione", [""])[0]
        valore = q.get("valore", [""])[0]
        try:
            esito = esegui(azione, valore)
        except Exception as e:
            esito = f"Errore sul PC: {e}"
        print(time.strftime("%H:%M:%S"), azione, valore, "->", esito)
        self._rispondi(200, {"esito": esito})

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        if url.path != "/file":
            return self._rispondi(404, {"esito": "Non trovato"})
        if not self._autorizzato():
            return self._rispondi(401, {"esito": "Codice sbagliato"})
        nome = os.path.basename(urllib.parse.parse_qs(url.query).get("nome", ["file"])[0]) or "file"
        DOWNLOAD.mkdir(parents=True, exist_ok=True)
        destinazione = DOWNLOAD / nome
        n = 1
        while destinazione.exists():
            destinazione = DOWNLOAD / f"{Path(nome).stem} ({n}){Path(nome).suffix}"
            n += 1
        with open(destinazione, "wb") as f:
            lunghezza = self.headers.get("Content-Length")
            if lunghezza:
                rimasti = int(lunghezza)
                while rimasti > 0:
                    pezzo = self.rfile.read(min(65536, rimasti))
                    if not pezzo:
                        break
                    f.write(pezzo)
                    rimasti -= len(pezzo)
            else:
                # invio a blocchi (chunked)
                while True:
                    riga = self.rfile.readline().strip()
                    dimensione = int(riga, 16) if riga else 0
                    if dimensione == 0:
                        self.rfile.readline()
                        break
                    f.write(self.rfile.read(dimensione))
                    self.rfile.readline()
        print(time.strftime("%H:%M:%S"), "file ricevuto:", destinazione)
        messaggio(f"Ricevuto da Jarvis: {destinazione.name}")
        self._rispondi(200, {"esito": f"{destinazione.name} salvato in Download\\Jarvis."})

    def log_message(self, *args):
        pass


def avvio_automatico():
    """Crea un collegamento nella cartella Esecuzione automatica di Windows."""
    esecuzione = Path(os.environ["APPDATA"]) / "Microsoft/Windows/Start Menu/Programs/Startup"
    script = Path(__file__).resolve()
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    bat = esecuzione / "JarvisPC.bat"
    bat.write_text(f'@echo off\nstart "" "{pythonw}" "{script}"\n', encoding="utf-8")
    print("Fatto: Jarvis PC partirà da solo a ogni accensione del computer.")


def main():
    if "--avvio-automatico" in sys.argv:
        avvio_automatico()
        return
    config = carica_config()
    Gestore.codice = config["codice"]
    server = ThreadingHTTPServer(("0.0.0.0", PORTA), Gestore)
    print("=" * 52)
    print("  JARVIS PC è attivo")
    print("=" * 52)
    print(f"  Indirizzo del PC : {ip_locale()}")
    print(f"  Codice           : {config['codice']}")
    print(f"  MAC (per accendere il PC da spento): {mac()}")
    print("-" * 52)
    print("  Scrivili nell'app: SISTEMA > COLLEGA > PC Windows")
    print("  Se Windows chiede il permesso del firewall, scegli 'Reti private'.")
    print("  Lascia aperta questa finestra (o usa --avvio-automatico).")
    print("=" * 52)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
