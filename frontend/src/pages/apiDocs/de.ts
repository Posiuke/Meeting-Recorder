import type { ApiDocs } from './index';

/** Deutsche Fassung des API-Hilfebereichs. Struktur siehe `./index.ts`. */
export const apiDocsDe: ApiDocs = {
  quickstart: {
    intro:
      'Die API kann alles, was diese Weboberfläche kann – es sind dieselben Endpunkte. Sie brauchen nur einen Schlüssel (oben anlegen) und geben ihn bei jedem Aufruf im Header mit. Antworten sind JSON, Zeitangaben ISO-8601 in UTC.',
    baseUrlLabel: 'Basis-URL dieser Installation',
    example: `export BBB="https://bbb.example.intern"
export KEY="bbb_..."

# Aufnahmen auflisten
curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings"

# Zusammenfassung einer Aufnahme lesen
curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings/<id>/summary"

# Datei transkribieren und auf das Ergebnis warten
curl -s -H "X-API-Key: $KEY" -F file=@notiz.m4a \\
  "$BBB/api/transcriptions?wait=300"`,
  },

  auth: {
    title: 'Anmeldung mit dem Schlüssel',
    text:
      'Der Schlüssel gehört in den Header X-API-Key. Alternativ geht auch Authorization: Bearer bbb_… – am Präfix bbb_ unterscheidet der Server ihn von einem Login-Token. Es gibt keine Sitzung: Der Schlüssel gilt, bis er widerrufen wird oder sein Ablaufdatum erreicht ist.',
    notes: [
      'Ein Schlüssel kann nie mehr als Sie selbst: fremde Aufnahmen bleiben unsichtbar, Admin-Endpunkte nur mit Admin-Recht.',
      'Ein Nur-Lese-Schlüssel darf ausschließlich GET. Jede andere Methode wird mit 403 abgewiesen.',
      'Nicht per Schlüssel möglich: die Schlüsselverwaltung (/api/api-keys) und der Passwortwechsel. Beides geht nur angemeldet in der Weboberfläche – so beendet ein Widerruf den Zugang wirklich.',
      'Der Schlüssel steht nur beim Anlegen in der Antwort. Gespeichert ist ausschließlich sein Abdruck; verloren heißt neu anlegen.',
    ],
  },

  sections: [
    {
      id: 'recordings',
      title: 'Aufnahmen',
      intro:
        'Aufnahmen aus Bot-Sitzungen, Uploads und Bildschirmaufnahmen. Sichtbar sind eigene und mit Ihnen geteilte.',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings',
          summary: 'Aufnahmen auflisten und durchsuchen.',
          params: [
            { name: 'q', description: 'Suchbegriff für Titel, Raumname, Meeting-URL und Schlagworte' },
            { name: 'tag', description: 'nur Aufnahmen mit diesem Schlagwort' },
            { name: 'content', description: 'true = zusätzlich Transkript und Zusammenfassung durchsuchen' },
          ],
          example: `curl -s -H "X-API-Key: $KEY" \\
  "$BBB/api/recordings?q=technik&content=true"`,
          response: `[
  {
    "id": "8f14e45f-...",
    "title": "Wochenbesprechung Technik",
    "status": "DONE",
    "startedAt": "2026-07-21T08:00:12Z",
    "durationMs": 3612000,
    "source": "BOT",
    "tags": ["Projekt Nord"],
    "mine": true
  }
]`,
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}',
          summary:
            'Eine Aufnahme mit Segmenten, Zusammenfassungen, Jobs und Teilnehmern. Die Segment-IDs brauchen Sie für den Audio-Download.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID"`,
        },
        {
          method: 'POST',
          path: '/api/recordings/upload',
          summary:
            'Bestehende Audio-/Videodatei als Aufnahme übernehmen (multipart/form-data). Für „nur Transkript" ist /api/transcriptions bequemer.',
          params: [
            { name: 'file', description: 'die Datei (mp3, wav, m4a, mp4, mkv, …)' },
            { name: 'title', description: 'Titel; leer = Dateiname' },
            { name: 'aiAnalysis', description: 'true (Standard) = transkribieren und zusammenfassen' },
            { name: 'processNow', description: 'true = sofort auswerten statt im Nachtfenster' },
            { name: 'diarize', description: 'true = Sprechererkennung (falls freigeschaltet)' },
            {
              name: 'summaryPrompt',
              description:
                'Auswertungs-Prompt für diese Aufnahme (max. 8000 Zeichen); leer = Standardvorgabe des Administrators. Wirkt schon bei der ersten Auswertung – auch bei processNow=true.',
            },
          ],
          example: `curl -s -H "X-API-Key: $KEY" \\
  -F file=@besprechung.mp3 -F title="Jour Fixe" -F processNow=true \\
  "$BBB/api/recordings/upload"`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}',
          summary: 'Aufnahme samt Dateien löschen (nur Besitzer). Nicht umkehrbar.',
          example: `curl -s -X DELETE -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID"`,
        },
        {
          method: 'GET',
          path: '/api/recordings/tags',
          summary: 'Alle sichtbaren Schlagworte mit Anzahl der Aufnahmen.',
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/tags',
          summary: 'Schlagwort setzen (nur Besitzer). Antwort: die neue Liste.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"name":"Projekt Nord"}' "$BBB/api/recordings/$ID/tags"`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}/tags?name=…',
          summary: 'Schlagwort entfernen (nur Besitzer).',
        },
      ],
    },

    {
      id: 'transcripts',
      title: 'Transkripte',
      intro:
        'Zu jeder Aufnahme gibt es das Whisper-Original und – wenn die KI-Glättung gelaufen ist – die geglättete Fassung, in der Ihr Glossar berücksichtigt wurde.',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings/{id}/transcript',
          summary: 'Transkript in beiden Fassungen, als Text und als Einträge mit Zeitstempel.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID/transcript"`,
          response: `{
  "transcript": "[00:05] ähm also guten morgen ...",
  "correctedTranscript": "[00:05] Guten Morgen ...",
  "hasCorrected": true,
  "correctionStatus": "READY",
  "entries": [
    { "startSeconds": 5, "speaker": "SPEAKER_00", "text": "Guten Morgen ..." }
  ]
}`,
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/transcript/download',
          summary:
            'Transkript als Datei zum Speichern – wahlweise die geglättete oder die Original-Fassung, als Markdown oder als Word-Datei (.doc). Der Dateiname enthält Fassung, Aufnahmedatum und Kurz-Kennung.',
          params: [
            { name: 'variant', description: 'corrected (Standard) = geglättet, original = Whisper-Rohfassung' },
            { name: 'format', description: 'md (Standard) oder doc für die Word-Fassung' },
          ],
          example: `curl -s -H "X-API-Key: $KEY" -OJ \\
  "$BBB/api/recordings/$ID/transcript/download?variant=original&format=doc"`,
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/transcribe',
          summary:
            'Nur transkribieren (Schritt 1), ohne Zusammenfassung. Läuft sofort. Antwort: der Job.',
          example: `curl -s -X POST -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID/transcribe"`,
          response: `{ "id": "3d2b...", "status": "PENDING", "immediate": true, "transcribeOnly": true }`,
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/retranscribe',
          summary:
            'Transkription verwerfen und neu erstellen – z.B. nach einer Whisper-Umstellung. Eine vorhandene Glättung wird ebenfalls neu gemacht.',
        },
        {
          method: 'PUT',
          path: '/api/recordings/{id}/participants/{participantId}',
          summary:
            'Erkannten Sprecher umbenennen (nur Besitzer). Wirkt in der Anzeige, in transcript.md und in künftigen Zusammenfassungen.',
          example: `curl -s -X PUT -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"displayName":"Frau Meier"}' \\
  "$BBB/api/recordings/$ID/participants/$PID"`,
        },
      ],
    },

    {
      id: 'summaries',
      title: 'Zusammenfassungen',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings/{id}/summary',
          summary: 'Neueste Zusammenfassung als Markdown. 404, wenn es noch keine gibt.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID/summary"`,
          response: `{
  "id": "b21f...",
  "status": "DONE",
  "markdown": "# Wochenbesprechung Technik\\n\\n## Ergebnisse\\n- ...",
  "model": "qwen2.5-32b-instruct",
  "finishedAt": "2026-07-22T02:14:51Z"
}`,
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/summary/download',
          summary:
            'Dieselbe Zusammenfassung als Datei zum Speichern – als Markdown oder als Word-Datei (.doc), die Word und LibreOffice direkt öffnen.',
          params: [
            { name: 'format', description: 'md (Standard) oder doc für die Word-Fassung' },
          ],
          example: `curl -s -H "X-API-Key: $KEY" -OJ \\
  "$BBB/api/recordings/$ID/summary/download?format=doc"`,
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/process',
          summary:
            'Jetzt auswerten: transkribieren (falls nötig) und zusammenfassen, ohne auf das Nachtfenster zu warten.',
          example: `curl -s -X POST -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID/process"`,
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/reprocess',
          summary:
            'Erneut auswerten: vorhandene Transkripte bleiben, nur die Zusammenfassung wird neu erstellt und ersetzt die alte.',
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/summary-options',
          summary:
            'Auswertung anpassen: eigener Prompt, Wortzahl, Sprache. Gilt für die nächste Auswertung dieser Aufnahme.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"prompt":"Nur Beschlüsse und Aufgaben.","maxWords":300,"language":"de"}' \\
  "$BBB/api/recordings/$ID/summary-options"`,
        },
        {
          method: 'PUT',
          path: '/api/recordings/{id}/summaries/{summaryId}',
          summary: 'Zusammenfassung händisch überschreiben (nur Besitzer).',
          example: `curl -s -X PUT -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"markdown":"# Ergebnis\\n- Beschluss A"}' \\
  "$BBB/api/recordings/$ID/summaries/$SID"`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}/summaries/{summaryId}',
          summary: 'Eine Zusammenfassung löschen (nur Besitzer).',
        },
      ],
    },

    {
      id: 'transcriptions',
      title: 'Direkt transkribieren',
      intro:
        'Datei hin, Transkript zurück – ohne Zusammenfassung. Der Auftrag läuft über dieselbe Strecke wie ein Upload (Transkodierung, Whisper, KI-Glättung mit Ihrem Glossar) und ist deshalb nicht sofort fertig: Entweder Sie warten mit wait= oder Sie holen das Ergebnis später ab. Jeder Auftrag ist eine normale Aufnahme in Ihrem Konto und kann anschließend gelöscht werden.',
      endpoints: [
        {
          method: 'POST',
          path: '/api/transcriptions',
          summary:
            'Transkription starten (multipart/form-data). Antwort 202 mit der Auftrags-ID – oder 200 mit dem Transkript, wenn es innerhalb von wait fertig wurde.',
          params: [
            { name: 'file', description: 'Audio- oder Videodatei (mp3, wav, m4a, mp4, mkv, …)' },
            { name: 'title', description: 'Titel der entstehenden Aufnahme; leer = Dateiname' },
            { name: 'diarize', description: 'true = Sprechererkennung (falls freigeschaltet)' },
            { name: 'wait', description: 'Sekunden warten (0 = sofort antworten, max. 600)' },
          ],
          example: `# kurze Datei: in einem Aufruf
