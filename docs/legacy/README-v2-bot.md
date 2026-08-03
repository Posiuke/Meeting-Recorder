# BBB Headless Audio Recorder Bot

Dieses Repository enthält einen Headless‑Bot, der einem BigBlueButton‑Raum beitritt, Audio mitschneidet, die Audiodateien transkribiert (externer ASR) und automatisierte Meeting‑Zusammenfassungen (LLM) erstellt. Optional werden die fertigen Zusammenfassungen in ein Gitea‑Repository hochgeladen.

Diese README enthält Installations‑ und Betriebsanweisungen, Konfiguration, Troubleshooting und Hinweise für Offline‑Umgebungen.

---

Inhaltsübersicht
- Kurz: Was macht der Bot?
- Voraussetzungen
- Installation (inkl. Offline/Repository‑Manager)
- Konfiguration (.env / /etc/default/bbb-bot)
- Systemd service (start/stop/logs)
- Gitea Upload (Token, Pfad)
- Troubleshooting (häufige Probleme & Logs)
- Upgrade / Deinstall
- Sicherheit & Empfehlungen

---

Kurz: Was macht der Bot?
- Beitritt in einen BBB‑Raum (headless via Playwright/Chromium)
- Start/Stop der Aufnahme basierend auf Teilnehmerzahl / Audio‑Spuren
- Manuelle Steuerung der Aufzeichnung per Chat-Befehle (STARTRECORDING / STOPRECORDING)
- Segmentierte Aufzeichnung, Transcodierung zu MP3 (ffmpeg)
- Externer ASR‑Service (HTTP) wird zum Transkribieren verwendet
- LLM‑Zusammenfassung per konfiguriertem LLM‑Server (HTTP)
- Optionale Uploads der erzeugten Markdown‑Summaries in ein Gitea‑Repo
- Resilienz: Auto‑Reconnect, Watchdogs, Dateisystem‑basierte Finalize‑Checks

---

Voraussetzungen
- Ubuntu/Debian-ähnliches System mit apt (oder interner apt‑Mirror / Repository‑Manager)
- Git, nodejs (>=18), npm, ffmpeg, curl müssen aus den konfigurierten Repos verfügbar sein
- Chrome/Chromium auf dem Host (Pfad konfigurierbar via `CHROME_PATH`)
- Externer ASR Service erreichbar (z. B. `http://asr-host:11436/asr`)
- Optional: LLM‑Server (z. B. Qwen/Inference) erreichbar (für Zusammenfassungen)
- Optional: Gitea Repo (für Uploads) — Personal Access Token empfohlen

Hinweis für Offline‑Umgebungen:
- Die VM braucht keinen Zugriff aufs Internet, wenn ein interner Repository‑Manager die benötigten Debian‑Pakete bereitstellt.
- Standard‑install.sh installiert alle Pakete mit `apt` — es ist so ausgelegt, dass Ihr interner Mirror die Pakete liefert.
- Falls Internet verfügbar ist und Sie NodeSource nutzen möchten, setzen Sie vor dem Installieren die Umgebungsvariable `INSTALL_NODE_FROM_NODESOURCE=1`.

---

Installation (empfohlen)
1. Kopiere das Repository auf die Ziel‑VM (z. B. per scp).
2. Wechsel ins Projekt‑Root (oder `scripts/`): hier liegt `install.sh`.
3. Führe das Installationsskript als root aus:
   - Aus dem Repo‑Root:
     sudo ./scripts/install.sh
   - Falls du Pfade ändern willst:
     sudo ./scripts/install.sh /pfad/zum/src /opt/bbb-bot bbb-bot bbb-bot
4. Interaktiver Prompt:
   - Das Skript legt `/etc/default/bbb-bot` an (Template) — im interaktiven Modus fragt es auch nach Gitea‑Einstellungen (Repo URL, username, email, token, target path).
   - Falls non‑interactive, bearbeite `/etc/default/bbb-bot` nachträglich.

Wichtig:
- Wenn eure Host‑Installation keinen direkten Internetzugang hat, NICHT `INSTALL_NODE_FROM_NODESOURCE=1` setzen — der Installer verwendet als Default die internen apt‑Repos.
- Installer erstellt:
  - Installation unter `/opt/bbb-bot` (konfigurierbar)
  - Service user `bbb-bot` (konfigurierbar)
  - Systemd Unit: `/etc/systemd/system/bbb-bot.service`
  - Env file: `/etc/default/bbb-bot`

---

