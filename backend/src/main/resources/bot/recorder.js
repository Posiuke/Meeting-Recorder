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
  const connected = new WeakSet();

  const attachAll = () => {
    const remotes = collectRemoteAudioElements();
    for (const el of remotes) {
      const s = el.srcObject;
      if (!s || connected.has(s)) continue;
      try {
        const src = ctx.createMediaStreamSource(s);
        src.connect(dest);
        connected.add(s);
      } catch {}
    }
  };

  attachAll();
  const pollId = window.setInterval(attachAll, 1500);

  const mime = 'audio/webm;codecs=opus';
  let recorder = new MediaRecorder(dest.stream, { mimeType: mime, audioBitsPerSecond: 128000 });
  let sendLastPending = false;
  let stopped = false;

  const sendChunk = async (blob, last) => {
    if (!blob || blob.size === 0) {
      if (last) {
        const silence = new Blob([new Uint8Array(1)], { type: mime });
        const ab = await silence.arrayBuffer();
        await nodeBinding({ bytes: Array.from(new Uint8Array(ab)), last: true });
      }
      return;
    }
    const ab = await blob.arrayBuffer();
    await nodeBinding({ bytes: Array.from(new Uint8Array(ab)), last });
  };

  const onData = async e => {
    if (e.data && e.data.size > 0) {
      const last = sendLastPending;
      await sendChunk(e.data, last);
      if (last) sendLastPending = false;
    }
  };

  recorder.ondataavailable = onData;
  const timeslice = 1500;
  recorder.start(timeslice);

  const rotate = () => {
    if (recorder.state === 'recording') {
      sendLastPending = true;
      try {
        recorder.requestData();
        recorder.stop();
      } catch {}
      recorder = new MediaRecorder(dest.stream, { mimeType: mime, audioBitsPerSecond: 128000 });
      recorder.ondataavailable = onData;
      recorder.start(timeslice);
    }
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

    if (recorder.state !== 'inactive') {
      await new Promise(res => {
        sendLastPending = true;
        recorder.addEventListener('stop', () => res(), { once: true });
        try {
          recorder.requestData();
          recorder.stop();
        } catch {
          res();
        }
      });
    }
    try { dest.disconnect(); } catch {}
    try { ctx.close(); } catch {}
  };
}
