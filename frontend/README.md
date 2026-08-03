# Meeting Recorder – Frontend

React-Frontend (Vite + React 18 + TypeScript + Redux Toolkit) für den BigBlueButton-Aufzeichnungs-Bot.

## Entwicklung

```bash
npm install
npm run dev
```

Der Dev-Server läuft auf http://localhost:5173. Alle Anfragen an `/api` werden per Vite-Proxy
an das Backend unter `http://localhost:8080` weitergeleitet (siehe `vite.config.ts`).

## Produktions-Build

```bash
npm run build
```

Erzeugt das statische Frontend in `dist/`. Die Auslieferung erfolgt über nginx; das Backend muss
unter demselben Origin auf dem Pfad `/api` erreichbar sein, da alle API-Aufrufe relative Pfade
(`/api/...`) verwenden. Da die App client-seitiges Routing (react-router) nutzt, sollte nginx
unbekannte Pfade auf `index.html` umschreiben (`try_files $uri /index.html;`).

## Hinweise

- Das JWT-Token wird nach dem Login in `localStorage` gespeichert und beim App-Start über
  `GET /api/auth/me` validiert. Bei HTTP 401 erfolgt ein automatischer Logout.
- Audio-Streams und Zusammenfassungs-Downloads übergeben das Token als Query-Parameter `?token=...`,
  da `<audio>`-Elemente keine Header setzen können.

## Fehler melden und Features vorschlagen

Fehler in der Oberfläche und Verbesserungsideen gerne als GitHub-Issue melden:
[Bug melden](https://github.com/Posiuke/Meeting-Recorder/issues/new?template=bug_report.yml)
· [Feature wünschen](https://github.com/Posiuke/Meeting-Recorder/issues/new?template=feature_request.yml)
· [alle Issues](https://github.com/Posiuke/Meeting-Recorder/issues).
Details im [Haupt-README](../README.md#fehler-melden-und-features-vorschlagen).
