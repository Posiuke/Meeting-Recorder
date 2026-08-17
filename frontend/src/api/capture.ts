import { ApiError, api, getToken, isTokenExpired, notifyUnauthorized } from './client';
import { translate } from '../i18n';
import type { RecordingView } from '../types';

/** Rahmenbedingungen der Bildschirmaufnahme (vom Server). */
export interface CaptureConfig {
  /** Hat der Admin die Bildschirmaufnahme freigeschaltet? */
  enabled: boolean;
  /** Obergrenze für eine einzelne Aufnahme in Bytes. */
  maxBytes: number;
  /** Hat der Admin die Sprechererkennung freigeschaltet? */
  diarizeAllowed: boolean;
  /** Admin-Standard der Spracherkennung (`whisper.language`); leer = automatisch erkennen. */
  sttLanguage: string;
}

export interface StartCaptureOptions {
  title?: string;
  aiAnalysis: boolean;
  processNow: boolean;
  diarize: boolean;
  /** false = nur Ton (der Bildschirm wird verworfen, sobald die Quelle steht). */
  video: boolean;
  /** Sprache der Spracherkennung; leer = Admin-Standard, `auto` = automatisch erkennen. */
  sttLanguage?: string | null;
  /** Aufnahmeformat des Browsers, bestimmt die Dateiendung auf dem Server. */
  mimeType: string;
}

export function fetchCaptureConfig(): Promise<CaptureConfig> {
  return api<CaptureConfig>('/api/recordings/capture/config');
}

export function startCapture(options: StartCaptureOptions): Promise<RecordingView> {
  return api<RecordingView>('/api/recordings/capture/start', { method: 'POST', body: options });
}

export function stopCapture(recordingId: string): Promise<RecordingView> {
  return api<RecordingView>(`/api/recordings/capture/${recordingId}/stop`, { method: 'POST' });
}

export function captureHeartbeat(recordingId: string): Promise<void> {
  return api<void>(`/api/recordings/capture/${recordingId}/heartbeat`, { method: 'POST' });
}

export function abortCapture(recordingId: string): Promise<void> {
  return api<void>(`/api/recordings/capture/${recordingId}/abort`, { method: 'POST' });
}

/**
 * Abbruch beim Schließen des Tabs. `keepalive` sorgt dafür, dass der Browser
 * die Anfrage noch zustellt, während die Seite verschwindet – sendBeacon
 * scheidet aus, weil es keinen Authorization-Header setzen kann.
 */
export function abortCaptureOnUnload(recordingId: string): void {
  const token = getToken();
  void fetch(`/api/recordings/capture/${recordingId}/abort`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    keepalive: true,
  }).catch(() => {
    // Beim Verlassen der Seite ist ein Fehlschlag egal: Der Server räumt die
    // Aufnahme später selbst ab (capture.staleMinutes).
  });
}

/**
 * Lädt ein Stück der laufenden Aufnahme hoch. Die Reihenfolge ist zwingend –
 * der Server hängt die Stücke unverändert aneinander.
 *
 * @returns die vom Server als nächstes erwartete Sequenznummer
 */
export async function uploadCaptureChunk(
  recordingId: string,
  seq: number,
  chunk: Blob,
): Promise<number> {
  const token = getToken();
  if (token && isTokenExpired(token)) {
    notifyUnauthorized();
    throw new ApiError(401, translate('errors.sessionExpired'));
  }
  const headers: Record<string, string> = { 'Content-Type': 'application/octet-stream' };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const response = await fetch(`/api/recordings/capture/${recordingId}/chunk?seq=${seq}`, {
    method: 'POST',
    headers,
    body: chunk,
  });

  if (response.status === 401) {
    notifyUnauthorized();
    throw new ApiError(401, translate('errors.sessionExpired'));
  }
  if (!response.ok) {
    let message = translate('errors.generic', { status: response.status });
    try {
      const data = (await response.json()) as Record<string, unknown>;
      if (typeof data.message === 'string' && data.message) message = data.message;
      else if (typeof data.error === 'string' && data.error) message = data.error;
    } catch {
      // Antwort ohne JSON-Körper – Standardmeldung verwenden
    }
    throw new ApiError(response.status, message);
  }
  const data = (await response.json()) as { nextSeq?: number };
  return typeof data.nextSeq === 'number' ? data.nextSeq : seq + 1;
}
