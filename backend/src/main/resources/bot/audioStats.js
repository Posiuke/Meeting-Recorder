// Zaehlt aktive Remote-Audiotracks (Grundlage fuer Start-/Stall-Entscheidung).
() => {
  const audios = Array.from(document.querySelectorAll('audio'));
  let tracks = 0;
  for (const a of audios) {
    try {
      const s = a.srcObject;
      if (s && s.getAudioTracks) {
        tracks += s.getAudioTracks().filter(t => t.enabled).length;
      }
    } catch {}
  }
  return tracks;
}
