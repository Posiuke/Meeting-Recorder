/**
 * Gleichbleibende Bildspur für den MediaRecorder, deren Quelle sich während der
 * Aufnahme austauschen lässt.
 *
 * Hintergrund: Ein `MediaRecorder` bricht ab, sobald sich der Satz seiner Spuren
 * ändert. Damit der Nutzer mitten in der Aufnahme einen anderen Bildschirm wählen
 * kann, bekommt der Recorder deshalb nicht die Bildspur des Bildschirms, sondern
 * eine eigene Spur (`MediaStreamTrackGenerator`), in die die Bilder der jeweils
 * aktuellen Quelle durchgereicht werden (`MediaStreamTrackProcessor`).
 *
 * Bewusst kein Canvas mit Zeichen-Timer: Wer seinen Bildschirm aufnimmt, hat
 * dieses Tool meist im Hintergrund, und dort drosselt der Browser Timer und
 * `requestAnimationFrame` drastisch. Die Bildstrom-Kette hier wird dagegen von
 * den eintreffenden Bildern selbst angetrieben und läuft ungebremst.
 *
 * Alle Bilder werden auf die Größe der ersten Quelle gebracht (andere Formate
 * mit schwarzem Rand eingepasst) – so bleibt die Datei für die Umwandlung auf
 * dem Server ein Strom mit einheitlicher Auflösung.
 */

interface TrackProcessor {
  readable: ReadableStream<VideoFrame>;
}
type TrackProcessorCtor = new (init: { track: MediaStreamTrack }) => TrackProcessor;
type GeneratorTrack = MediaStreamTrack & { writable: WritableStream<VideoFrame> };
type TrackGeneratorCtor = new (init: { kind: 'video' }) => GeneratorTrack;

interface BreakoutBoxWindow {
  MediaStreamTrackProcessor?: TrackProcessorCtor;
  MediaStreamTrackGenerator?: TrackGeneratorCtor;
  VideoFrame?: unknown;
  OffscreenCanvas?: unknown;
}

function breakoutBox(): { Processor: TrackProcessorCtor; Generator: TrackGeneratorCtor } | null {
  if (typeof window === 'undefined') return null;
  const w = window as unknown as BreakoutBoxWindow;
  if (!w.MediaStreamTrackProcessor || !w.MediaStreamTrackGenerator || !w.VideoFrame || !w.OffscreenCanvas) {
    return null;
  }
  return { Processor: w.MediaStreamTrackProcessor, Generator: w.MediaStreamTrackGenerator };
}

/** Kann dieser Browser die Bildquelle während der Aufnahme wechseln? (Chrome/Edge) */
export function videoRelaySupported(): boolean {
  return breakoutBox() !== null;
}

/** Auf gerade Werte runden – Video-Encoder mögen keine ungeraden Kantenlängen. */
function even(value: number): number {
  return Math.max(2, Math.round(value / 2) * 2);
}

export class VideoRelay {
  /** Die Spur, die der Recorder aufnimmt. Sie bleibt über alle Wechsel dieselbe. */
  readonly track: MediaStreamTrack;

  private readonly Processor: TrackProcessorCtor;
  private readonly writer: WritableStreamDefaultWriter<VideoFrame>;
  private readonly width: number;
  private readonly height: number;
  private canvas: OffscreenCanvas | null = null;
  private reader: ReadableStreamDefaultReader<VideoFrame> | null = null;
  /** Erhöht sich bei jedem Wechsel; ältere Lese-Schleifen beenden sich daran. */
  private generation = 0;
  private lastTimestamp = 0;
  private stopped = false;

  constructor(source: MediaStreamTrack) {
    const api = breakoutBox();
    if (!api) throw new Error('MediaStreamTrackGenerator wird nicht unterstützt');
    this.Processor = api.Processor;
    const generator = new api.Generator({ kind: 'video' });
    this.track = generator;
    this.writer = generator.writable.getWriter();
    const settings = source.getSettings();
    this.width = even(settings.width ?? 1920);
    this.height = even(settings.height ?? 1080);
    this.setSource(source);
  }

  /** Ab sofort die Bilder dieser Spur durchreichen. */
  setSource(source: MediaStreamTrack) {
    if (this.stopped) return;
    const generation = ++this.generation;
    const previous = this.reader;
    const reader = new this.Processor({ track: source }).readable.getReader();
    this.reader = reader;
    void previous?.cancel().catch(() => undefined);
    void this.pump(reader, generation);
  }

  stop() {
    if (this.stopped) return;
    this.stopped = true;
    this.generation += 1;
    void this.reader?.cancel().catch(() => undefined);
    this.reader = null;
    void this.writer.close().catch(() => undefined);
    this.track.stop();
  }

  private async pump(reader: ReadableStreamDefaultReader<VideoFrame>, generation: number) {
    try {
      for (;;) {
        const { value: frame, done } = await reader.read();
        if (done || !frame) return;
        if (generation !== this.generation) {
          frame.close();
          return;
        }
        await this.forward(frame);
      }
    } catch {
      // Quelle beendet oder Wechsel – nichts zu tun, die nächste Quelle übernimmt.
    }
  }

  private async forward(frame: VideoFrame) {
    // Eigene, streng steigende Zeitstempel: Bilder verschiedener Quellen haben
    // keinen gemeinsamen Zeitbezug, der Recorder braucht aber einen durchgehenden.
    const timestamp = Math.max(this.lastTimestamp + 1, Math.round(performance.now() * 1000));
    this.lastTimestamp = timestamp;
    let out: VideoFrame;
    try {
      if (frame.displayWidth === this.width && frame.displayHeight === this.height) {
        out = new VideoFrame(frame, { timestamp });
      } else {
        out = new VideoFrame(this.letterbox(frame), { timestamp });
      }
    } finally {
      frame.close();
    }
    // Das Schreiben übergibt das Bild an die Spur, die es danach selbst freigibt.
    await this.writer.write(out);
  }

  /** Bild formatfüllend mit schwarzem Rand in die feste Ausgabegröße einpassen. */
  private letterbox(frame: VideoFrame): OffscreenCanvas {
    if (!this.canvas) this.canvas = new OffscreenCanvas(this.width, this.height);
    const ctx = this.canvas.getContext('2d');
    if (!ctx) throw new Error('2D-Kontext nicht verfügbar');
    const scale = Math.min(this.width / frame.displayWidth, this.height / frame.displayHeight);
    const w = frame.displayWidth * scale;
    const h = frame.displayHeight * scale;
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, this.width, this.height);
    ctx.drawImage(frame, (this.width - w) / 2, (this.height - h) / 2, w, h);
    return this.canvas;
  }
}
