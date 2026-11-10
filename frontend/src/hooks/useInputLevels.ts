import { useEffect, useState } from 'react';

/** Lautstärke einer Quelle als Wert zwischen 0 und 1 (Effektivwert). */
export function readLevel(analyser: AnalyserNode | null | undefined): number {
  if (!analyser) return 0;
  const data = new Uint8Array(analyser.fftSize);
  analyser.getByteTimeDomainData(data);
  let sum = 0;
  for (let i = 0; i < data.length; i += 1) {
    const deviation = (data[i] - 128) / 128;
    sum += deviation * deviation;
  }
  const rms = Math.sqrt(sum / data.length);
  // Etwas anheben, damit normale Sprache den Balken sichtbar bewegt.
  return Math.min(1, rms * 3);
}

/**
 * Pegel aller Audio-Eingänge gleichzeitig – damit man bei der Auswahl sieht, auf
 * welchem Gerät gerade Ton ankommt. Jedes Gerät wird dafür kurz geöffnet, aber
 * nirgends hingeleitet; die Streams schließen, sobald `enabled` false wird.
 *
 * Liefert pro deviceId einen Pegel (0–1) oder `null`, wenn sich das Gerät nicht
 * öffnen ließ. Geräte ohne deviceId (Berechtigung fehlt noch) fehlen.
 */
export function useInputLevels(
  devices: MediaDeviceInfo[],
  enabled: boolean,
): Record<string, number | null> {
  const [levels, setLevels] = useState<Record<string, number | null>>({});
  const ids = devices
    .map((d) => d.deviceId)
    .filter(Boolean)
    .join('|');

  useEffect(() => {
    if (!enabled || !ids) {
      setLevels({});
      return;
    }
    let cancelled = false;
    const ctx = new AudioContext();
    const streams: MediaStream[] = [];
    const analysers = new Map<string, AnalyserNode | null>();

    ids.split('|').forEach((deviceId) => {
      navigator.mediaDevices
        .getUserMedia({
          audio: {
            deviceId: { exact: deviceId },
            // Rohsignal zeigen: Die Aufbereitung würde leise Quellen wegregeln.
            echoCancellation: false,
            noiseSuppression: false,
            autoGainControl: false,
          },
        })
        .then((stream) => {
          if (cancelled) {
            stream.getTracks().forEach((t) => t.stop());
            return;
          }
          streams.push(stream);
          const analyser = ctx.createAnalyser();
          analyser.fftSize = 1024;
          ctx.createMediaStreamSource(stream).connect(analyser);
          analysers.set(deviceId, analyser);
        })
        .catch(() => {
          if (!cancelled) analysers.set(deviceId, null);
        });
    });

    const timer = window.setInterval(() => {
      const next: Record<string, number | null> = {};
      analysers.forEach((analyser, deviceId) => {
        next[deviceId] = analyser ? readLevel(analyser) : null;
      });
      setLevels(next);
    }, 100);

    return () => {
      cancelled = true;
      window.clearInterval(timer);
      streams.forEach((s) => s.getTracks().forEach((t) => t.stop()));
      if (ctx.state !== 'closed') void ctx.close();
    };
  }, [enabled, ids]);

  return levels;
}
