# ⚠️ Moved: use `posiuke/meeting-recorder`

**This image is no longer maintained.** The Meeting Recorder is now published
as **[`posiuke/meeting-recorder`](https://hub.docker.com/r/posiuke/meeting-recorder)**
(starting with version **1.0.0**). Please switch to the new image — this
repository receives no further updates, including security fixes.

```bash
docker pull posiuke/meeting-recorder:latest
```

## Switching over

1. In your `docker-compose.yml` replace

   ```yaml
   image: posiuke/bbb-recorder:latest
   ```

   with

   ```yaml
   image: posiuke/meeting-recorder:latest
   ```

2. **Using the BigBlueButton bots?** The BigBlueButton integration is now
   **off by default**. To keep the *Bots* tab, bot templates and schedules,
   add this to the `app` service's environment:

   ```yaml
   BBB_ON: "true"
   ```

   Without it the application runs as a pure upload / screen recording /
   transcription tool.

3. `docker compose pull && docker compose up -d`

Database, volumes and settings are taken over unchanged — no migration or
export is needed.

Documentation, source code and issues:
[github.com/Posiuke/Meeting-Recorder](https://github.com/Posiuke/Meeting-Recorder).

---

### Deutsch

**Dieses Image wird nicht mehr gepflegt.** Der Meeting Recorder erscheint ab
Version 1.0.0 als
**[`posiuke/meeting-recorder`](https://hub.docker.com/r/posiuke/meeting-recorder)**.
Bitte nur noch das neue Image verwenden.

Umstieg: in der `docker-compose.yml` `posiuke/bbb-recorder` durch
`posiuke/meeting-recorder` ersetzen. Wer die BigBlueButton-Bots weiter nutzt,
setzt zusätzlich `BBB_ON: "true"` beim `app`-Service — die Anbindung ist jetzt
standardmäßig ausgeschaltet. Datenbank, Volumes und Einstellungen bleiben
unverändert erhalten.
