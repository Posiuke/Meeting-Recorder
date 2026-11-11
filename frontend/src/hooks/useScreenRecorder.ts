import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, errorMessage } from '../api/client';
import { translate } from '../i18n';
import {
  abortCapture,
  abortCaptureOnUnload,
  captureHeartbeat,
  startCapture,
  stopCapture,
  uploadCaptureChunk,
} from '../api/capture';
import type { RecordingView } from '../types';
import { readLevel } from './useInputLevels';
import { VideoRelay, videoRelaySupported } from './videoRelay';

export type CapturePhase =
  | 'idle' // noch keine Quelle gewählt
  | 'ready' // Bildschirm gewählt, Vorschau läuft
  | 'starting' // Aufnahme wird angelegt
  | 'recording'
  | 'paused'
  | 'finishing' // Restliche Stücke werden hochgeladen
  | 'done'
  | 'error';

export type CaptureQuality = 'standard' | 'high';

/**
 * Woher das Bild kommt: Bildschirm/Fenster/Tab über den Auswahldialog des
 * Browsers – oder eine Kamera. Die Kamera ist auch der Weg, ein Bild von außen
 * hereinzuholen, etwa den Bildschirm des RDP-Clients über eine virtuelle Kamera
 * (OBS) und die Kamera-Umleitung von Remotedesktop.
 */
export type CaptureSourceKind = 'screen' | 'camera';

/** Länge eines Aufnahmestücks. Kurz genug, dass ein Absturz wenig kostet. */
const CHUNK_MS = 5000;

/** Lebenszeichen an den Server, damit eine Pause nicht als Abbruch gilt. */
const HEARTBEAT_MS = 30_000;

const MAX_CHUNK_ATTEMPTS = 4;
const RETRY_BASE_MS = 1000;

/** Auf diese Fehler folgt kein erneuter Versuch – die Aufnahme ist zu Ende. */
const FATAL_STATUS = new Set([401, 403, 409, 410, 413]);

const AUDIO_BITS_PER_SECOND = 128_000;

const QUALITY_PRESETS: Record<CaptureQuality, { frameRate: number; videoBitsPerSecond: number }> = {
  // Besprechungen sind kein Actionfilm: Wenige Bilder pro Sekunde halten die
  // Datei klein, ohne dass Folien oder geteilte Dokumente leiden.
  standard: { frameRate: 10, videoBitsPerSecond: 1_200_000 },
  high: { frameRate: 25, videoBitsPerSecond: 2_500_000 },
};

const VIDEO_MIME_CANDIDATES = [
  'video/webm;codecs=vp9,opus',
  'video/webm;codecs=vp8,opus',
  'video/webm',
];

const AUDIO_MIME_CANDIDATES = ['audio/webm;codecs=opus', 'audio/webm'];

function pickMimeType(withVideo: boolean): string | null {
  const candidates = withVideo ? VIDEO_MIME_CANDIDATES : AUDIO_MIME_CANDIDATES;
  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) ?? null;
}

export type CaptureSupportReason = 'insecure' | 'unsupported' | 'codec';

/**
 * Prüft, ob dieser Browser überhaupt aufnehmen kann. Der häufigste Fall ist
 * `insecure`: Bildschirmaufnahme gibt es nur im sicheren Kontext (HTTPS oder
 * localhost) – ohne den existiert `getDisplayMedia` gar nicht erst.
 */
export function captureSupport(): { supported: boolean; reason?: CaptureSupportReason } {
  if (typeof window === 'undefined') return { supported: false, reason: 'unsupported' };
  if (!window.isSecureContext) return { supported: false, reason: 'insecure' };
  if (typeof MediaRecorder === 'undefined' || !navigator.mediaDevices?.getDisplayMedia) {
    return { supported: false, reason: 'unsupported' };
  }
  if (!pickMimeType(true) && !pickMimeType(false)) return { supported: false, reason: 'codec' };
  return { supported: true };
}

/** Chrome-spezifische Zusatzoptionen, die die TypeScript-Typen noch nicht kennen. */
interface DisplayMediaOptions extends DisplayMediaStreamOptions {
  systemAudio?: 'include' | 'exclude';
  selfBrowserSurface?: 'include' | 'exclude';
  surfaceSwitching?: 'include' | 'exclude';
}

