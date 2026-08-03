# Bildschirmaufnahme direkt im Tool

Neben dem Bot im Meeting und dem Datei-Upload können Nutzer ihren eigenen
Bildschirm samt Ton direkt in der Weboberfläche aufnehmen: **Aufnahmen →
Bildschirm aufnehmen**. Das Ergebnis landet als ganz normale Aufnahme in der
Liste und durchläuft dieselbe Auswertung (Whisper + Zusammenfassung).

Gedacht ist das für alles, wo der Bot nicht hinkommt: Teams-/Zoom-/WebEx-
Termine, Präsenzsitzungen mit dem Laptop auf dem Tisch, lokale Vorführungen.
Für reine BigBlueButton-Räume bleibt der Bot die bessere Wahl — er belegt
keinen Arbeitsplatzrechner.

---

## Voraussetzungen

| Voraussetzung | Warum |
|---|---|
| **HTTPS** (oder `localhost`) | Die Browser-Schnittstelle `getDisplayMedia` existiert nur im sicheren Kontext. Über `http://` fehlt sie ersatzlos — die Funktion lässt sich dann gar nicht erst starten. |
| **Chrome oder Edge** | Nur diese Browser schneiden unter Windows den **Systemton** mit. Firefox kann Bildschirme teilen, aber keinen Systemton aufnehmen. |
| **`capture.enabled` = true** | Admin-Freischaltung unter *Administration → Einstellungen → Bildschirmaufnahme* (Standard: an). |

Fehlt eine der Voraussetzungen, erklärt der Dialog im Klartext, woran es liegt.

---

## Reverse Proxy einrichten

Die Anwendung selbst spricht weiter HTTP im Container; die TLS-Terminierung
übernimmt der Reverse Proxy. Wichtig sind drei Dinge: ein Zertifikat, dem die
Clients vertrauen (interne CA), die `X-Forwarded-*`-Header und ein Proxy, der
den laufenden Datenstrom nicht künstlich zwischenpuffert.

Das Backend wertet die Header bereits aus — in `application.yml` steht dafür
`server.forward-headers-strategy: framework`.

### nginx

```nginx
server {
    listen 443 ssl;
    server_name bbb-recorder.intern.example;

    ssl_certificate     /etc/ssl/certs/bbb-recorder.crt;   # von der internen CA
    ssl_certificate_key /etc/ssl/private/bbb-recorder.key;

    location / {
        proxy_pass http://127.0.0.1:8090;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;   # ohne das hält sich die App für "http"

        # Datei-Uploads (Standard 4 GB, siehe MAX_UPLOAD_SIZE)
        client_max_body_size 4g;

        # Die Aufnahme wird stückweise gesendet: durchreichen statt erst
        # komplett auf die Proxy-Platte schreiben.
        proxy_request_buffering off;

        # Lange Auswertungen und Downloads nicht abschneiden
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }
}

# HTTP nur noch zum Weiterleiten - über http:// ist keine Aufnahme möglich
server {
    listen 80;
    server_name bbb-recorder.intern.example;
    return 301 https://$host$request_uri;
}
```

### Apache httpd

```apache
<VirtualHost *:443>
    ServerName bbb-recorder.intern.example

    SSLEngine on
    SSLCertificateFile    /etc/ssl/certs/bbb-recorder.crt
    SSLCertificateKeyFile /etc/ssl/private/bbb-recorder.key

    ProxyPreserveHost On
    RequestHeader set X-Forwarded-Proto "https"

    ProxyPass        / http://127.0.0.1:8090/
    ProxyPassReverse / http://127.0.0.1:8090/

    LimitRequestBody 4294967296
    ProxyTimeout 600
</VirtualHost>
```

### Prüfen

```bash
# Muss das Zertifikat der internen CA zeigen und 200 liefern
curl -I https://bbb-recorder.intern.example/actuator/health
```

