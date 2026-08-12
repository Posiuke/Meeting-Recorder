# Meeting Recorder

Aufnahme-Bot für **BigBlueButton / Nextcloud-Meetings**: Ein Bot tritt einem Raum
über die fertige Raum-URL bei (keine BBB-API/Checksums nötig), zeichnet das
Meeting auf und stellt Wiedergabe, Transkript und KI-Zusammenfassung im Web-UI
bereit. Bestehende Audio-/Videodateien lassen sich auch ohne Bot per Upload
auswerten.

Frontend (React) und Backend (Spring Boot, Java 21, Playwright) laufen zusammen
in **einem** Container; PostgreSQL läuft separat.

## Funktionen

- 🎙️ **Audio-Aufnahme** des Meetings (gemischte Teilnehmer-Streams), segmentiert nach MP3
- 🎬 **Optionale Video-Aufnahme** der Meeting-Ansicht als MP4 (Wiedergabe & Download im UI)
- 📤 **Datei-Upload**: vorhandene Audio-/Videodateien (MP3, WAV, M4A, MP4, MKV …) als Aufnahme übernehmen und auswerten — die Auswertungs-Vorlage ist schon im Upload-Dialog wählbar
- 🖥️ **Bildschirmaufnahme im Browser**: Bildschirm/Fenster/Tab samt Systemton und optionalem Mikrofon direkt im Tool aufnehmen — für Termine ohne Bot (Teams/Zoom/WebEx, Präsenz). **Erfordert HTTPS** und Chrome/Edge, siehe [docs/SCREEN_CAPTURE.md](docs/SCREEN_CAPTURE.md)
- 📝 **Transkription** wahlweise über einen **eigenen Whisper-Server** (optional mit Sprechertrennung/WhisperX) oder eine **OpenAI-kompatible Cloud-API** — mit fortlaufenden Zeitstempeln über die ganze Aufnahme und strukturierter Anzeige im UI
- ✨ **KI-Glättung des Transkripts** vor der Auswertung (Füllwörter, Satzzeichen, Erkennungsfehler) — Original bleibt erhalten, im Transkript-Tab umschaltbar; dazu ein **persönliches Glossar** für Abkürzungen und Fachbegriffe
- 🤖 **KI-Zusammenfassung** über jeden **OpenAI-kompatiblen** Chat-Endpoint — lokal (vLLM, Ollama) oder Cloud (OpenAI, Anthropic, Google Gemini, Groq, Mistral …)
- 🏷️ **Schlagworte & Suche**: Aufnahmen verschlagworten, nach Schlagwort filtern und in Titel, Meeting-URL, Schlagworten sowie auf Wunsch in **Transkript und Zusammenfassung** suchen
- 🎛️ **Auswertung pro Aufnahme anpassbar**: eigener Auswertungs-Prompt (mit Vorlagen für Vortrag, Interview, Sprachnotiz), maximale Länge, Sprache
- 🔁 **Erneut auswerten** (nur Zusammenfassung) und **Transkription neu erstellen** (Spracherkennung + Zusammenfassung) per Klick
- ⏱️ **Verarbeitungs-Zeitfenster** (STT/LLM z. B. nachts) plus „Jetzt auswerten"
- 🧪 **Verbindungstests** für Whisper und LLM direkt im Admin-Bereich
- 👥 **Teilen & Gruppen**, Admin-Bereich für Einstellungen und Benutzer
- 🔗 **Freigabe-Link**: Aufnahme per Adresse weitergeben — Empfänger sehen Video, Audio, Transkript und Zusammenfassung **ohne Anmeldung**, mit optionaler Laufzeit und jederzeit widerrufbar
- 🌐 **Oberfläche auf Deutsch und Englisch** – jeder Nutzer wählt seine Sprache selbst, gespeichert am Konto (gilt auf jedem Gerät)
- 🔐 **Anmeldung** per lokalem Konto **oder** LDAP/Active Directory — vollständig im Admin-Bereich konfigurier- und testbar
- 🧹 **Aufräumen**: Aufbewahrungsfrist für alte Aufnahmen, Bereinigung hängengebliebener Aufnahmen

## Tags

