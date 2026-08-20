import { translate } from '../i18n';
import type {
  GlossaryImportResult,
  GlossaryScope,
  PublicShareView,
  RecordingView,
  ShareLinkClaimView,
} from '../types';

const TOKEN_KEY = 'bbb_token';

let unauthorizedHandler: (() => void) | null = null;

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler;
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * Prüft clientseitig, ob das JWT abgelaufen ist (exp-Claim). So wird der
 * Nutzer beim nächsten API-Aufruf sofort abgemeldet, statt erst durch die
 * 401-Antwort des Servers. Nicht dekodierbare Tokens gelten als abgelaufen.
 */
export function isTokenExpired(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))) as {
      exp?: number;
    };
    if (typeof payload.exp !== 'number') return false;
    return payload.exp * 1000 <= Date.now();
  } catch {
    return true;
  }
}

/**
 * Abmeldung auslösen, wenn ein Aufruf außerhalb von {@link api} eine 401
 * erhält (z.B. der rohe Chunk-Upload der Bildschirmaufnahme).
 */
export function notifyUnauthorized(): void {
  unauthorizedHandler?.();
}

/** Abgelaufene Session zentral beenden; wirft den passenden 401-Fehler. */
function rejectExpiredSession(): ApiError {
  setToken(null);
  unauthorizedHandler?.();
  return new ApiError(401, translate('errors.sessionExpired'));
}

interface RequestOptions {
  method?: string;
  /** Objekt (wird als JSON gesendet) oder FormData für Datei-Uploads. */
  body?: unknown;
}