curl -s -H "X-API-Key: $KEY" -F file=@notiz.m4a \\
  "$BBB/api/transcriptions?wait=300"

# lange Aufnahme: starten und später abholen
curl -s -H "X-API-Key: $KEY" -F file=@besprechung.mp4 \\
  "$BBB/api/transcriptions"`,
          response: `{ "id": "a1b2c3d4-...", "status": "PENDING" }`,
        },
        {
          method: 'GET',
          path: '/api/transcriptions/{id}',
          summary:
            'Zustand und – sobald fertig – das Transkript. status ist PENDING, RUNNING, DONE oder FAILED; wait= wartet auch hier.',
          params: [{ name: 'wait', description: 'Sekunden warten (0 = sofort antworten, max. 600)' }],
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/transcriptions/$ID?wait=60"`,
          response: `{
  "id": "a1b2c3d4-...",
  "status": "DONE",
  "durationMs": 187000,
  "text": "[00:00] Guten Morgen, wir fangen an ...",
  "entries": [
    { "startSeconds": 0, "speaker": null, "text": "Guten Morgen, wir fangen an ..." }
  ]
}`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}',
          summary:
            'Aufräumen: Wenn Sie das Transkript haben und die Aufnahme nicht behalten wollen, löschen Sie sie mit derselben ID.',
          example: `curl -s -X DELETE -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID"`,
        },
      ],
    },

    {
      id: 'glossary',
      title: 'Glossar',
      intro:
        'Ihre Abkürzungen und Fachbegriffe. Sie gehen in die KI-Glättung Ihrer Transkripte ein – auch bei der Direkt-Transkription.',
      endpoints: [
        {
          method: 'GET',
          path: '/api/glossary',
          summary: 'Alle eigenen Einträge, alphabetisch.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/glossary"`,
          response: `[
  { "id": "c7f1...", "term": "RZ", "meaning": "Rechenzentrum", "createdAt": "2026-07-20T09:12:00Z" }
]`,
        },
        {
          method: 'POST',
          path: '/api/glossary',
          summary: 'Begriff anlegen. 409, wenn er (unabhängig von Groß-/Kleinschreibung) schon existiert.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"term":"RZ","meaning":"Rechenzentrum"}' "$BBB/api/glossary"`,
        },
        {
          method: 'PUT',
          path: '/api/glossary/{id}',
          summary: 'Begriff oder Bedeutung ändern.',
          example: `curl -s -X PUT -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"term":"RZ","meaning":"Rechenzentrum Nord"}' "$BBB/api/glossary/$GID"`,
        },
        {
          method: 'DELETE',
          path: '/api/glossary/{id}',
          summary: 'Eintrag löschen.',
        },
        {
          method: 'GET',
          path: '/api/glossary/export',
          summary: 'Ganzes Glossar als CSV (Begriff;Bedeutung, UTF-8 mit BOM).',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/glossary/export" -o glossar.csv`,
        },
        {
          method: 'POST',
          path: '/api/glossary/import',
          summary:
            'CSV einlesen (multipart/form-data, Feld file). Führt zusammen: vorhandene Begriffe werden aktualisiert, neue angelegt, nichts gelöscht.',
          example: `curl -s -H "X-API-Key: $KEY" -F file=@glossar.csv \\
  "$BBB/api/glossary/import"`,
          response: `{ "created": 12, "updated": 3, "unchanged": 40, "skipped": 1,
  "warnings": ["Zeile 8: kein Begriff angegeben"] }`,
        },
      ],
    },

    {
      id: 'bots',
      title: 'Bots und Aufnahmesteuerung',
      intro:
        'Ein Bot tritt einem BigBlueButton-Raum bei und nimmt den Ton auf. Die Raum-URL ist die fertige Einladungs-URL, wie Sie sie im Browser öffnen würden.',
      endpoints: [
        {
          method: 'GET',
          path: '/api/bots',
          summary: 'Aktive Bots mit Zustand, Teilnehmerzahl und laufender Aufnahme.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/bots"`,
        },
        {
          method: 'POST',
          path: '/api/bots',
          summary: 'Bot starten.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"meetingUrl":"https://bbb.example.intern/b/abc-def-ghi",
       "botName":"Protokoll-Bot","autoRecord":true,
       "recordVideo":false,"aiAnalysis":true,"diarize":false}' \\
  "$BBB/api/bots"`,
          response: `{ "sessionId": "9a7c...", "status": "STARTING", "roomName": "Technikrunde" }`,
        },
        {
          method: 'POST',
          path: '/api/bots/{sessionId}/recording/start',
          summary: 'Aufnahme starten.',
        },
        {
          method: 'POST',
          path: '/api/bots/{sessionId}/recording/stop',
          summary: 'Aufnahme beenden. Mit ?discard=true wird sie verworfen statt ausgewertet.',
          example: `curl -s -X POST -H "X-API-Key: $KEY" \\
  "$BBB/api/bots/$SID/recording/stop"`,
        },
        {
          method: 'DELETE',
          path: '/api/bots/{sessionId}',
          summary: 'Bot stoppen; eine laufende Aufnahme wird abgeschlossen.',
        },
        {
          method: 'GET',
          path: '/api/bots/history',
          summary: 'Beendete Bot-Sitzungen mit Zeitraum und Fehlern.',
        },
      ],
    },

    {
      id: 'sharing',
      title: 'Teilen und Gruppen',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings/{id}/shares',
          summary: 'Bestehende Freigaben einer Aufnahme.',
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/shares',
          summary: 'Mit einem Nutzer oder einer Gruppe teilen – genau eines von userId/groupId angeben.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"userId":"4f2a..."}' "$BBB/api/recordings/$ID/shares"`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}/shares/{shareId}',
          summary: 'Freigabe zurücknehmen.',
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/share-links',
          summary:
            'Öffentliche Freigabe-Links dieser Aufnahme (nur Besitzer) – mit Ablauf und Zahl der Aufrufe.',
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/share-links',
          summary:
            'Freigabe-Link erzeugen (nur Besitzer). Standard ist kontogebunden: Der Empfänger meldet sich an und bekommt die Aufnahme dabei automatisch freigegeben. Mit requireLogin=false entsteht ein Link, der ohne Anmeldung Video, Audio, Transkript und Zusammenfassung zeigt. Ohne expiresInDays gilt der Link bis zum Widerruf. Die Adresse lautet <Basis-URL>/share/<token>.',
          params: [
            { name: 'expiresInDays', description: 'Laufzeit in Tagen (1–3650); weglassen = bis zum Widerruf' },
            {
              name: 'requireLogin',
              description:
                'true (Standard) = Anmeldung nötig, Freigabe wird dabei erteilt; false = Zugriff ohne Anmeldung. Hat der Admin sharing.publicLinks abgeschaltet, wird false mit 409 abgewiesen.',
            },
          ],
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"expiresInDays":30}' "$BBB/api/recordings/$ID/share-links"`,
          response: `{
  "id": "0c9d1e2f-...",
  "token": "brqk6JmNhZf9oO55m_98lBOZoRskgzRh7K3fBMY12Ok",
  "createdAt": "2026-08-12T13:04:38Z",
  "expiresAt": "2026-09-11T13:04:38Z",
  "expired": false,
  "views": 0,
  "lastViewedAt": null,
  "requiresLogin": true
}`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}/share-links/{linkId}',
          summary: 'Freigabe-Link widerrufen – die Adresse ist sofort ungültig.',
        },
        {
          method: 'POST',
          path: '/api/share-links/{token}/claim',
          summary:
            'Kontogebundenen Freigabe-Link einlösen: Die Aufnahme wird mit dem angemeldeten Nutzer geteilt (mehrfaches Einlösen erzeugt nur eine Freigabe); zurück kommt ihre Kennung. Bei einem Link ohne Anmeldepflicht wird bewusst keine Freigabe angelegt.',
          example: `curl -s -X POST -H "X-API-Key: $KEY" \\
  "$BBB/api/share-links/$SHARE_TOKEN/claim"`,
          response: `{"recordingId":"8f14e45f-...","title":"Jour Fixe","shared":true}`,
        },
        {
          method: 'GET',
          path: '/api/share-links/config',
          summary: 'Darf auf diesem Server ohne Anmeldung geteilt werden? {"publicLinksAllowed":true}',
        },
        {
          method: 'GET',
          path: '/api/public/shares/{token}',
          summary:
            'Inhalt eines Freigabe-Links: Kopfdaten, Audio-Segmente, Transkript und Zusammenfassung. Braucht KEINEN Schlüssel – dazu /video, /video/download, /segments/{segmentId}/audio und /summary/download. Unbekannte, abgelaufene und widerrufene Tokens antworten gleich mit 404; ein kontogebundener Link mit 403 (dann über /api/share-links/{token}/claim einlösen).',
          example: `curl -s "$BBB/api/public/shares/$SHARE_TOKEN"`,
        },
        {
          method: 'GET',
          path: '/api/groups',
          summary: 'Eigene Gruppen und Gruppen, in denen Sie Mitglied sind.',
        },
        {
          method: 'POST',
          path: '/api/groups',
          summary: 'Gruppe anlegen.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"name":"Technik"}' "$BBB/api/groups"`,
        },
        {
          method: 'GET',
          path: '/api/groups/{groupId}/members',
          summary: 'Mitglieder einer Gruppe.',
        },
        {
          method: 'POST',
          path: '/api/groups/{groupId}/members',
          summary: 'Mitglied hinzufügen: {"userId":"…"}.',
        },
        {
          method: 'DELETE',
          path: '/api/groups/{groupId}/members/{userId}',
          summary: 'Mitglied entfernen.',
        },
        {
          method: 'GET',
          path: '/api/users/search?q=…',
          summary: 'Nutzer suchen (ab 2 Zeichen) – liefert die IDs für Freigaben und Gruppen.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/users/search?q=mei"`,
        },
      ],
    },

    {
      id: 'templates',
      title: 'Promptvorlagen',
      intro:
        'Eigene Auswertungs-Prompts, die Sie beim Auswerten wiederverwenden – im Frontend im Tab „Vorlagen" pflegbar.',
      endpoints: [
        { method: 'GET', path: '/api/prompt-templates', summary: 'Eigene Vorlagen auflisten.' },
        {
          method: 'GET',
          path: '/api/prompt-templates/default-prompt',
          summary: 'Standardvorgabe des Administrators – Ausgangspunkt für eigene Vorlagen.',
          response: `{ "prompt": "Du bist ein Assistent, der …" }`,
        },
        {
          method: 'POST',
          path: '/api/prompt-templates',
          summary: 'Vorlage anlegen.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"name":"Nur Aufgaben","prompt":"Liste ausschließlich Aufgaben mit Zuständigkeit."}' \\
  "$BBB/api/prompt-templates"`,
        },
        { method: 'PUT', path: '/api/prompt-templates/{id}', summary: 'Vorlage ändern.' },
        { method: 'DELETE', path: '/api/prompt-templates/{id}', summary: 'Vorlage löschen.' },
      ],
    },

    {
      id: 'media',
      title: 'Audio und Video',
      intro:
        'Die Segment-IDs stehen in der Detailantwort einer Aufnahme (GET /api/recordings/{id}).',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings/{id}/segments/{segmentId}/audio',
          summary: 'Ein Audiosegment als MP3.',
          example: `curl -s -H "X-API-Key: $KEY" \\
  "$BBB/api/recordings/$ID/segments/$SEGID/audio" -o segment.mp3`,
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/video',
          summary: 'Meeting-Video als MP4 (nur wenn die Aufnahme eines hat).',
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/video/download',
          summary: 'Dasselbe Video mit Download-Namen.',
        },
      ],
    },

    {
      id: 'account',
      title: 'Konto und Verwaltung',
      endpoints: [
        {
          method: 'GET',
          path: '/api/auth/me',
          summary: 'Wer bin ich? Guter erster Aufruf, um einen Schlüssel zu prüfen.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/auth/me"`,
          response: `{ "id": "5d34...", "username": "m.mustermann", "admin": false, "local": false }`,
        },
        {
          method: 'PUT',
          path: '/api/users/me/language',
          summary: 'Sprache des eigenen Kontos setzen: {"language":"de"} oder {"language":"en"}.',
        },
        {
          method: 'GET',
          path: '/api/admin/settings',
          summary:
            'Alle Einstellungen (Whisper, LLM, Zeitfenster, Bots) – nur mit Admin-Recht. Änderungen per PUT mit demselben Format.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/admin/settings"`,
        },
        {
          method: 'GET',
          path: '/api/admin/users',
          summary:
            'Alle bekannten Nutzer – nur mit Admin-Recht. Je Nutzer zusätzlich lastSeenAt/online (Aktivität im Frontend, Fenster 5 Minuten) und activeRecordings (gerade laufende Aufnahmen).',
          response: `[{ "username": "m.mustermann", "online": true,
   "lastSeenAt": "2026-08-13T09:12:44Z",
   "activeRecordings": [{ "id": "5d34…", "status": "RECORDING",
                          "source": "CAPTURE", "startedAt": "2026-08-13T09:03:00Z" }] }]`,
        },
      ],
    },
  ],

  errors: {
    title: 'Fehler und Statuscodes',
    intro:
      'Fehler kommen immer als JSON mit dem Feld message und einer Begründung in Klartext. Werten Sie den Statuscode aus, die Meldung ist für Menschen gedacht.',
    rows: [
      { code: '200 / 202', meaning: 'Erfolg. 202 heißt: angenommen, läuft noch (Transkription).' },
      { code: '400', meaning: 'Anfrage unbrauchbar – message nennt den Grund (fehlendes Feld, falsches Format, nicht unterstützter Dateityp).' },
      { code: '401', meaning: 'Kein, unbekannter, widerrufener oder abgelaufener Schlüssel.' },
      { code: '403', meaning: 'Kein Recht: fremde Aufnahme, Admin-Endpunkt ohne Admin-Recht, Nur-Lese-Schlüssel bei schreibendem Aufruf, oder ein per Schlüssel gesperrter Bereich.' },
      { code: '404', meaning: 'Nicht vorhanden – oder für Sie nicht sichtbar.' },
      { code: '409', meaning: 'Konflikt: z.B. Begriff steht schon im Glossar, Verarbeitung läuft bereits.' },
      { code: '413', meaning: 'Datei größer als das serverseitige Upload-Limit.' },
      { code: '500', meaning: 'Serverfehler. Steht im Server-Log; message nennt die technische Ursache.' },
    ],
    example: `{ "message": "Dieser API-Schluessel darf nur lesen (POST nicht erlaubt)" }`,
  },
};
