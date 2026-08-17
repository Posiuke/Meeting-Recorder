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
 */
export function useScreenRecorder() {
  const [phase, setPhase] = useState<CapturePhase>('idle');
  const [error, setError] = useState<string | null>(null);
  const [previewStream, setPreviewStream] = useState<MediaStream | null>(null);
  const [withVideo, setWithVideo] = useState(true);
  const [quality, setQuality] = useState<CaptureQuality>('standard');
  const [systemAudio, setSystemAudio] = useState(false);
  const [micDevices, setMicDevices] = useState<MediaDeviceInfo[]>([]);
  const [micDeviceId, setMicDeviceId] = useState<string>('');
  const [micActive, setMicActive] = useState(false);
  const [micError, setMicError] = useState<string | null>(null);
  const [levels, setLevels] = useState<AudioLevels>({ system: 0, mic: 0 });
  const [elapsedMs, setElapsedMs] = useState(0);
  const [uploadedBytes, setUploadedBytes] = useState(0);
  const [pendingChunks, setPendingChunks] = useState(0);
  const [result, setResult] = useState<RecordingView | null>(null);

  const displayStreamRef = useRef<MediaStream | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const systemAnalyserRef = useRef<AnalyserNode | null>(null);
  const micAnalyserRef = useRef<AnalyserNode | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const recordingIdRef = useRef<string | null>(null);

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

  /** Alle Medienressourcen freigeben (Freigabe-Hinweis des Browsers verschwindet). */
  const releaseMedia = useCallback(() => {
    clearTimers();
    recorderRef.current = null;
    displayStreamRef.current?.getTracks().forEach((t) => t.stop());
    displayStreamRef.current = null;
    micStreamRef.current?.getTracks().forEach((t) => t.stop());
    micStreamRef.current = null;
    systemAnalyserRef.current = null;
    micAnalyserRef.current = null;
    const ctx = audioCtxRef.current;
    audioCtxRef.current = null;
    if (ctx && ctx.state !== 'closed') void ctx.close();
    setPreviewStream(null);
    setMicActive(false);
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

  const refreshMicDevices = useCallback(async () => {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      setMicDevices(devices.filter((d) => d.kind === 'audioinput'));
    } catch {
      setMicDevices([]);
    }
  }, []);

  /**
   * Mikrofon aktivieren. Die Geräteliste trägt erst nach erteilter Berechtigung
   * lesbare Namen – deshalb wird hier zuerst der Zugriff angefragt.
   */
  const enableMic = useCallback(
    async (deviceId?: string) => {
      setMicError(null);
      try {
        micStreamRef.current?.getTracks().forEach((t) => t.stop());
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: deviceId ? { deviceId: { exact: deviceId } } : true,
        });
        micStreamRef.current = stream;
        setMicActive(true);
        if (deviceId) setMicDeviceId(deviceId);
        else {
          const active = stream.getAudioTracks()[0]?.getSettings().deviceId;
          if (active) setMicDeviceId(active);
        }
        await refreshMicDevices();
        return true;
      } catch (e) {
        micStreamRef.current = null;
        setMicActive(false);
        setMicError(
          e instanceof DOMException && e.name === 'NotAllowedError'
            ? translate('capture.errors.micDenied')
            : translate('capture.errors.micFailed', { message: errorMessage(e) }),
        );
        return false;
      }
    },
    [refreshMicDevices],
  );

  const disableMic = useCallback(() => {
    micStreamRef.current?.getTracks().forEach((t) => t.stop());
    micStreamRef.current = null;
    setMicActive(false);
    setMicError(null);
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
      const preset = QUALITY_PRESETS[selectedQuality];
      const options: DisplayMediaOptions = {
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
      try {
        const stream = await navigator.mediaDevices.getDisplayMedia(options);
        displayStreamRef.current?.getTracks().forEach((t) => t.stop());
        displayStreamRef.current = stream;
        setSystemAudio(stream.getAudioTracks().length > 0);
        setWithVideo(video);
        setQuality(selectedQuality);

        if (video) {
          setPreviewStream(stream);
        } else {
          // Nur Ton: Bildspur sofort beenden, dann sieht der Nutzer auch keinen
          // laufenden Bildschirm-Mitschnitt in der Browserleiste mehr als nötig.
          stream.getVideoTracks().forEach((t) => t.stop());
          setPreviewStream(null);
        }
        setPhase('ready');
        return true;
      } catch (e) {
        if (e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError')) {
          // Nutzer hat den Auswahldialog abgebrochen – kein Fehlerzustand.
          return false;
        }
        setError(translate('capture.errors.displayFailed', { message: errorMessage(e) }));
        return false;
      }
    },
    [],
  );

  /** Vorbereitete Quelle wieder freigeben (z.B. beim Schließen des Dialogs). */
  const discardSource = useCallback(() => {
    releaseMedia();
    setSystemAudio(false);
    setPhase('idle');
  }, [releaseMedia]);

  /** Ton der Quellen mischen und die Pegelmessung anhängen. */
  const buildMixedStream = useCallback((): MediaStream => {
    const display = displayStreamRef.current;
    // Ein fehlgeschlagener Startversuch kann einen Kontext hinterlassen haben.
    const previous = audioCtxRef.current;
    if (previous && previous.state !== 'closed') void previous.close();
    const ctx = new AudioContext();
    audioCtxRef.current = ctx;
    const destination = ctx.createMediaStreamDestination();

    const systemTracks = display?.getAudioTracks() ?? [];
    if (systemTracks.length > 0) {
      const source = ctx.createMediaStreamSource(new MediaStream(systemTracks));
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      source.connect(analyser);
      source.connect(destination);
      systemAnalyserRef.current = analyser;
    }
    const micStream = micStreamRef.current;
    if (micStream) {
      const source = ctx.createMediaStreamSource(micStream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      source.connect(analyser);
      source.connect(destination);
      micAnalyserRef.current = analyser;
    }

    const tracks: MediaStreamTrack[] = [];
    const videoTrack = display?.getVideoTracks()[0];
    if (withVideo && videoTrack) tracks.push(videoTrack);
    tracks.push(...destination.stream.getAudioTracks());
    return new MediaStream(tracks);
  }, [withVideo]);

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
        stream = buildMixedStream();
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

      // Beendet der Nutzer die Freigabe über die Leiste des Browsers, ist das
      // ein regulärer Stopp – die Aufnahme wird abgeschlossen, nicht verworfen.
      // Bei „nur Ton" gibt es keine Bildspur mehr, dort endet die Tonspur.
      // (Selbst gestoppte Spuren lösen kein `ended` aus, also kein Fehlalarm.)
      displayStreamRef.current
        .getTracks()
        .filter((track) => track.readyState === 'live')
        .forEach((track) => {
          track.onended = () => void finish();
        });

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
      timersRef.current.push(
        window.setInterval(() => {
          setLevels({
            system: readLevel(systemAnalyserRef.current),
            mic: readLevel(micAnalyserRef.current),
          });
        }, 100),
      );
      setPhase('recording');
    },
    [buildMixedStream, finish, pump, quality, withVideo],
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
    systemAudio,
    micDevices,
    micDeviceId,
    micActive,
    micError,
    levels,
    elapsedMs,
    uploadedBytes,
    pendingChunks,
    chooseSource,
    discardSource,
    enableMic,
    disableMic,
    beginRecording,
    pause,
    resume,
    finish,
    cancel,
  };
}

/** Lautstärke einer Quelle als Wert zwischen 0 und 1 (Effektivwert). */
function readLevel(analyser: AnalyserNode | null): number {
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