/** Optionen für den Auswahldialog des Browsers – beim Start und beim Wechsel gleich. */
function displayOptions(quality: CaptureQuality): DisplayMediaOptions {
  const preset = QUALITY_PRESETS[quality];
  return {
    video: {
      frameRate: { ideal: preset.frameRate, max: preset.frameRate },
      width: { max: 1920 },
      height: { max: 1080 },
    },
    // Für Sprache sind die Aufbereitungen des Browsers eher schädlich –
    // sie sind für Mikrofone gedacht, nicht für einen Systemtonmitschnitt.
    audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    systemAudio: 'include',
    selfBrowserSurface: 'exclude',
    surfaceSwitching: 'include',
  };
}

/** Einschränkungen für eine Kamera – gleiche Bildrate und Obergrenze wie beim Bildschirm. */
function cameraConstraints(deviceId: string, quality: CaptureQuality): MediaTrackConstraints {
  const preset = QUALITY_PRESETS[quality];
  return {
    ...(deviceId ? { deviceId: { exact: deviceId } } : {}),
    frameRate: { ideal: preset.frameRate, max: preset.frameRate },
    width: { ideal: 1920, max: 1920 },
    height: { ideal: 1080, max: 1080 },
  };
}

/** Auswahldialog abgebrochen – kein Fehler, sondern eine Entscheidung des Nutzers. */
function isUserAbort(e: unknown): boolean {
  return e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError');
}

export interface BeginRecordingOptions {
  title: string;
  aiAnalysis: boolean;
  processNow: boolean;
  diarize: boolean;
  /** Sprache der Spracherkennung; '' = Admin-Standard, 'auto' = automatisch erkennen. */
  sttLanguage: string;
}

export interface AudioLevels {
  system: number;
  mic: number;
}

/**
 * Nimmt im Browser Bildschirm und Ton auf und schiebt die laufende Aufnahme
 * stückweise an den Server.
 *
 * Aufbau: `getDisplayMedia` liefert Bild und – wenn der Nutzer im Chrome-Dialog
 * „Systemaudio übertragen" ankreuzt – den Ton des Bildschirms. Ein optionales
 * Mikrofon kommt über `getUserMedia` dazu. Beide Tonquellen werden im WebAudio-
 * Graph gemischt (eine Datei trägt praktisch nur eine Tonspur, und zwei parallele
 * Recorder würden gegeneinander driften). Der `MediaRecorder` liefert alle
 * {@link CHUNK_MS} ein Stück, das eine strikt sequenzielle Warteschlange hochlädt.
 *
 * Quellen wechseln während der Aufnahme: Ein `MediaRecorder` bricht ab, sobald
 * sich seine Spuren ändern. Er sieht deshalb nur Spuren, die gleich bleiben –
 * den Ausgang des Mixers und (in Chrome/Edge) die Bildspur eines
 * {@link VideoRelay}. Ein neues Mikrofon oder ein neuer Bildschirm wird nur
 * dahinter umgehängt; die Datei bleibt ein einziger, durchgehender WebM-Strom.
 */