- `latest` — aktueller Stand
- `<git-hash>` — konkreter Commit (reproduzierbare Deployments)
- ggf. Versions-Tags wie `v3.0.0`

## Ports & Volumes

- Port **8080** — UI + API
- Volume **`/data/recordings`** — Aufnahmen, Transkripte, Zusammenfassungen, MP4s

## Wichtige Umgebungsvariablen

| Variable | Bedeutung |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL-Verbindung (Pflicht) |
| `JWT_SECRET` | Signaturschlüssel für Sessions (min. 32 Zeichen, zufällig) |
| `JWT_TTL_HOURS` | Login-Session-Dauer in Stunden (Default 168 = 7 Tage) |
| `ADMIN_USERNAME`, `ADMIN_INITIAL_PASSWORD` | Lokales Admin-Konto beim ersten Start (Passwortwechsel wird erzwungen) |
| `STORAGE_DIR` | Aufnahme-Verzeichnis (Default `/data/recordings`) |
| `MAX_UPLOAD_SIZE` | Maximale Größe für Datei-Uploads (Default `4GB`) |
| `MAX_CONCURRENT_BOTS` | Max. gleichzeitige Bots (Default 5) |
| `INSECURE_TLS` | Self-Signed-Zertifikate im Intranet akzeptieren |
| `SERVER_PORT` | HTTP-Port im Container (Default 8080) |

> Alles Weitere (Whisper, LLM, Zeitfenster, Bot-Verhalten, Bildschirmaufnahme,
> Aufbewahrung, LDAP) wird **zur Laufzeit im Admin-Bereich** konfiguriert und in
> der Datenbank gespeichert — keine Container-Neustarts nötig.

## Reverse Proxy / HTTPS

Der Container spricht HTTP; die TLS-Terminierung übernimmt üblicherweise ein
Reverse Proxy davor. Für die **Bildschirmaufnahme ist HTTPS Pflicht** — ohne
sicheren Kontext stellt der Browser `getDisplayMedia` gar nicht bereit. Der
Proxy sollte `X-Forwarded-Proto`/`-Host` setzen (die App wertet sie aus) und
den Anfragekörper nicht zwischenpuffern (`proxy_request_buffering off`), damit
die stückweise übertragene Aufnahme direkt durchläuft. Fertige nginx- und
Apache-Konfigurationen: [docs/SCREEN_CAPTURE.md](docs/SCREEN_CAPTURE.md).

## Schnellstart (docker compose)

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: bbbbot
      POSTGRES_USER: bbbbot
      POSTGRES_PASSWORD: bitte-aendern
    volumes:
      - pgdata:/var/lib/postgresql/data

  app:
    image: posiuke/bbb-recorder:latest
    depends_on:
      - db
    environment:
      DB_URL: jdbc:postgresql://db:5432/bbbbot
      DB_USER: bbbbot
      DB_PASSWORD: bitte-aendern
      JWT_SECRET: bitte-einen-langen-zufaelligen-wert-eintragen
      ADMIN_USERNAME: admin
      ADMIN_INITIAL_PASSWORD: bitte-aendern
      STORAGE_DIR: /data/recordings
    ports:
      - "8090:8080"
    volumes:
      - ./data/recordings:/data/recordings
    shm_size: "1gb"

volumes:
  pgdata:
```

## Erster Login

Mit `ADMIN_USERNAME` / `ADMIN_INITIAL_PASSWORD` anmelden. Beim ersten Login muss
ein neues Passwort vergeben werden. Danach optional unter
**Administration → Authentifizierung** LDAP/AD aktivieren und testen.

## Spracherkennung & KI einrichten

Ohne konfigurierte Dienste laufen Aufnahme und Wiedergabe trotzdem — nur
Transkript und Zusammenfassung brauchen Whisper bzw. ein LLM. Beide Dienste
werden unter **Administration → Einstellungen** konfiguriert; ein Klick auf
**„Verbindung testen"** prüft die gespeicherte Konfiguration sofort
(Erreichbarkeit, API-Key und Modellname).

### Variante A: Eigene Server (Intranet, Standard)

| Einstellung | Wert |
|---|---|
| `whisper.provider` | `local` |
| `whisper.url` | z. B. `http://whisper:9000/asr` ([onerahmet/openai-whisper-asr-webservice](https://hub.docker.com/r/onerahmet/openai-whisper-asr-webservice); mit `ASR_ENGINE=whisperx` ist Sprechertrennung möglich, dann `whisper.diarize` freischalten) |
| `llm.baseUrl` | z. B. `http://vllm:8000/v1` (vLLM, Ollama oder anderer OpenAI-kompatibler Server) |
| `llm.model` | Name des geladenen Modells |

