Ich möchte, dass du mir dieses Repo komplett neu umbaust. Dieses Repo enthält ein Bot, welcher Aufzeichnungen von Bigbluebutton macht,
und diese mithilfe von KI auswertet um so eine Zusammenfassung eines Meetings zu geben.
Schau dir die bisherigen Dateien in diesem Repo an, um zu verstehen, wie das bisher gemacht wird. Schau dir auch an, welche Features es bisher hat.
Diese sollen bis auf Ausnahmen, welche ich dir sage auch weiter so enthalten sein.
Wichtig ist hierbei auch ganz besonders zu verstehen, wie das Tool in den Meetingroom joint, da die Bigbluebutton Umgebung in unserem Intranet etwas anders
arbeitet, als die im Internet.

Die neue Version des BigBlugButton Bots soll deutlich stabiler werden und ein Backend und Frontend erhalten. 

Als Architektur möchte ich Java als Backend und Redux im Frontend nutzen
Was sollen die beiden machen?

# Backend
Das Backend soll sinnvolle Logs produzieren
Das Backend soll die Verbindungen zu Whisper und dem KI Modell halten
Das Backend soll die Verbindungen zu BBB halten und sich um die Aufnahmen kümmern
Das Backend soll die Aufnahmen und Zusammenfassungen verwalten
Das Backend soll mehrere Bots gleichzeitig steuern können
Das Backend hält alle wichtigen Properties
Über das Backend sollen Admins definiert werden können

# Frontend
Das Frontend soll an ein LDAP angebunden werden
Angemeldete Nutzer sollen steuern können, mit welchem BBB Raum sich verbunden werden sol
Nutzer können über die Oberfläche steuern, wann aufgenommen werden soll und wann die aufnahme beendet wird
Nutzer können über das Frontend die Aufnahmen direkt anhören/runterladen/löschen
Nutzer können über das Frontend die Zusammenfassung ansehene/herunterladen/löschen
Man soll über das Frontend die Parameter optimieren können, mit der die Auswertung und der STT gemacht werden.
Nutzer können ihre Aufnahmen und Zusammenfassungen mit anderen Nutzern/Gruppen teilen
Nutzer können Gruppen erstellen, wo sie leute einladen können
Der Admin soll die Möglichkeit haben, den Zeitraum einzustellen, wann der STT und die Auswertung passiert, damit tagsüber ressourcen geschont werden.

# Stabilität
Bezüglich der Stabilität. In der Version, welche jetzt läuft, ist alles sehr instabil.
Was Problematisch war, sind die Aufnahmen, welche manchmal nicht richtig funktionieren.
Was noch ein größereres Problem war, dass die Aufnahmen durch den STT nicht sauber umgewandelt wurden, wodurch die Zusammenfassung brüchig war.
Weiterhin funktionierte das Hochladen auf Git nicht korrekt. Diese funktionalität kann aber entfernt werden, da die Nutzer über das Frontend alles
ansehen und herunterladen können.

# Parameter
Gut wäre, wenn du vielleicht selber mal prüfst, mit welchen Parametern unser Whisper das beste ergebnis liefert. Ich glaube z.B., dass Aufnahmen, welche über 30 Minuten
gehen nicht so vorteilhaft sind. Aber das bekommst du sicher alles raus.

# Verwendete Modelle
## Whisper
Hier das Composefile von von whisper:
version: '3.8'

services:
  whisper:
    image: registry.example.com/onerahmet/openai-whisper-asr-webservice:latest-gpu
    container_name: whisper-asr
    ports:
      - "11436:9000"
    volumes:
      - /opt/data/whisper-project/models:/root/.cache/whisper:ro
    environment:
      - ASR_MODEL=large-v3
      - ASR_ENGINE=openai_whisper
      - ASR_VAD_FILTER=true
      - ASR_VAD_THRESHOLD=0.5
      # GPU-Limitierungen
      - CUDA_VISIBLE_DEVICES=0                    # Nur GPU 0 verwenden
      - NVIDIA_VISIBLE_DEVICES=0                  # Alternative Syntax
     # - CUDA_MPS_ACTIVE_THREAD_PERCENTAGE=50     # Max 50% GPU-Rechenleistung
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

## LLM
Für die Auswertung benutzen wir das Modell Qwen3.5-122B, welches auf einem vllm-openai Container läuft

# Optional
Was gut wäre, wenn es möglich wäre das STT mit den Sprechern zu matchen, sodass man sehen kann, wer was gesagt hat.

Auch interessant wäre die Prüfung, ob mithilfe von Mermaid eine grafische Darstellung von Theman aus dem Meeting möglich ist.

Ein weiteres interessantes Feature wäre es, wenn man im Frontend zusätzlich auswählen kann, dass ein Video von dem ganzen gemacht werden soll,
welches man später über das Frontend abspielen kann.