Im Browser zusätzlich auf der Seite `https://…` die Konsole öffnen und
`window.isSecureContext` eingeben — kommt `true`, ist alles bereit. Ein
Zertifikat, dem der Client **nicht** vertraut, reicht nicht: Chrome behandelt
die Seite dann als unsicher und blockiert die Medienzugriffe weiterhin.

---

## Ablauf für Nutzer

1. **Aufnahmen → Bildschirm aufnehmen.**
2. Optionen setzen: Titel, „Bild aufzeichnen" (aus = nur Ton, viel kleinere
   Dateien), Bildqualität, Mikrofon, KI-Auswertung, Sofort auswerten,
   Sprechererkennung.
3. **Quelle auswählen** öffnet den Auswahldialog des Browsers. Dort
   **„Systemaudio übertragen"** (ganzer Bildschirm) bzw. **„Audio des Tabs
   teilen"** (Tab) ankreuzen — sonst bleibt die Besprechung stumm. Der Dialog
   meldet anschließend deutlich, ob Ton ankommt.
4. **Aufnahme starten.** Laufzeit, übertragene Menge und die Aussteuerung
   beider Tonquellen sind sichtbar; Pause und Fortsetzen sind möglich.
5. **Aufnahme beenden** — oder die Freigabe über die Leiste des Browsers
   beenden, das zählt ebenfalls als reguläres Ende.

Der Tab muss offen bleiben: Die Aufnahme entsteht im Browser, nicht auf dem
Server. Beim Versuch, das Fenster zu schließen, warnt der Browser.

### Größenordnung

| Einstellung | Bedarf |
|---|---|
| Nur Ton | ca. 60 MB pro Stunde |
| Bild, Standard (10 Bilder/s) | ca. 0,5 GB pro Stunde |
| Bild, Hoch (25 Bilder/s) | ca. 1,1 GB pro Stunde |

Für die KI-Auswertung reicht der Ton — „nur Ton" ist im Zweifel die bessere
Wahl.

---

## Wie es technisch läuft

`getDisplayMedia` liefert Bild und (bei gesetztem Haken) den Systemton,
`getUserMedia` optional das Mikrofon. Beide Tonquellen werden im Browser über
einen WebAudio-Graph zu **einer** Spur gemischt — eine Datei trägt praktisch
nur eine Tonspur, und zwei parallele Recorder würden gegeneinander driften.
Ein `MediaRecorder` erzeugt daraus alle 5 Sekunden ein Stück, das sofort
hochgeladen wird.

Der Server hängt die Stücke unverändert an eine Datei `capture.webm` im
Aufnahme-Verzeichnis an. Das funktioniert, weil die Stücke **eines**
MediaRecorder-Laufs zusammen einen gültigen WebM-Strom ergeben (nur das erste
trägt die Header). Deshalb ist die Reihenfolge zwingend: Ein fehlendes Stück
würde die Datei zerstören, ein Sprung wird darum abgelehnt. Wiederholt
gesendete Stücke (Netz-Retry nach verlorener Antwort) erkennt der Server an der
Sequenznummer und verwirft sie.

Beim Stoppen geht die Datei in dieselbe Verarbeitungsstrecke wie ein Upload:
ffmpeg schneidet sie in MP3-Segmente (`recording.segmentMinutes`), erkennt einen
Video-Anteil und stellt ihn zusätzlich als `meeting.mp4` bereit, danach läuft
die normale Auswertung.

**Nichts geht verloren, wenn der Browser wegbricht.** Alles bis zum letzten
bestätigten Stück liegt bereits auf dem Server. Kommen `capture.staleMinutes`
lang keine Daten mehr (Tab zu, Rechner zugeklappt, Netz weg), schließt ein
Aufräumlauf die Aufnahme selbst ab und wertet das Übertragene aus — auch dann,
wenn das Backend zwischendurch neu gestartet wurde.

### Endpunkte