Die Daten bleiben komplett im eigenen Netz.

### Variante B: Öffentliche Cloud-APIs

**Transkription** (OpenAI-Audio-API-Format):

| Einstellung | Wert |
|---|---|
| `whisper.provider` | `openai` |
| `whisper.openaiUrl` | `https://api.openai.com/v1/audio/transcriptions` (kompatible Anbieter wie Groq analog) |
| `whisper.openaiApiKey` | API-Schlüssel des Anbieters |
| `whisper.openaiModel` | z. B. `whisper-1` (mit Zeitstempeln) oder `gpt-4o-mini-transcribe` |

**Zusammenfassung** (jeder OpenAI-kompatible Chat-Endpoint):

| Anbieter | `llm.baseUrl` | `llm.model` (Beispiel) |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Anthropic | `https://api.anthropic.com/v1` | `claude-sonnet-5` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-2.5-flash` |
| Groq | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` |
| Mistral | `https://api.mistral.ai/v1` | `mistral-large-latest` |

Dazu jeweils `llm.apiKey` setzen.

> ⚠️ **Datenschutz:** Bei Cloud-APIs verlassen Audiodaten (Whisper) bzw.
> Transkript und Chat-Protokoll (LLM) das eigene Netz. Sprechertrennung
> (Diarisierung) wird von der OpenAI-Audio-API nicht unterstützt und steht nur
> mit eigenem WhisperX-Server zur Verfügung.

## Transkript-Glättung und Glossar

Zwischen Spracherkennung und Auswertung glättet das LLM das Rohtranskript. Die
Zusammenfassung nutzt die geglättete Fassung, das Whisper-Original bleibt
gespeichert und ist im Transkript-Tab über *Korrigiert / Original* einsehbar
(`transcript.md` bzw. `transcript_original.md`).

Geglättet wird **satzweise und in mehreren Schritten**: Whisper-Zeilen enden oft
mitten im Satz, deshalb werden sie zu ganzen Sätzen zusammengefasst; ein Satz wird
nie über zwei Schritte zerschnitten, ein Sprecherwechsel beendet immer einen Satz.
Zeitstempel und Sprecher-Labels werden nicht dem Modell überlassen: Es bekommt nur
die nummerierten Sätze, die Struktur setzt das Backend selbst wieder davor. Fällt
die Glättung aus, läuft die Auswertung unbeeinträchtigt mit dem Original weiter.

Unter **Glossar** pflegt jeder Nutzer eigene Abkürzungen und Fachbegriffe; bei
einer Aufnahme geht das Glossar ihres Besitzers in den Glättungs-Prompt ein.

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `correction.enabled` | `true` | Glättung ein/aus |
| `correction.systemPrompt` | (deutscher Standardprompt) | Anweisung an das LLM; Antwortformat `Nummer \| Satz` beibehalten |
| `correction.chunkChars` | `3000` | Zeichen je Glättungsschritt (bestimmt auch das Antwort-Token-Budget) |
| `correction.maxSentenceChars` | `500` | Trennung, wenn das Transkript keine Satzzeichen enthält |
| `correction.glossaryMaxChars` | `12000` | Wie viel Glossar in den Prompt geht (0 = unbegrenzt) |

## Schlagworte und Suche

Der Besitzer vergibt auf der Detailseite Schlagworte (max. 40 Zeichen, 20 je
Aufnahme; Schreibweisen werden zusammengefasst); alle mit Leseberechtigung sehen
und filtern danach. Über der Liste durchsucht ein Suchfeld Titel/Raumname,
Meeting-URL und Schlagworte — mit der Checkbox **„Auch in Transkript und
Zusammenfassung suchen"** zusätzlich die Inhalte. Es gibt nichts zu
konfigurieren; die Suche liefert stets nur eigene und geteilte Aufnahmen.