/**
 * Fetch-Wrapper: setzt Authorization-Header, parst JSON und behandelt Fehler.
 * Bei HTTP 401 (außer beim Login) wird der registrierte Handler aufgerufen,
 * der den Nutzer automatisch abmeldet.
 */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token && isTokenExpired(token) && !path.startsWith('/api/auth/login')) {
    throw rejectExpiredSession();
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  // Bei FormData setzt der Browser den Content-Type samt Boundary selbst –
  // ein eigener Header würde den Upload unbrauchbar machen.
  const isFormData = options.body instanceof FormData;
  if (options.body !== undefined && !isFormData) {
    headers['Content-Type'] = 'application/json';
  }

  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body:
      options.body === undefined
        ? undefined
        : isFormData
          ? (options.body as FormData)
          : JSON.stringify(options.body),
  });

  if (response.status === 401 && !path.startsWith('/api/auth/login')) {
    unauthorizedHandler?.();
    throw new ApiError(401, translate('errors.sessionExpired'));
  }

  if (!response.ok) {
    throw await errorFromResponse(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Fehlerantwort des Backends auswerten (Feld `message`, ersatzweise `error`). */
async function errorFromResponse(response: Response): Promise<ApiError> {
  let message = translate('errors.generic', { status: response.status });
  try {
    const data: unknown = await response.json();
    if (data && typeof data === 'object') {
      const obj = data as Record<string, unknown>;
      if (typeof obj.message === 'string' && obj.message) {
        message = obj.message;
      } else if (typeof obj.error === 'string' && obj.error) {
        message = obj.error;
      }
    }
  } catch {
    // Antwort ohne JSON-Körper – Standardmeldung verwenden
  }
  return new ApiError(response.status, message);
}

/**
 * Aufruf eines öffentlichen Endpunkts (Freigabe-Link). Bewusst ohne
 * Authorization-Header und ohne die Ablaufprüfung von {@link api}: Die
 * Berechtigung steckt im Token der Adresse. Ein abgelaufenes Login im
 * Browser darf die Ansicht nicht stören – auch nicht durch eine Abmeldung.
 */
export async function publicApi<T>(path: string): Promise<T> {
  const response = await fetch(path);
  if (!response.ok) {
    throw await errorFromResponse(response);
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Fehlermeldung aus beliebigen Fehlern (Error, rejectWithValue-String, ...) extrahieren. */
export function errorMessage(e: unknown): string {
  if (typeof e === 'string') return e;
  if (e instanceof Error) return e.message;
  if (e && typeof e === 'object' && 'message' in e) {
    const msg = (e as { message: unknown }).message;
    if (typeof msg === 'string') return msg;
  }
  return translate('errors.unknown');
}

export interface UploadRecordingOptions {
  title?: string;
  aiAnalysis: boolean;
  processNow: boolean;
  diarize?: boolean;
  /**
   * Auswertungs-Prompt der gewählten Vorlage; leer/undefined = Standardvorgabe
   * des Administrators. Er wird an der Aufnahme gespeichert und wirkt damit
   * schon bei einer sofortigen Auswertung.
   */
  summaryPrompt?: string | null;
  /**
   * Sprache der Spracherkennung; leer/undefined = Admin-Standard, `auto` =
   * Whisper erkennt sie selbst. Muss beim Hochladen feststehen: Ein mit
   * falscher Sprache erzeugtes Transkript ist nachträglich nicht zu retten.
   */
  sttLanguage?: string | null;
}

export interface UploadConfig {
  /** Upload-Limit, damit zu große Dateien gar nicht erst hochgeladen werden. */
  maxFileSizeBytes: number;
  /** Hat der Admin die Sprechererkennung (Diarisierung) freigeschaltet? */
  diarizeAllowed: boolean;
  /** Admin-Standard der Spracherkennung (`whisper.language`); leer = automatisch erkennen. */
  sttLanguage: string;
}

export function fetchUploadConfig(): Promise<UploadConfig> {
  return api<UploadConfig>('/api/recordings/upload-config');
}

/**
 * Lädt eine Audio-/Videodatei als neue Aufnahme hoch. Nutzt XMLHttpRequest
 * statt fetch, damit der Upload-Fortschritt gemeldet werden kann.
 */
export function uploadRecording(
  file: File,
  options: UploadRecordingOptions,
  onProgress?: (percent: number) => void,
): Promise<RecordingView> {
  return new Promise((resolve, reject) => {
    const token = getToken();
    if (token && isTokenExpired(token)) {
      reject(rejectExpiredSession());
      return;
    }
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/recordings/upload');
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    }
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    };
    xhr.onload = () => {
      if (xhr.status === 401) {
        unauthorizedHandler?.();
        reject(new ApiError(401, translate('errors.sessionExpired')));
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as RecordingView);
        } catch {
          reject(new ApiError(xhr.status, translate('errors.invalidResponse')));
        }
        return;
      }
      let message = translate('errors.generic', { status: xhr.status });
      try {
        const data = JSON.parse(xhr.responseText) as Record<string, unknown>;
        if (typeof data.message === 'string' && data.message) message = data.message;
        else if (typeof data.error === 'string' && data.error) message = data.error;
      } catch {
        // Antwort ohne JSON-Körper – Standardmeldung verwenden
      }
      reject(new ApiError(xhr.status, message));
    };
    xhr.onerror = () => reject(new ApiError(0, translate('errors.uploadNetwork')));

    const form = new FormData();
    form.append('file', file);
    if (options.title?.trim()) form.append('title', options.title.trim());
    form.append('aiAnalysis', String(options.aiAnalysis));
    form.append('processNow', String(options.processNow));
    form.append('diarize', String(options.diarize ?? false));
    if (options.summaryPrompt?.trim()) form.append('summaryPrompt', options.summaryPrompt.trim());
    if (options.sttLanguage?.trim()) form.append('sttLanguage', options.sttLanguage.trim());
    xhr.send(form);
  });
}

/**
 * Audio-URL mit Token als Query-Parameter, da <audio> keine Header setzen kann.
 */
export function audioUrl(recordingId: string, segmentId: string): string {
  const token = getToken() ?? '';
  return `/api/recordings/${recordingId}/segments/${segmentId}/audio?token=${encodeURIComponent(token)}`;
}

/**
 * Durchgehende Tonspur der ganzen Aufnahme (Segmente zusammengefügt). Der
 * Server fügt sie beim ersten Abruf zusammen; der Browser darf darin springen
 * (Range-Requests), worauf der Sprung aus dem Transkript aufbaut.
 */
export function fullAudioUrl(recordingId: string): string {
  const token = getToken() ?? '';
  return `/api/recordings/${recordingId}/audio?token=${encodeURIComponent(token)}`;
}

export function fullAudioDownloadUrl(recordingId: string): string {
  const token = getToken() ?? '';
  return `/api/recordings/${recordingId}/audio/download?token=${encodeURIComponent(token)}`;
}