export function useScreenRecorder() {
  const [phase, setPhase] = useState<CapturePhase>('idle');
  const [error, setError] = useState<string | null>(null);
  const [previewStream, setPreviewStream] = useState<MediaStream | null>(null);
  const [withVideo, setWithVideo] = useState(true);
  const [quality, setQuality] = useState<CaptureQuality>('standard');
  const [systemAudio, setSystemAudio] = useState(false);
  const [sourceKind, setSourceKind] = useState<CaptureSourceKind>('screen');
  const [cameraDevices, setCameraDevices] = useState<MediaDeviceInfo[]>([]);
  const [cameraDeviceId, setCameraDeviceId] = useState<string>('');
  const [micDevices, setMicDevices] = useState<MediaDeviceInfo[]>([]);
  const [micDeviceId, setMicDeviceId] = useState<string>('');
  const [micActive, setMicActive] = useState(false);
  const [micError, setMicError] = useState<string | null>(null);
  const [micMuted, setMicMuted] = useState(false);
  const [canSwitchSource, setCanSwitchSource] = useState(false);
  const [switching, setSwitching] = useState(false);
  const [levels, setLevels] = useState<AudioLevels>({ system: 0, mic: 0 });
  const [elapsedMs, setElapsedMs] = useState(0);
  const [uploadedBytes, setUploadedBytes] = useState(0);
  const [pendingChunks, setPendingChunks] = useState(0);
  const [result, setResult] = useState<RecordingView | null>(null);

  const displayStreamRef = useRef<MediaStream | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const recordingIdRef = useRef<string | null>(null);
  const relayRef = useRef<VideoRelay | null>(null);
  const withVideoRef = useRef(true);
  const qualityRef = useRef<CaptureQuality>('standard');

  // Tonmischer (siehe ensureMixer)
  const audioCtxRef = useRef<AudioContext | null>(null);
  const destinationRef = useRef<MediaStreamAudioDestinationNode | null>(null);
  const systemSourceRef = useRef<MediaStreamAudioSourceNode | null>(null);
  const micSourceRef = useRef<MediaStreamAudioSourceNode | null>(null);
  const micGainRef = useRef<GainNode | null>(null);
  const micMutedRef = useRef(false);
  const systemAnalyserRef = useRef<AnalyserNode | null>(null);
  const micAnalyserRef = useRef<AnalyserNode | null>(null);
  const levelTimerRef = useRef<number | null>(null);

  const queueRef = useRef<Blob[]>([]);
  const seqRef = useRef(0);
  const pumpingRef = useRef(false);
  const fatalRef = useRef<string | null>(null);
  const finishingRef = useRef(false);

  const startedAtRef = useRef(0);
  const pausedTotalRef = useRef(0);
  const pausedAtRef = useRef(0);
  const timersRef = useRef<number[]>([]);

  const clearTimers = useCallback(() => {
    timersRef.current.forEach((id) => window.clearInterval(id));
    timersRef.current = [];
  }, []);

  /**
   * Tonmischer anlegen, falls noch keiner läuft. Er entsteht mit der ersten
   * Quelle (nicht erst beim Start der Aufnahme), damit die Pegel schon vorher
   * sichtbar sind und sich Quellen jederzeit umhängen lassen:
   *
   *   Systemton ──┬─ Analyser
   *               └─────────────────────────┐
   *   Mikrofon ───┬─ Analyser               ├─ Ziel → Recorder
   *               └─ Gain (0 = stumm) ──────┘
   *
   * Der Analyser hängt vor der Stummschaltung: So sieht man auch stumm
   * geschaltet, dass das Mikrofon Ton liefert.
   */
  const ensureMixer = useCallback((): AudioContext => {
    const existing = audioCtxRef.current;
    if (existing && existing.state !== 'closed') return existing;
    const ctx = new AudioContext();
    audioCtxRef.current = ctx;
    const destination = ctx.createMediaStreamDestination();
    destinationRef.current = destination;
    const gain = ctx.createGain();
    gain.gain.value = micMutedRef.current ? 0 : 1;
    gain.connect(destination);
    micGainRef.current = gain;
    if (ctx.state === 'suspended') void ctx.resume().catch(() => undefined);
    if (levelTimerRef.current === null) {
      levelTimerRef.current = window.setInterval(() => {
        setLevels({
          system: readLevel(systemAnalyserRef.current),
          mic: readLevel(micAnalyserRef.current),
        });
      }, 100);
    }
    return ctx;
  }, []);

  /** Systemton einer (neuen) Bildschirmquelle in den Mixer hängen – oder entfernen. */
  const connectSystem = useCallback(
    (stream: MediaStream | null) => {
      systemSourceRef.current?.disconnect();
      systemSourceRef.current = null;
      systemAnalyserRef.current = null;
      const tracks = stream?.getAudioTracks().filter((t) => t.readyState === 'live') ?? [];
      if (tracks.length === 0) return;
      const ctx = ensureMixer();
      const source = ctx.createMediaStreamSource(new MediaStream(tracks));
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      source.connect(analyser);
      if (destinationRef.current) source.connect(destinationRef.current);
      systemSourceRef.current = source;
      systemAnalyserRef.current = analyser;
    },
    [ensureMixer],
  );

  /** Mikrofon in den Mixer hängen – oder entfernen. */
  const connectMic = useCallback(
    (stream: MediaStream | null) => {
      micSourceRef.current?.disconnect();
      micSourceRef.current = null;
      micAnalyserRef.current = null;
      if (!stream) return;
      const ctx = ensureMixer();
      const source = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      source.connect(analyser);
      if (micGainRef.current) source.connect(micGainRef.current);
      micSourceRef.current = source;
      micAnalyserRef.current = analyser;
    },
    [ensureMixer],
  );

  /** Bildschirmquelle freigeben, ohne Mikrofon und Mixer anzutasten. */
  const releaseDisplay = useCallback(() => {
    displayStreamRef.current?.getTracks().forEach((t) => {
      t.onended = null;
      t.stop();
    });
    displayStreamRef.current = null;
    connectSystem(null);
    setPreviewStream(null);
  }, [connectSystem]);

  /** Alle Medienressourcen freigeben (Freigabe-Hinweis des Browsers verschwindet). */
  const releaseMedia = useCallback(() => {
    clearTimers();
    recorderRef.current = null;
    relayRef.current?.stop();
    relayRef.current = null;
    displayStreamRef.current?.getTracks().forEach((t) => {
      t.onended = null;
      t.stop();
    });
    displayStreamRef.current = null;
    micStreamRef.current?.getTracks().forEach((t) => t.stop());
    micStreamRef.current = null;
    if (levelTimerRef.current !== null) window.clearInterval(levelTimerRef.current);
    levelTimerRef.current = null;
    systemSourceRef.current = null;
    micSourceRef.current = null;
    micGainRef.current = null;
    destinationRef.current = null;
    systemAnalyserRef.current = null;
    micAnalyserRef.current = null;
    const ctx = audioCtxRef.current;
    audioCtxRef.current = null;
    if (ctx && ctx.state !== 'closed') void ctx.close();
    setPreviewStream(null);
    setMicActive(false);
    setCanSwitchSource(false);
    setLevels({ system: 0, mic: 0 });
  }, [clearTimers]);

  useEffect(() => releaseMedia, [releaseMedia]);

  // Schutz vor versehentlichem Schließen des Tabs: Ohne den Browser läuft
  // die Aufnahme nicht weiter.
  useEffect(() => {
    if (phase !== 'recording' && phase !== 'paused' && phase !== 'finishing') return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [phase]);

  // Wird der Tab trotzdem geschlossen, die Aufnahme sauber abbrechen statt sie
  // bis zum Aufräumlauf des Servers hängen zu lassen.
  useEffect(() => {
    if (phase !== 'recording' && phase !== 'paused') return;
    const handler = () => {
      const id = recordingIdRef.current;
      if (id) abortCaptureOnUnload(id);
    };
    window.addEventListener('pagehide', handler);
    return () => window.removeEventListener('pagehide', handler);
  }, [phase]);

  /** Mikrofone und Kameras neu einlesen (Namen gibt es erst nach erteilter Berechtigung). */
  const refreshDevices = useCallback(async () => {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      setMicDevices(devices.filter((d) => d.kind === 'audioinput'));
      setCameraDevices(devices.filter((d) => d.kind === 'videoinput'));
    } catch {
      setMicDevices([]);
      setCameraDevices([]);
    }
  }, []);

  // Kameraliste für die Quellenauswahl schon vor der ersten Freigabe füllen
  // (dann noch ohne Namen – die gibt der Browser erst nach der Berechtigung her).
  useEffect(() => {
    void refreshDevices();
  }, [refreshDevices]);

  // Neu angesteckte oder entfernte Geräte (Headset!) gleich in der Liste zeigen.
  useEffect(() => {
    const devices = navigator.mediaDevices;
    if (!devices?.addEventListener) return;
    const handler = () => void refreshDevices();
    devices.addEventListener('devicechange', handler);
    return () => devices.removeEventListener('devicechange', handler);
  }, [refreshDevices]);

  /**
   * Mikrofon aktivieren oder wechseln – auch während der Aufnahme. Die
   * Geräteliste trägt erst nach erteilter Berechtigung lesbare Namen – deshalb
   * wird hier zuerst der Zugriff angefragt. Das neue Gerät öffnet, bevor das alte
   * schließt, damit beim Wechsel keine Lücke im Ton entsteht.
   */
  const enableMic = useCallback(
    async (deviceId?: string) => {
      setMicError(null);
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: deviceId ? { deviceId: { exact: deviceId } } : true,
        });
        connectMic(stream);
        micStreamRef.current?.getTracks().forEach((t) => t.stop());
        micStreamRef.current = stream;
        setMicActive(true);
        if (deviceId) setMicDeviceId(deviceId);
        else {
          const active = stream.getAudioTracks()[0]?.getSettings().deviceId;
          if (active) setMicDeviceId(active);
        }
        await refreshDevices();
        return true;
      } catch (e) {
        // Schlägt nur der Wechsel fehl, bleibt das bisherige Mikrofon aktiv.
        setMicActive(micStreamRef.current !== null);
        setMicError(
          e instanceof DOMException && e.name === 'NotAllowedError'
            ? translate('capture.errors.micDenied')
            : translate('capture.errors.micFailed', { message: errorMessage(e) }),
        );
        return false;
      }
    },
    [connectMic, refreshDevices],
  );

  const disableMic = useCallback(() => {
    connectMic(null);
    micStreamRef.current?.getTracks().forEach((t) => t.stop());
    micStreamRef.current = null;
    setMicActive(false);
    setMicError(null);
  }, [connectMic]);

  /** Mikrofon stumm schalten bzw. wieder freigeben – wirkt sofort, auch in der Aufnahme. */
  const setMicMute = useCallback((muted: boolean) => {
    micMutedRef.current = muted;
    setMicMuted(muted);
    const gain = micGainRef.current;
    // Kurze Rampe statt hartem Sprung, sonst knackt es in der Aufnahme.
    if (gain) gain.gain.setTargetAtTime(muted ? 0 : 1, gain.context.currentTime, 0.015);
  }, []);

  /**
   * Quelle wählen: Öffnet den Auswahldialog des Browsers (eine eigene Liste der
   * Bildschirme darf eine Webseite aus Sicherheitsgründen nicht anzeigen).
   * Auch für „nur Ton" ist die Auswahl nötig – der Systemton hängt am geteilten
   * Bildschirm, das Bild wird danach verworfen.
   */
  const chooseSource = useCallback(
    async (video: boolean, selectedQuality: CaptureQuality) => {
      setError(null);
      try {
        const stream = await navigator.mediaDevices.getDisplayMedia(displayOptions(selectedQuality));
        releaseDisplay();
        displayStreamRef.current = stream;
        setSystemAudio(stream.getAudioTracks().length > 0);
        setSourceKind('screen');
        setWithVideo(video);
        withVideoRef.current = video;
        setQuality(selectedQuality);
        qualityRef.current = selectedQuality;

        if (video) {
          setPreviewStream(stream);
        } else {
          // Nur Ton: Bildspur sofort beenden, dann sieht der Nutzer auch keinen
          // laufenden Bildschirm-Mitschnitt in der Browserleiste mehr als nötig.
          stream.getVideoTracks().forEach((t) => t.stop());
          setPreviewStream(null);
        }
        connectSystem(stream);
        setPhase('ready');
        return true;
      } catch (e) {
        if (isUserAbort(e)) return false;
        setError(translate('capture.errors.displayFailed', { message: errorMessage(e) }));
        return false;
      }
    },
    [connectSystem, releaseDisplay],
  );

  /**
   * Eine Kamera als Bildquelle öffnen. Sie liefert nur Bild – Ton kommt über
   * das Mikrofon dazu. Ohne `deviceId` nimmt der Browser die Standardkamera
   * (und fragt dabei die Berechtigung an, danach tragen die Geräte Namen).
   */
  const chooseCamera = useCallback(
    async (deviceId: string, selectedQuality: CaptureQuality) => {
      setError(null);
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: cameraConstraints(deviceId, selectedQuality),
          audio: false,
        });
        releaseDisplay();
        displayStreamRef.current = stream;
        setSystemAudio(false);
        setSourceKind('camera');
        setWithVideo(true);
        withVideoRef.current = true;
        setQuality(selectedQuality);
        qualityRef.current = selectedQuality;
        setCameraDeviceId(stream.getVideoTracks()[0]?.getSettings().deviceId ?? deviceId);
        setPreviewStream(stream);
        setPhase('ready');
        await refreshDevices();
        return true;
      } catch (e) {
        setError(
          e instanceof DOMException && e.name === 'NotAllowedError'
            ? translate('capture.errors.cameraDenied')
            : translate('capture.errors.cameraFailed', { message: errorMessage(e) }),
        );
        return false;
      }
    },
    [refreshDevices, releaseDisplay],
  );

  /** Gewählten Bildschirm wieder freigeben; Mikrofon und dessen Auswahl bleiben. */
  const discardSource = useCallback(() => {
    releaseDisplay();
    setSystemAudio(false);
    setPhase('idle');
  }, [releaseDisplay]);

  /**
   * Die Spuren für den Recorder: Mixer-Ausgang plus – bei Bild – die Bildspur.
   * Wo der Browser es kann, ist das die Spur eines {@link VideoRelay}, damit sich
   * der Bildschirm später wechseln lässt; sonst die Bildspur selbst.
   */
  const buildRecorderStream = useCallback((): MediaStream => {
    ensureMixer();
    const destination = destinationRef.current;
    if (!destination) throw new Error('Mixer nicht verfügbar');
    const tracks: MediaStreamTrack[] = [];
    let switchable = !withVideo;
    const videoTrack = displayStreamRef.current?.getVideoTracks()[0];
    if (withVideo && videoTrack) {
      relayRef.current?.stop();
      relayRef.current = null;
      if (videoRelaySupported()) {
        try {
          relayRef.current = new VideoRelay(videoTrack);
          switchable = true;
        } catch {
          relayRef.current = null;
        }
      }
      tracks.push(relayRef.current?.track ?? videoTrack);
    }
    tracks.push(...destination.stream.getAudioTracks());
    setCanSwitchSource(switchable);
    return new MediaStream(tracks);
  }, [ensureMixer, withVideo]);

  /** Ein Stück mit Wiederholungen hochladen; gibt bei endgültigem Fehler auf. */
  const uploadChunk = useCallback(async (recordingId: string, seq: number, blob: Blob) => {
    let attempt = 0;
    for (;;) {
      try {
        await uploadCaptureChunk(recordingId, seq, blob);
        return;
      } catch (e) {
        const status = e instanceof ApiError ? e.status : 0;
        attempt += 1;
        if (FATAL_STATUS.has(status) || attempt >= MAX_CHUNK_ATTEMPTS) throw e;
        await new Promise((resolve) => window.setTimeout(resolve, RETRY_BASE_MS * 2 ** (attempt - 1)));
      }
    }
  }, []);

  /** Warteschlange strikt der Reihe nach abarbeiten. */
  const pump = useCallback(async () => {
    if (pumpingRef.current || fatalRef.current) return;
    pumpingRef.current = true;
    try {
      while (queueRef.current.length > 0) {
        const recordingId = recordingIdRef.current;
        if (!recordingId) break;
        const blob = queueRef.current[0];
        await uploadChunk(recordingId, seqRef.current, blob);
        queueRef.current.shift();
        seqRef.current += 1;
        setUploadedBytes((bytes) => bytes + blob.size);
        setPendingChunks(queueRef.current.length);
      }
    } catch (e) {
      // Der Server hat alles bis zum letzten bestätigten Stück – die Aufnahme
      // ist also nicht verloren, sie endet nur hier.
      fatalRef.current = errorMessage(e);
      queueRef.current = [];
      setPendingChunks(0);
      setError(translate('capture.errors.transferAborted', { message: errorMessage(e) }));
      try {
        recorderRef.current?.stop();
      } catch {
        // Recorder war bereits beendet
      }
      releaseMedia();
      setPhase('error');
    } finally {
      pumpingRef.current = false;
    }
  }, [releaseMedia, uploadChunk]);

  /** Aufnahme beenden: Recorder stoppen, Rest hochladen, Server abschließen lassen. */
  const finish = useCallback(async () => {
    if (finishingRef.current) return;
    const recordingId = recordingIdRef.current;
    if (!recordingId) return;
    finishingRef.current = true;
    setPhase('finishing');

    const recorder = recorderRef.current;
    if (recorder && recorder.state !== 'inactive') {
      await new Promise<void>((resolve) => {
        recorder.onstop = () => resolve();
        try {
          recorder.stop();
        } catch {
          resolve();
        }
      });
    }
    releaseMedia();

    // Auf die letzten Stücke warten – ohne sie fehlt das Ende der Aufnahme.
    void pump();
    const deadline = Date.now() + 120_000;
    while ((queueRef.current.length > 0 || pumpingRef.current) && Date.now() < deadline) {
      if (fatalRef.current) break;
      await new Promise((resolve) => window.setTimeout(resolve, 200));
    }
    if (fatalRef.current) {
      finishingRef.current = false;
      return; // Fehlerzustand hat pump bereits gesetzt
    }

    try {
      const recording = await stopCapture(recordingId);
      setResult(recording);
      setPhase('done');
    } catch (e) {
      setError(translate('capture.errors.finishFailed', { message: errorMessage(e) }));
      setPhase('error');
    } finally {
      recordingIdRef.current = null;
      finishingRef.current = false;
    }
  }, [pump, releaseMedia]);

  const beginRecording = useCallback(
    async (options: BeginRecordingOptions) => {
      if (!displayStreamRef.current) return;
      setError(null);
      setPhase('starting');
      fatalRef.current = null;
      queueRef.current = [];
      seqRef.current = 0;
      setUploadedBytes(0);
      setPendingChunks(0);

      const mimeType = pickMimeType(withVideo);
      if (!mimeType) {
        setError(translate('capture.errors.noFormat'));
        setPhase('error');
        return;
      }

      let stream: MediaStream;
      try {
        stream = buildRecorderStream();
      } catch (e) {
        setError(translate('capture.errors.mixFailed', { message: errorMessage(e) }));
        setPhase('error');
        return;
      }

      let recording: RecordingView;
      try {
        recording = await startCapture({
          title: options.title,
          aiAnalysis: options.aiAnalysis,
          processNow: options.processNow,
          diarize: options.diarize,
          video: withVideo,
          sttLanguage: options.sttLanguage,
          mimeType,
        });
      } catch (e) {
        // Quelle und Tonmischung stehen noch – der Nutzer kann es direkt
        // erneut versuchen, ohne den Bildschirm neu auszuwählen.
        relayRef.current?.stop();
        relayRef.current = null;
        setError(errorMessage(e));
        setPhase('ready');
        return;
      }
      recordingIdRef.current = recording.id;

      const recorder = new MediaRecorder(stream, {
        mimeType,
        audioBitsPerSecond: AUDIO_BITS_PER_SECOND,
        ...(withVideo ? { videoBitsPerSecond: QUALITY_PRESETS[quality].videoBitsPerSecond } : {}),
      });
      recorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          queueRef.current.push(event.data);
          setPendingChunks(queueRef.current.length);
          void pump();
        }
      };
      recorder.onerror = () => {
        setError(translate('capture.errors.recorderAborted'));
        setPhase('error');
      };
      recorder.start(CHUNK_MS);

      watchDisplayEnd(displayStreamRef.current, finish);

      startedAtRef.current = Date.now();
      pausedTotalRef.current = 0;
      setElapsedMs(0);
      timersRef.current.push(
        window.setInterval(() => {
          if (pausedAtRef.current > 0) return;
          setElapsedMs(Date.now() - startedAtRef.current - pausedTotalRef.current);
        }, 500),
      );
      timersRef.current.push(
        window.setInterval(() => {
          const id = recordingIdRef.current;
          if (id) void captureHeartbeat(id).catch(() => undefined);
        }, HEARTBEAT_MS),
      );
      setPhase('recording');
    },
    [buildRecorderStream, finish, pump, quality, withVideo],
  );

  /**
   * Während der Aufnahme eine neue Bildquelle einhängen. Die Aufnahme läuft
   * weiter, solange die neue Quelle geöffnet wird (das Bild steht kurz).
   */
  const replaceSource = useCallback(
    (stream: MediaStream, kind: CaptureSourceKind) => {
      // Die Aufnahme kann geendet haben, während der Dialog offen war.
      if (!recordingIdRef.current || finishingRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return false;
      }
      if (withVideoRef.current) {
        const videoTrack = stream.getVideoTracks()[0];
        if (!videoTrack || !relayRef.current) {
          stream.getTracks().forEach((t) => t.stop());
          return false;
        }
        relayRef.current.setSource(videoTrack);
        setPreviewStream(stream);
      } else {
        stream.getVideoTracks().forEach((t) => t.stop());
      }
      // Erst jetzt die alte Quelle schließen – ohne Ende-Handler, sonst würde das
      // als „Freigabe beendet" die ganze Aufnahme abschließen.
      displayStreamRef.current?.getTracks().forEach((t) => {
        t.onended = null;
        t.stop();
      });
      displayStreamRef.current = stream;
      connectSystem(stream);
      setSystemAudio(stream.getAudioTracks().length > 0);
      setSourceKind(kind);
      watchDisplayEnd(stream, finish);
      return true;
    },
    [connectSystem, finish],
  );

  /**
   * Während der Aufnahme eine andere Bildschirmquelle wählen. Öffnet erneut den
   * Auswahldialog des Browsers. Bricht der Nutzer ab, bleibt die bisherige Quelle.
   */
  const switchSource = useCallback(async () => {
    if (!recordingIdRef.current || finishingRef.current) return false;
    if (withVideoRef.current && !relayRef.current) return false;
    setError(null);
    setSwitching(true);
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getDisplayMedia(displayOptions(qualityRef.current));
    } catch (e) {
      if (!isUserAbort(e)) {
        setError(translate('capture.errors.switchFailed', { message: errorMessage(e) }));
      }
      return false;
    } finally {
      setSwitching(false);
    }
    return replaceSource(stream, 'screen');
  }, [replaceSource]);

  /** Während der Aufnahme auf eine (andere) Kamera umschalten. */
  const switchToCamera = useCallback(
    async (deviceId: string) => {
      if (!recordingIdRef.current || finishingRef.current) return false;
      if (!withVideoRef.current || !relayRef.current) return false;
      setError(null);
      setSwitching(true);
      let stream: MediaStream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: cameraConstraints(deviceId, qualityRef.current),
          audio: false,
        });
      } catch (e) {
        setError(translate('capture.errors.switchFailed', { message: errorMessage(e) }));
        return false;
      } finally {
        setSwitching(false);
      }
      const ok = replaceSource(stream, 'camera');
      if (ok) {
        setCameraDeviceId(stream.getVideoTracks()[0]?.getSettings().deviceId ?? deviceId);
        void refreshDevices();
      }
      return ok;
    },
    [refreshDevices, replaceSource],
  );

  const pause = useCallback(() => {
    const recorder = recorderRef.current;
    if (!recorder || recorder.state !== 'recording') return;
    recorder.pause();
    pausedAtRef.current = Date.now();
    setPhase('paused');
  }, []);

  const resume = useCallback(() => {
    const recorder = recorderRef.current;
    if (!recorder || recorder.state !== 'paused') return;
    recorder.resume();
    if (pausedAtRef.current > 0) {
      pausedTotalRef.current += Date.now() - pausedAtRef.current;
      pausedAtRef.current = 0;
    }
    setPhase('recording');
  }, []);

  /** Aufnahme verwerfen (Server löscht alle Daten). */
  const cancel = useCallback(async () => {
    const recordingId = recordingIdRef.current;
    const recorder = recorderRef.current;
    if (recorder && recorder.state !== 'inactive') {
      try {
        recorder.stop();
      } catch {
        // bereits beendet
      }
    }
    queueRef.current = [];
    setPendingChunks(0);
    releaseMedia();
    recordingIdRef.current = null;
    setPhase('idle');
    if (recordingId) {
      try {
        await abortCapture(recordingId);
      } catch {
        // Der Aufräumlauf des Servers fängt es ab
      }
    }
  }, [releaseMedia]);

  return {
    phase,
    error,
    result,
    previewStream,
    withVideo,
    sourceKind,
    cameraDevices,
    cameraDeviceId,
    systemAudio,
    micDevices,
    micDeviceId,
    micActive,
    micError,
    micMuted,
    canSwitchSource,
    switching,
    levels,
    elapsedMs,
    uploadedBytes,
    pendingChunks,
    chooseSource,
    discardSource,
    chooseCamera,
    refreshDevices,
    enableMic,
    disableMic,
    setMicMute,
    switchSource,
    switchToCamera,
    beginRecording,
    pause,
    resume,
    finish,
    cancel,
  };
}

/**
 * Beendet der Nutzer die Freigabe über die Leiste des Browsers, ist das ein
 * regulärer Stopp – die Aufnahme wird abgeschlossen, nicht verworfen. Bei „nur
 * Ton" gibt es keine Bildspur mehr, dort endet die Tonspur. (Selbst gestoppte
 * Spuren lösen kein `ended` aus, also kein Fehlalarm.)
 */
function watchDisplayEnd(stream: MediaStream, finish: () => Promise<void>) {
  stream
    .getTracks()
    .filter((track) => track.readyState === 'live')
    .forEach((track) => {
      track.onended = () => void finish();
    });
}