| Endpunkt | Zweck |
|---|---|
| `GET /api/recordings/capture/config` | Freischaltung, Größengrenze, Sprechererkennung |
| `POST /api/recordings/capture/start` | Aufnahme anlegen (Titel, Auswertungsoptionen, Format) |
| `POST /api/recordings/capture/{id}/chunk?seq=n` | Nächstes Stück (`application/octet-stream`) |
| `POST /api/recordings/capture/{id}/heartbeat` | Lebenszeichen (z. B. während einer Pause) |
| `POST /api/recordings/capture/{id}/stop` | Regulär beenden und auswerten lassen |
| `POST /api/recordings/capture/{id}/abort` | Abbrechen, alle Daten löschen |

### Einstellungen

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `capture.enabled` | `true` | Funktion für Nutzer freigeschaltet |
| `capture.maxMegabytes` | `8192` | Obergrenze pro Aufnahme; danach nimmt der Server keine Daten mehr an, das Bisherige bleibt erhalten |
| `capture.staleMinutes` | `5` | So lange ohne Daten gilt eine Aufnahme als abgebrochen und wird abgeschlossen |

---

## Grenzen

- **Die Bildschirmauswahl kommt vom Browser.** Eine Webseite darf die
  vorhandenen Bildschirme nicht selbst auflisten — deshalb der Chrome-Dialog
  statt einer eigenen Liste mit Vorschaubildern.
- **Ein bestimmtes Wiedergabegerät lässt sich nicht gezielt abgreifen.**
  Aufgenommen wird der Ton der geteilten Quelle (ganzer Bildschirm = der
  Standard-Ausgabemix, Tab = der Ton dieses Tabs). Wer wirklich ein einzelnes
  Ausgabegerät mitschneiden muss, richtet auf dem Client ein virtuelles
  Loopback-Gerät ein (z. B. VB-Cable oder „Stereomix" unter Windows) — das
  erscheint dann als *Eingabegerät* und ist in der Mikrofon-Auswahl des Dialogs
  wählbar.
- **Der Browser-Tab muss offen bleiben.** Serverseitig läuft dabei nichts, was
  weiteraufnehmen könnte.

---

## Fehlersuche

| Symptom | Ursache | Abhilfe |
|---|---|---|
| Dialog meldet „nur über eine verschlüsselte Verbindung möglich" | Seite über `http://` aufgerufen, oder das Zertifikat ist dem Client nicht bekannt | Über die HTTPS-Adresse aufrufen; Zertifikat der internen CA auf den Clients verteilen |
| „Kein Systemton" nach der Quellenauswahl | Haken „Systemaudio übertragen" / „Audio des Tabs teilen" nicht gesetzt | Quelle erneut auswählen und den Haken setzen |
| Kein Systemton trotz Haken | Firefox (kann das nicht), Linux ohne PipeWire, macOS je nach Version eingeschränkt | Chrome oder Edge unter Windows verwenden; ersatzweise Mikrofon aufnehmen |
| Aufnahme endet mit „Die Übertragung ist abgebrochen" | Netz weg oder Sitzung abgelaufen | Die bis dahin übertragene Aufnahme steht in der Liste und ist auswertbar; danach neu starten |
| Fehler 413 beim Chunk | `capture.maxMegabytes` erreicht | Grenze im Admin-Bereich anheben oder kürzer/nur mit Ton aufnehmen |
| Fehler 410 „Aufnahme ist nicht mehr aktiv" | Backend wurde während der Aufnahme neu gestartet | Nichts zu tun: Der Aufräumlauf schließt die Aufnahme mit den vorhandenen Daten ab |
| Aufnahme bleibt auf „Aufnahme läuft" stehen | Browser weggebrochen | Nach `capture.staleMinutes` schließt der Server sie selbst ab |
| Dateien wachsen zu schnell | Bildqualität „Hoch" | „Standard" oder „nur Ton" verwenden |
