// Diagnose: Audio-Zustand der BBB-Seite als eine Logzeile.
// mode: listen-only | microphone-open | microphone-muted | none | unknown
// (abgeleitet aus den sichtbaren Toolbar-Buttons), dazu Audio-Modal,
// sichtbare Audio-Buttons und alle <audio>-Elemente mit ihren Spuren.
() => {
  const visible = el => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  const shown = sel => Array.from(document.querySelectorAll(sel)).some(visible);

  const BUTTONS = ['listenOnlyBtn', 'microphoneBtn', 'joinBtn', 'echoYesBtn', 'joinEchoTestButton',
    'joinAudio', 'leaveAudio', 'leaveListenOnly', 'muteMicButton', 'unmuteMicButton'];
  const buttons = BUTTONS.filter(b => shown('[data-test="' + b + '"]'));
  const has = b => buttons.includes(b);

  let mode = 'unknown';
  if (has('muteMicButton')) mode = 'microphone-open';
  else if (has('unmuteMicButton')) mode = 'microphone-muted';
  else if (has('leaveListenOnly') || has('leaveAudio')) mode = 'listen-only';
  else if (has('joinAudio')) mode = 'none';

  const audios = Array.from(document.querySelectorAll('audio')).map(a => {
    const name = a.id ? '#' + a.id : (a.className ? '.' + String(a.className).split(' ')[0] : 'audio');
    const s = a.srcObject;
    let src = 'none';
    if (s && s.getAudioTracks) {
      const tracks = s.getAudioTracks();
      const live = tracks.filter(t => t.readyState === 'live').length;
      const enabled = tracks.filter(t => t.enabled && !t.muted).length;
      src = 'stream tracks=' + tracks.length + ' live=' + live + ' aktiv=' + enabled;
    } else if (a.currentSrc) {
      src = 'url';
    }
    return name + '(' + src + ', paused=' + a.paused + ', ready=' + a.readyState + ')';
  });

  const modal = shown('[data-test="audioModal"]');
  const summary = 'modus=' + mode
    + ' | audioModal=' + (modal ? 'offen' : 'zu')
    + ' | buttons=[' + buttons.join(',') + ']'
    + ' | audio=[' + audios.join('; ') + ']'
    + ' | seite=' + location.host + location.pathname;
  return { mode, summary };
}
