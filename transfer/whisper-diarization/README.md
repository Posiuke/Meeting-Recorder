# WhisperX-Diarisierung: vorgeladene Modelle für die Offline-VM

Dieses Paket enthält alle Modelle, die der Whisper-Container mit
`ASR_ENGINE=whisperx` braucht (siehe docs/WHISPER_DIARIZATION.md).
Heruntergeladen am 2026-07-13 mit `onerahmet/openai-whisper-asr-webservice:latest`
und **offline verifiziert** (Container ohne Netzwerk, Diarize-Request liefert
Sprecher-Labels).

## Inhalt (tatsächliche Struktur — weicht von der urspr. Anleitung ab!)

| Verzeichnis | Größe | Inhalt |
|---|---|---|
| `hf-cache/` | ~5,8 GB | `models--Systran--faster-whisper-large-v3` (CTranslate2-Modell, das WhisperX statt des alten `.pt` nutzt) |
| `torch-cache/` | ~0,4 GB | `pyannote/` (speaker-diarization-3.1, segmentation-3.0, wespeaker-voxceleb) + `hub/checkpoints/` (Alignment Deutsch, VoxPopuli) |
| `whisper-models/` | leer | WhisperX nutzt dieses Verzeichnis NICHT — das vorhandene `models`-Verzeichnis auf dem Host bleibt für den Rückweg auf `openai_whisper` einfach unverändert |

Wichtige Erkenntnisse gegenüber docs/WHISPER_DIARIZATION.md:

1. Die pyannote-Modelle liegen im **Torch-Cache** (`/root/.cache/torch/pyannote/`),
   NICHT im Hugging-Face-Cache. Ohne das dritte Volume funktioniert die
   Diarisierung offline nicht.
2. Der HF-Cache ist ~6 GB (nicht 1–2 GB), weil WhisperX das large-v3-Modell im
   CTranslate2-Format (Systran/faster-whisper-large-v3) neu bezieht — das
   vorhandene `.pt`-Modell auf dem Host wird von WhisperX nicht verwendet.
3. Es ist nur das Alignment-Modell für **Deutsch** enthalten (Requests mit
   `language=de`). Für andere Sprachen müsste ein weiterer Request mit der
   jeweiligen Sprache auf einem Internet-Rechner laufen.

## Übertragen auf den Whisper-Host

```bash
scp -r hf-cache torch-cache user@whisper-host:/opt/data/whisper-project/
```

## Compose auf dem Whisper-Host

> **Fertige Datei:** `docker-compose.yml` in diesem Verzeichnis ist die
> vollständige, einsatzbereite Compose (inkl. GPU-Konfiguration des Servers) —
> nur noch `HF_TOKEN` eintragen.

```yaml
    volumes:
      - /opt/data/whisper-project/models:/root/.cache/whisper           # wie bisher (Rückweg-Fallback), ohne :ro
      - /opt/data/whisper-project/hf-cache:/root/.cache/huggingface     # NEU
      - /opt/data/whisper-project/torch-cache:/root/.cache/torch        # NEU (pyannote + Alignment!)
    environment:
      - ASR_MODEL=large-v3
      - ASR_ENGINE=whisperx
      - HF_TOKEN=hf_...   # euer Read-Token; mit vorgeladenem Cache kein Internetzugriff
```

## Verifikation nach dem Umzug

```bash
ls /opt/data/whisper-project/torch-cache/pyannote/   # models--pyannote--speaker-diarization-3.1 usw.
curl -F "audio_file=@beispiel.mp3" \
  "http://whisper-host:11436/asr?task=transcribe&language=de&output=json&diarize=true" | head -c 2000
# -> segments[] / words[] mit "speaker": "SPEAKER_00"
```

Danach im BBB-Bot-Frontend: Admin → Einstellungen → `whisper.diarize` auf `true`.
Das ist die **Freischaltung** — die Sprechererkennung selbst wählen die Nutzer
anschließend pro Aufnahme (Bot-Start bzw. Upload-Dialog, Checkbox
„Sprechererkennung").
