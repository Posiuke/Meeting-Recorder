// Protokolliert die Sprechanzeige von BBB ("wer spricht gerade") waehrend der
// Aufnahme. BBB zeigt Sprechende als Buttons mit data-test="isTalking" (bzw.
// "wasTalking" fuer kurz zuvor Sprechende); der Name steht im Text bzw. im
// aria-label. Alle 500 ms abgetastet; Zeiten in Sekunden ab Aufnahmestart.
//
// Das Backend holt die abgeschlossenen Intervalle regelmaessig ab
// (window.__BBB_TALK_LOG_DRAIN__) - so geht bei einem Absturz der Seite
// hoechstens das letzte Abfrageintervall verloren.
(botName) => {
  if (window.__BBB_TALK_LOG_DRAIN__) return;
  const t0 = performance.now();
  const now = () => (performance.now() - t0) / 1000;
  const open = new Map();   // Name -> Startzeit
  let closed = [];          // [{name, start, end}]
  const self = (botName || '').trim().toLowerCase();

  const talkingNames = () => {
    const names = new Set();
    const nodes = document.querySelectorAll('[data-test="isTalking"]');
    for (const el of nodes) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) continue;
      let name = (el.innerText || '').trim();
      if (!name) {
        // aria-label z.B. "Anna is talking" / "Anna spricht"
        name = (el.getAttribute('aria-label') || '')
          .replace(/\s+(is talking|spricht|was talking|sprach)\s*$/i, '').trim();
      }
      name = name.split('\n')[0].trim();
      if (name && name.toLowerCase() !== self) names.add(name);
    }
    return names;
  };

  const tick = () => {
    const t = now();
    const current = talkingNames();
    for (const name of current) {
      if (!open.has(name)) open.set(name, t);
    }
    for (const [name, start] of open) {
      if (!current.has(name)) {
        open.delete(name);
        closed.push({ name, start, end: t });
      }
    }
  };
  const id = window.setInterval(tick, 500);

  // Liefert und vergisst die abgeschlossenen Intervalle. Mit final=true werden
  // auch die noch offenen geschlossen und die Abtastung beendet.
  window.__BBB_TALK_LOG_DRAIN__ = (final) => {
    if (final) {
      window.clearInterval(id);
      const t = now();
      for (const [name, start] of open) closed.push({ name, start, end: t });
      open.clear();
      delete window.__BBB_TALK_LOG_DRAIN__;
    }
    const out = closed.map(i => ({ name: i.name, start: Math.round(i.start * 10) / 10, end: Math.round(i.end * 10) / 10 }));
    closed = [];
    return out;
  };
}