Konfiguration (/etc/default/bbb-bot)
Die wichtigsten Variablen (Beispiele):

- MEETING_URL - vollständige BBB‑Raum‑URL
- DISPLAY_NAME - Name des Bots im Raum
- OUTPUT_DIR - Verzeichnis für Aufzeichnungen (z. B. /app/recordings)
- INSECURE_TLS - "true" falls TLS‑Fehler ignoriert werden sollen (nur intern)
- CHAT_STOP_MESSAGE - Chat-Befehl zum Stoppen/Verwerfen der Aufzeichnung (Standard: "STOPRECORDING")
- CHAT_START_MESSAGE - Chat-Befehl zum Starten/Wiederaufnehmen der Aufzeichnung (Standard: "STARTRECORDING")
- ASR_URL - URL des ASR‑Endpoints (z. B. `http://asr-host:11436/asr`)
- LLM_BASE_URL - URL des LLM Servers (z. B. `http://llm-host:11434/v1`)
- LLM_MODEL - Modellname (z. B. `/Qwen3-32B`)
- GITEA_REPO_URL - (optional) z. B. `https://git.example.com/org/dokumentation.git`
- GITEA_USERNAME, GITEA_EMAIL, GITEA_TOKEN - (optional) für Push; Token empfohlen
- GITEA_TARGET_PATH - Pfad im Repo, Standard `protokolle/meetings/bbb`
- GITEA_BRANCH - Zielbranch, Standard `main`
- AUTO_RECONNECT - "true" um automatisches Rejoin zu erlauben
- RECONNECT_MAX_ATTEMPTS - `-1` für unbegrenzt
- DEBUG_STATE - "true" für ausführliche Logs
- KEEP_BBB_TMP - "1" behält temporäre LLM request/response files für Debug

Nach Änderungen: restart service:
sudo systemctl restart bbb-bot.service

---

Chat-Befehle zur Aufzeichnungssteuerung
Die Aufzeichnung kann von Teilnehmern per Chat-Nachricht gesteuert werden:
- CHAT_STOP_MESSAGE (Standard: "STOPRECORDING") - Stoppt die laufende Aufzeichnung und verwirft sie
- CHAT_START_MESSAGE (Standard: "STARTRECORDING") - Startet die Aufzeichnung manuell

Hinweise:
- Die Chat-Befehle sind case-insensitive (Groß-/Kleinschreibung wird ignoriert)
- Der START-Befehl funktioniert nur, wenn genügend Teilnehmer und Audiospuren vorhanden sind
- Der STOP-Befehl funktioniert nur während einer aktiven Aufzeichnung
- Nach einem STOP kann die Aufzeichnung mit START wieder aufgenommen werden
- Der Bot bestätigt jeden Befehl mit einer Chat-Nachricht

Beispiel:
- Teilnehmer schreibt "STOPRECORDING" im Chat → Aufzeichnung wird gestoppt und verworfen
- Teilnehmer schreibt "STARTRECORDING" im Chat → Aufzeichnung wird (wieder) gestartet

---

Systemd service: Start / Stop / Logs
- Service unit: /etc/systemd/system/bbb-bot.service
- Starten:
  sudo systemctl enable bbb-bot.service
  sudo systemctl start bbb-bot.service
- Stoppen:
  sudo systemctl stop bbb-bot.service
- Logs:
  sudo journalctl -u bbb-bot.service -f

---

Gitea Upload — Hinweise
- Empfehlung: Erzeuge in Gitea einen Personal Access Token (mit repo push rights).
- Installer fragt interaktiv nach:
  - GITEA_REPO_URL (HTTPS)
  - GITEA_USERNAME, GITEA_EMAIL
  - GITEA_TOKEN (Token empfohlen)
  - GITEA_TARGET_PATH (z. B. `protokolle/meetings/bbb`)
- Die Summary‑Upload‑Logik:
  - Klont das Repo (shallow clone auf angegebenen Branch) in ein temporäres Verzeichnis.
  - Kopiert die erzeugte Markdown in `${GITEA_TARGET_PATH}/${summary-filename}`.
  - Commit & Push in den konfigurierten Branch.
- Wenn Gitea‑Vars leer → Upload übersprungen (kein Fehler).

Sicherheits‑Hinweis:
- Die Umgebungsdatei `/etc/default/bbb-bot` wird mit Berechtigung 640 angelegt — nur root kann sie ändern. Bewahre Tokens dort sicher auf.
- Alternativ: verwende Vault/Secret‑Store und setze nur die notwendigen ENV vars für den Dienst.

---

