// In die BBB-Seite injiziertes Aufnahme-Skript.
// Mischt alle Remote-Audio-Streams ueber WebAudio zusammen und streamt
// MediaRecorder-Chunks (webm/opus) ueber das Playwright-Binding
// "node_receiveAudioChunk" ins Backend. 1:1-Portierung des bewaehrten
// Verhaltens aus dem alten Node-Bot (src/recorder.ts).
(segmentMs) => {
  const nodeBinding = window['node_receiveAudioChunk'];
  if (!nodeBinding) throw new Error('node_receiveAudioChunk binding not found (unexpected).');

  function collectRemoteAudioElements() {
    const audios = Array.from(document.querySelectorAll('audio'));
    return audios.filter(a => {
      try {
        const s = a.srcObject;
        return !!(s && s.getAudioTracks && s.getAudioTracks().length > 0);
      } catch { return false; }
    });
  }

  const ctx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
  const dest = ctx.createMediaStreamDestination();
  // Alle Quellen laufen ueber Mix -> Analyser -> Aufnahme. Der Analyser reicht
  // das Signal unveraendert durch und liefert die Pegel-Diagnose
  // (window.__BBB_RECORDER_LEVELS__). Haengt er nur seitlich am Mix ohne
  // Ausgang, verarbeitet Chrome ihn nicht und misst dauerhaft Stille.
  const mix = ctx.createGain();
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 2048;
  mix.connect(analyser);
  analyser.connect(dest);
  const connected = new WeakSet();
  let sourceCount = 0;

  const attachAll = () => {
    const remotes = collectRemoteAudioElements();
    for (const el of remotes) {
      const s = el.srcObject;
      if (!s || connected.has(s)) continue;
      try {
        const src = ctx.createMediaStreamSource(s);
        src.connect(mix);
        connected.add(s);
        sourceCount++;
      } catch {}
    }
  };

  attachAll();
  const pollId = window.setInterval(attachAll, 1500);

  // Pegel-Messung: alle 250 ms RMS und Spitze des Mixes; das Backend holt die
  // Werte regelmaessig ab (und setzt sie dabei zurueck).
  const SILENCE_RMS = 0.001; // ca. -60 dBFS
  const levelBuf = new Float32Array(analyser.fftSize);
  let lvSumSq = 0, lvN = 0, lvPeak = 0, lvSilent = 0, lvSamples = 0;
  const levelId = window.setInterval(() => {
    analyser.getFloatTimeDomainData(levelBuf);
    let sq = 0, pk = 0;
    for (const v of levelBuf) { sq += v * v; const a = Math.abs(v); if (a > pk) pk = a; }
    const rms = Math.sqrt(sq / levelBuf.length);
    lvSumSq += sq; lvN += levelBuf.length;
    if (pk > lvPeak) lvPeak = pk;
    if (rms < SILENCE_RMS) lvSilent++;
    lvSamples++;
  }, 250);
  const toDb = v => v > 0 ? Math.round(20 * Math.log10(v) * 10) / 10 : -120;
  window.__BBB_RECORDER_LEVELS__ = () => {
    const out = {
      rmsDb: toDb(lvN ? Math.sqrt(lvSumSq / lvN) : 0),
      peakDb: toDb(lvPeak),
      silentPct: lvSamples ? Math.round(100 * lvSilent / lvSamples) : 100,
      sources: sourceCount,
      ctxState: ctx.state,
      recorderState: recorder.state
    };
    lvSumSq = 0; lvN = 0; lvPeak = 0; lvSilent = 0; lvSamples = 0;
    return out;
  };

  const mime = 'audio/webm;codecs=opus';
  const timeslice = 1500;
  let stopped = false;

  // Alle Sendungen laufen strikt nacheinander ueber eine Kette. Das Ende einer
  // Segmentdatei (last=true) haengt am stop-Ereignis des jeweiligen Recorders,
  // das nach dessen letztem dataavailable feuert. Frueher markierte ein
  // gemeinsames Flag "naechster Chunk ist der letzte" - solange der Versand
  // ans Backend dauerte, bekamen auch der Rest des alten und der erste Chunk
  // des neuen Recorders (mit dem WebM-Header) das Flag. Folge: ein Segment
  // von ~1 s mit Header und danach ein Segment ohne Header (defekt), Issue #31.
  let sendQueue = Promise.resolve();
  const enqueue = (bytes, last) => {
    sendQueue = sendQueue
      .then(async () => {
        const arr = bytes ? Array.from(new Uint8Array(await bytes.arrayBuffer())) : [];
        await nodeBinding({ bytes: arr, last });
      })
      .catch(() => {});
    return sendQueue;
  };

  const startRecorder = () => {
    const r = new MediaRecorder(dest.stream, { mimeType: mime, audioBitsPerSecond: 128000 });
    r.ondataavailable = e => {
      if (e.data && e.data.size > 0) enqueue(e.data, false);
    };
    r.addEventListener('stop', () => enqueue(null, true), { once: true });
    r.start(timeslice);
    return r;
  };

  let recorder = startRecorder();

  const rotate = () => {
    if (stopped || recorder.state !== 'recording') return;
    const old = recorder;
    // stop() liefert die Restdaten und danach das stop-Ereignis; beides landet
    // in der Kette vor dem ersten Chunk des neuen Recorders (erst nach 1,5 s).
    try { old.stop(); } catch {}
    recorder = startRecorder();
  };

  let segTimer = null;
  if (segmentMs && segmentMs > 0) {
    segTimer = window.setInterval(rotate, segmentMs);
  }

  window.__BBB_RECORDER_STOP__ = async () => {
    if (stopped) return;
    stopped = true;
    if (segTimer) window.clearInterval(segTimer);
    window.clearInterval(pollId);
    window.clearInterval(levelId);
    delete window.__BBB_RECORDER_LEVELS__;

    if (recorder.state !== 'inactive') {
      await new Promise(res => {
        recorder.addEventListener('stop', () => res(), { once: true });
        try {
          recorder.stop();
        } catch {
          enqueue(null, true);
          res();
        }
      });
    } else {
      enqueue(null, true);
    }
    // Erst zurueckkehren, wenn auch der Ende-Marker beim Backend ist.
    await sendQueue;
    try { mix.disconnect(); } catch {}
    try { analyser.disconnect(); } catch {}
    try { dest.disconnect(); } catch {}
    try { ctx.close(); } catch {}
  };
}
