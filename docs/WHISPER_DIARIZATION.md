# Whisper auf Sprechererkennung (Diarisierung) umstellen

Diese Anleitung beschreibt, wie der bestehende Whisper-Container
(`onerahmet/openai-whisper-asr-webservice`) so umkonfiguriert wird, dass die
Transkripte **Sprecher-Labels** enthalten ("wer hat was gesagt"). Das Backend
ist bereits darauf vorbereitet: Sobald die Whisper-Antwort Sprecher-Segmente
enthält, tauchen sie automatisch in Transkript und Zusammenfassung auf.

## Hintergrund

Der Container unterstützt drei Engines (Umgebungsvariable `ASR_ENGINE`):

| Engine | Diarisierung | Anmerkung |
|---|---|---|
| `openai_whisper` (aktuell im Einsatz) | nein | Referenz-Implementierung |
| `faster_whisper` | nein | schneller, weniger VRAM |
| `whisperx` | **ja** | Whisper + Alignment + pyannote-Diarisierung |

Für Sprechererkennung wird also `ASR_ENGINE=whisperx` benötigt. WhisperX nutzt
intern [pyannote.audio](https://github.com/pyannote/pyannote-audio) für die
Sprecher-Segmentierung — diese Modelle werden von Hugging Face geladen und
sind **zugangsbeschränkt (gated)**, deshalb braucht es einmalig einen
Hugging-Face-Account und ein Token.

## Schritt 1: Hugging-Face-Token besorgen (einmalig)

1. Kostenlosen Account auf https://huggingface.co anlegen.
2. Die Nutzungsbedingungen dieser beiden Modelle akzeptieren (Button
   "Agree and access repository" auf der jeweiligen Modellseite):
   - https://huggingface.co/pyannote/segmentation-3.0
   - https://huggingface.co/pyannote/speaker-diarization-3.1
3. Unter https://huggingface.co/settings/tokens ein **Read**-Token erzeugen
   (Format `hf_...`).

## Schritt 2: Modelle für die Offline-Umgebung vorladen

Da die Ziel-VM keinen Internetzugang hat, müssen die Modelle auf einem
Rechner **mit** Internet einmal heruntergeladen und dann übertragen werden.

Auf einem Internet-Rechner mit Docker:

```bash
mkdir -p /tmp/whisper-models /tmp/hf-cache

docker run --rm \
  -e ASR_MODEL=large-v3 \
  -e ASR_ENGINE=whisperx \
  -e HF_TOKEN=hf_IHR_TOKEN \
  -v /tmp/whisper-models:/root/.cache/whisper \
  -v /tmp/hf-cache:/root/.cache/huggingface \
  -p 9000:9000 \
  onerahmet/openai-whisper-asr-webservice:latest \
  &   # Container starten und einmal eine Testdatei transkribieren

# Test-Request mit Diarisierung, damit ALLE Modelle (Whisper, Alignment,
# pyannote) heruntergeladen werden:
curl -F "audio_file=@test.mp3" \
  "http://localhost:9000/asr?task=transcribe&language=de&output=json&diarize=true"
```

> **Korrektur nach Praxistest (2026-07-13):** Die tatsächliche Cache-Struktur
> weicht von der ursprünglichen Annahme ab — es braucht **drei** Caches:
>
> | Container-Pfad | Inhalt | Größe |
> |---|---|---|
> | `/root/.cache/huggingface` | faster-whisper large-v3 (CTranslate2) | ~5,8 GB |
> | `/root/.cache/torch` | **pyannote-Modelle** + Alignment (de) | ~0,4 GB |
> | `/root/.cache/whisper` | bleibt leer (nur für Rückweg auf openai_whisper) | — |
>
> Die pyannote-Modelle liegen also NICHT im Hugging-Face-Cache, sondern unter
> `torch/pyannote/`. Beim Download-Lauf zusätzlich
> `-v /tmp/torch-cache:/root/.cache/torch` mounten! Das vorhandene
> `.pt`-Modell unter `/opt/data/whisper-project/models` nutzt WhisperX nicht —
> es lädt large-v3 im CTranslate2-Format neu (daher die ~6 GB).

Danach die Verzeichnisse auf den Whisper-Host im Intranet kopieren
(z. B. per scp nach `/opt/data/whisper-project/hf-cache` und
`/opt/data/whisper-project/torch-cache`).

> Ein fertig heruntergeladenes, offline-verifiziertes Paket liegt unter
> `transfer/whisper-diarization/` (inkl. eigener README).

## Schritt 3: Compose-Datei anpassen

Änderungen gegenüber eurer bestehenden Datei sind markiert:

```yaml
version: '3.8'

services:
  whisper:
    image: registry.example.com/onerahmet/openai-whisper-asr-webservice:latest-gpu
    container_name: whisper-asr
    ports:
      - "11436:9000"
    volumes:
      - /opt/data/whisper-project/models:/root/.cache/whisper          # wie bisher, aber NICHT mehr :ro
      - /opt/data/whisper-project/hf-cache:/root/.cache/huggingface    # NEU: faster-whisper large-v3 (CTranslate2)
      - /opt/data/whisper-project/torch-cache:/root/.cache/torch       # NEU: pyannote + Alignment-Modelle
    environment:
      - ASR_MODEL=large-v3
      - ASR_ENGINE=whisperx                    # GEÄNDERT: vorher openai_whisper
      - HF_TOKEN=hf_IHR_TOKEN                  # NEU: Hugging-Face-Token (siehe Schritt 1)
      - ASR_VAD_FILTER=true
      - ASR_VAD_THRESHOLD=0.5
      # GPU-Limitierungen (unverändert)
      - CUDA_VISIBLE_DEVICES=0
      - NVIDIA_VISIBLE_DEVICES=0
    deploy:
      resources:
        limits:
          cpus: '4.0'
          memory: 8G
        reservations:
          cpus: '2.0'
          memory: 4G
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
              device_ids: ['2']
    restart: unless-stopped
```

Wichtige Punkte:

- **`:ro` beim Modell-Volume entfernen** — WhisperX legt beim ersten Start
  konvertierte Modell-Dateien ab.
- `HF_TOKEN` wird auch offline benötigt (pyannote prüft die Lizenz lokal
  gegen den Cache); mit vorgeladenem Cache findet aber kein Internetzugriff
  mehr statt.
- Danach: `docker compose down && docker compose up -d`.

## Schritt 4: Diarisierung im BBB-Bot aktivieren

Im Frontend unter **Admin → Einstellungen → Spracherkennung (Whisper)**:

- `whisper.diarize` auf `true` stellen — das Backend hängt dann bei jedem
  Transkriptions-Request `diarize=true` an.
- `whisper.output` muss auf `json` stehen (Standard) — nur dann liefert der
  Dienst Segment-Informationen inkl. Sprecher.

Das Backend hängt bei WhisperX-Antworten die Sprecher-Labels automatisch an
(`SPEAKER_00:`, `SPEAKER_01:`, … vor den jeweiligen Abschnitten). Eine
Zuordnung der generischen Labels zu echten Teilnehmernamen nimmt das LLM in
der Zusammenfassung vor, soweit der Kontext (Chat, Teilnehmerliste,
Selbstvorstellungen im Gespräch) das hergibt.

## Schritt 5: Testen

```bash
# Vom Backend-Host aus:
curl -F "audio_file=@beispiel.mp3" \
  "http://whisper-host:11436/asr?task=transcribe&language=de&output=json&diarize=true" | head -c 2000
```

Erwartung: JSON mit `segments[]`, jedes Segment enthält ein Feld
`"speaker": "SPEAKER_00"`. Fehlt das Feld, prüfen:

1. Läuft wirklich die whisperx-Engine? → `docker logs whisper-asr | head -50`
2. Ist `HF_TOKEN` gesetzt und sind die pyannote-Modelle im Cache?
   → `ls /opt/data/whisper-project/torch-cache/pyannote/` sollte
   `models--pyannote--speaker-diarization-3.1` u. ä. enthalten.

## Leistungs-Hinweise

- Diarisierung kostet zusätzlich GPU-Zeit (grob +30–50 % pro Datei). Da die
  Verarbeitung ohnehin im Nacht-Zeitfenster läuft, ist das unkritisch.
- WhisperX ist bei der reinen Transkription schneller als die bisherige
  `openai_whisper`-Engine — unterm Strich bleibt die Gesamtdauer ähnlich.
- Die Segmentlänge der Aufnahmen (Admin → Einstellungen → Aufnahme,
  Standard 10 Minuten) passt gut zur Diarisierung; sehr lange Dateien
  (>30 Min) verschlechtern Qualität und VRAM-Verbrauch.

## Rückweg

Falls Probleme auftreten: `ASR_ENGINE` wieder auf `openai_whisper` stellen
und den Container neu starten — das Backend funktioniert mit beiden Engines,
dann ohne Sprecher-Labels.