Troubleshooting / häufige Probleme
1) "TypeError: fetch failed" / undici‑Fehler:
   - Ursache: Node/undici Netzwerk/Proxy oder LLM‑Server nicht erreichbar.
   - Workaround: wir rufen das LLM per curl/execa auf — prüfe, ob LLM‑Endpoint erreichbar:
     curl -v -X POST "http://LLM_HOST:PORT/v1/chat/completions" -H "Content-Type: application/json" --data-binary @/tmp/some.json --max-time 10
   - Prüfe `HTTP_PROXY` / `HTTPS_PROXY` Umgebungsvariablen.

2) Bot verschwindet aus Raum:
   - Auto‑Reconnect ist verfügbar (setze AUTO_RECONNECT=true).
   - Setze RECONNECT_MAX_ATTEMPTS=-1, damit der Bot unbegrenzt versucht, wieder beizutreten.
   - Stelle sicher, dass der Node‑Prozess unter systemd läuft (Restart=always), sonst stoppt das Reconnect‑System bei Prozessende.

3) Upload zu Gitea funktioniert nicht:
   - Prüfe, ob `git` installiert ist (Installer legt das an).
   - Prüfe Gitea‑URL, Token und Rechte; teste manuell:
     git clone https://username:token@git.example.com/org/dokumentation.git
   - In Offline‑Setups: Gitea muss von der VM erreichbar sein (internes Netzwerk).

4) LLM/curl hängt oder liefert leere Antwort:
   - Setze env `LLM_CURL_MAX_TIME_SEC` und `LLM_EXEC_TIMEOUT_MS` kleiner (z. B. 30s) für schnellere Failures.
   - Aktiviere `KEEP_BBB_TMP=1` um die temporären Request‑JSONs zu behalten (unter /tmp) und teste manuell.
   - Prüfe die erzeugten `/tmp/bbb_llm_response_*.json` und `/tmp/bbb_llm_error_*.log`.

5) FFmpeg Transcoding Fehler:
   - Prüfe ffmpeg Version (aus interner apt).
   - In case of corrupted webm, installer has `FFMPEG_REPAIR_ATTEMPT` flag for repair attempts.

---

Upgrade / Rollout neuer Versionen
- Deployment (on host):
  1. Stoppe Service (optional): sudo systemctl stop bbb-bot.service
  2. Kopiere neue Source (oder pull) ins Install‑Verzeichnis (falls managed by install.sh, rerun install).
  3. Als Service‑User: cd /opt/bbb-bot && npm ci --omit=dev && npm run build
  4. Restart: sudo systemctl restart bbb-bot.service
- Alternative: rerun `sudo ./scripts/install.sh` mit dem neuen SRC_DIR.

---

Deinstall
- Es gibt ein Uninstall‑Skript: `scripts/uninstall.sh`.
- Beispiel (nur service/unit entfernen):
  sudo ./scripts/uninstall.sh
- Vollständige Entfernung (inkl. install dir, recordings und user):
  sudo ./scripts/uninstall.sh /opt/bbb-bot bbb-bot bbb-bot --purge
- Achtung: `--purge` löscht Dateien unwiderruflich!

---

Debugging Hinweise
- Aktiviere `DEBUG_STATE=true` in `/etc/default/bbb-bot` für ausführlichere Logs.
- LLM/ASR Debug:
  - KEEP_BBB_TMP=1 -> tmp Request/Response JSONs werden behalten (/tmp/bbb_llm_req_*.json, /tmp/bbb_llm_response_*.json)
  - Schau nach `/tmp/bbb_asr_stderr_*.log` und `/tmp/bbb_llm_error_*.log`
- Beobachte Journal:
  sudo journalctl -u bbb-bot.service -f

---

Design‑Hinweise / Verhalten
- Der Bot wartet beim Stop auf MP3‑Dateien (stabiler mtime) statt ausschließlich auf interne Promises — das macht Finalize robuster.
- LLM‑Aufrufe verwenden curl via execa mit retries; leere oder unerwartete Antworten führen zu logged errors und (falls möglich) zu einer failed marker datei.
- Gitea Upload ist optional und best‑effort: Fehler verhindern nicht den weiteren Betrieb.

---

Support / Kontakt
- Wenn du Logs oder Fehlermeldungen postest, leite bitte:
  - journalctl -u bbb-bot.service -n 300
  - /tmp/bbb_llm_* files (falls KEEP_BBB_TMP=1)
  - Beispiel: curl tests gegen ASR / LLM aus der VM (siehe oben)