/** Download-Format der Text-Ausgaben: Markdown-Rohfassung oder Word-Datei (.doc). */
export type DownloadFormat = 'md' | 'doc';

export function summaryDownloadUrl(recordingId: string, format: DownloadFormat = 'md'): string {
  const token = getToken() ?? '';
  return `/api/recordings/${recordingId}/summary/download?format=${format}&token=${encodeURIComponent(token)}`;
}

/**
 * Transkript als Datei. `original` wählt die Whisper-Rohfassung statt der
 * geglätteten – der Aufrufer gibt weiter, was im Transkript-Tab gerade
 * angezeigt wird.
 */
export function transcriptDownloadUrl(
  recordingId: string,
  original: boolean,
  format: DownloadFormat = 'md',
): string {
  const token = getToken() ?? '';
  const variant = original ? 'original' : 'corrected';
  return `/api/recordings/${recordingId}/transcript/download?variant=${variant}&format=${format}`
    + `&token=${encodeURIComponent(token)}`;
}

/** Video-URL (MP4) mit Token als Query-Parameter, da <video> keine Header setzen kann. */
export function videoUrl(recordingId: string): string {
  const token = getToken() ?? '';
  return `/api/recordings/${recordingId}/video?token=${encodeURIComponent(token)}`;
}

export function videoDownloadUrl(recordingId: string): string {
  const token = getToken() ?? '';
  return `/api/recordings/${recordingId}/video/download?token=${encodeURIComponent(token)}`;
}

// ------------------------------------------------------- Freigabe-Links
//
// Die Adressen der öffentlichen Ansicht tragen das Token im Pfad; ein
// Login-Token wird hier bewusst nirgends angehängt.

/** Vollständige Adresse, die der Besitzer weitergibt. */
export function shareLinkUrl(token: string): string {
  return `${window.location.origin}/share/${token}`;
}

export function publicShare(token: string): Promise<PublicShareView> {
  return publicApi<PublicShareView>(`/api/public/shares/${encodeURIComponent(token)}`);
}

export function publicAudioUrl(token: string, segmentId: string): string {
  return `/api/public/shares/${encodeURIComponent(token)}/segments/${segmentId}/audio`;
}

export function publicVideoUrl(token: string): string {
  return `/api/public/shares/${encodeURIComponent(token)}/video`;
}

export function publicVideoDownloadUrl(token: string): string {
  return `/api/public/shares/${encodeURIComponent(token)}/video/download`;
}

export function publicSummaryDownloadUrl(token: string): string {
  return `/api/public/shares/${encodeURIComponent(token)}/summary/download`;
}

/**
 * Kontogebundenen Freigabe-Link einlösen (angemeldet): Die Aufnahme wird mit dem
 * eigenen Konto geteilt; zurück kommt ihre Kennung für den Sprung dorthin.
 */
export function claimShareLink(token: string): Promise<ShareLinkClaimView> {
  return api<ShareLinkClaimView>(`/api/share-links/${encodeURIComponent(token)}/claim`, {
    method: 'POST',
  });
}

/** Darf auf diesem Server überhaupt ohne Anmeldung geteilt werden? (Admin-Einstellung) */
export function fetchShareLinkConfig(): Promise<{ publicLinksAllowed: boolean }> {
  return api<{ publicLinksAllowed: boolean }>('/api/share-links/config');
}

/**
 * Basispfad einer der beiden Glossar-Listen: das persönliche Glossar des
 * angemeldeten Nutzers oder das gemeinsame der Installation.
 */
export function glossaryPath(scope: GlossaryScope): string {
  return scope === 'shared' ? '/api/glossary/shared' : '/api/glossary';
}

/**
 * Glossar als CSV-Datei. Token im Query-Parameter, weil der Download über einen
 * normalen Link läuft (kein Header möglich).
 */
export function glossaryExportUrl(scope: GlossaryScope): string {
  const token = getToken() ?? '';
  return `${glossaryPath(scope)}/export?token=${encodeURIComponent(token)}`;
}

/** Liest eine CSV-Datei in eine der Listen ein (zusammenführen, nichts wird gelöscht). */
export function importGlossary(file: File, scope: GlossaryScope): Promise<GlossaryImportResult> {
  const form = new FormData();
  form.append('file', file);
  return api<GlossaryImportResult>(`${glossaryPath(scope)}/import`, { method: 'POST', body: form });
}