Die Inhaltssuche arbeitet mit `LIKE` ohne Volltextindex — für einige Tausend
Aufnahmen unproblematisch, bei sehr großen Beständen wäre ein Postgres-
Volltextindex der nächste Schritt.

## Bildschirmaufnahme

Nutzer nehmen ihren Bildschirm über **Aufnahmen → Bildschirm aufnehmen** direkt
im Browser auf; die laufende Aufnahme wird stückweise übertragen, sodass ein
Absturz höchstens die letzten Sekunden kostet. Voraussetzung sind HTTPS und
Chrome/Edge (Firefox schneidet keinen Systemton mit).

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `capture.enabled` | `true` | Funktion für Nutzer freigeschaltet |
| `capture.maxMegabytes` | `8192` | Obergrenze pro Aufnahme (Richtwert: 0,5 GB pro Stunde in Standardqualität, ohne Bild ca. 60 MB) |
| `capture.staleMinutes` | `5` | So lange ohne Daten gilt eine Aufnahme als abgebrochen; der Server schließt sie dann mit den vorhandenen Daten ab |

Einrichtung, Reverse-Proxy-Konfiguration und Fehlersuche:
[docs/SCREEN_CAPTURE.md](docs/SCREEN_CAPTURE.md).

## Auswertung pro Aufnahme anpassen

Auf der Aufnahme-Detailseite kann der Besitzer über **„Auswertung anpassen"**
eigenen Auswertungs-Prompt (mit Vorlagen für Vortrag, Interview, Sprachnotiz),
maximale Länge und Sprache der Zusammenfassung setzen — praktisch für
hochgeladene Dateien, die kein Meeting sind. Die Einstellungen wirken bei der
nächsten Auswertung („Jetzt auswerten", „Erneut auswerten" oder „Transkription
neu erstellen"). Die Standardvorgabe des Administrators lässt sich über
**„Standard übernehmen"** in das Feld holen und dort anpassen, statt sie
vollständig ersetzen zu müssen.

Zusammenfassung und geglättetes Transkript werden als GitHub-Markdown angezeigt
— Tabellen und Aufgabenlisten erscheinen als solche. Codeblöcke mit der Sprache
`mermaid` werden als Diagramm gezeichnet; anfordern lässt sich das über den
Auswertungs-Prompt („… zusätzlich als Mermaid-Flussdiagramm").

## Freigabe-Link (Zugriff ohne Anmeldung)

Über **Teilen → Link zum Teilen** erzeugt der Besitzer einer Aufnahme eine
öffentliche Adresse (`https://<host>/share/<token>`). Wer sie kennt, sieht dort
**Video, Audio, Transkript und Zusammenfassung** — ohne Konto und ohne
Anmeldung. Gedacht für Empfänger ohne Zugang zum System.

Chat- und Sitzungsprotokoll bleiben der angemeldeten Ansicht vorbehalten. Die
Laufzeit ist wahlweise unbegrenzt (bis zum Widerruf) oder 7/30/90 Tage; ein
Widerruf wirkt sofort. Der Dialog zeigt zu jedem Link die Zahl der Aufrufe und
den letzten Zugriff. Wird die Aufnahme gelöscht, verschwinden ihre Links mit ihr.

## Quellcode, Fehler und Feature-Wünsche

Der Quellcode liegt öffentlich auf GitHub:
[Posiuke/Meeting-Recorder](https://github.com/Posiuke/Meeting-Recorder).

Fehlermeldungen und Feature-Wünsche sind willkommen — von jedem, direkt als
Issue:
[Bug melden](https://github.com/Posiuke/Meeting-Recorder/issues/new?template=bug_report.yml)
· [Feature wünschen](https://github.com/Posiuke/Meeting-Recorder/issues/new?template=feature_request.yml)
· [alle Issues](https://github.com/Posiuke/Meeting-Recorder/issues).

Bitte in Logs vorher Zugangsdaten, API-Keys, interne Hostnamen und
Meeting-Inhalte entfernen — Issues sind öffentlich lesbar.
