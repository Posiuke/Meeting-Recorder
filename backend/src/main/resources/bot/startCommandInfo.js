// Sucht (neueste zuerst) nach einem START-Befehl im Chat und liefert Metadaten
// der Nachricht (fuer Debounce/Logging). Parameter: { cmd: escaped Regex,
// sent: vom Bot selbst gesendete Texte }.
// Nachrichten des Bots selbst werden uebersprungen (kein Selbst-Trigger) -
// Erkennung wie in chatMessages.js: Bearbeiten-Button (nur beim Autor), nur
// ohne jeden Bearbeiten-Button im Chat ersatzweise die selbst gesendeten Texte. Zusaetzlich bleiben Zeilen mit
// Bot-Marker "[RECxxxxxxxxxxxx]" aussen vor.
({ cmd, sent }) => {
  // \b nur setzen, wenn der Befehlsrand ein Wortzeichen ist: bei Befehlen wie
  // "!start" gibt es vor dem "!" keine Wortgrenze, starres \b matcht dann nie.
  // (cmd beginnt bei Sonderzeichen mit "\", das ist kein Wortzeichen.)
  const wordChar = /\w/;
  const prefix = wordChar.test(cmd[0]) ? '\\b' : '';
  const suffix = wordChar.test(cmd[cmd.length - 1]) ? '\\b' : '';
  const cmdRegex = new RegExp(prefix + cmd + suffix, 'i');
  const botMarker = /\[REC[0-9A-Za-z]{12}\]/;
  const norm = (s) => (s || '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
  const sentSet = new Set((sent || []).map(norm));

  // Nur Nachrichten im Chat-Verlauf selbst: BBB zeigt neue Nachrichten
  // zusaetzlich kurz als Benachrichtigung an - die zaehlte sonst doppelt.
  // Nicht [data-test^="chatMessage"]: das traefe auch den Container.
  // Doppelte IDs (z.B. waehrend BBB neu rendert) werden verworfen.
  const scope = document.querySelector('[data-test="chatMessages"]') || document;
  const seenIds = new Set();
  const nodes = Array.from(scope.querySelectorAll(
    '[data-test="chatMessageItem"], [data-test="chatMessage"]')).filter(m => {
      const id = m.getAttribute('data-chat-message-id');
      if (!id) return true;
      if (seenIds.has(id)) return false;
      seenIds.add(id);
      return true;
    });

  const editButtons = nodes.some(m => m.querySelector('[data-test="editMessageButton"]'));

  for (let i = nodes.length - 1; i >= 0; i--) {
    const m = nodes[i];
    const bodyEl = m.querySelector('[data-test="messageContent"], [data-test="chatMessageBody"]');
    const body = norm(bodyEl ? bodyEl.textContent : '');
    if (!body) continue;
    const own = editButtons
      ? !!m.querySelector('[data-test="editMessageButton"]')
      : sentSet.has(body);
    if (own) continue;
    if (botMarker.test(body)) continue;
    if (cmdRegex.test(body)) {
      const time = (m.querySelector('time, [data-test="chatMessageTime"]')?.textContent || '').trim();
      // Die ID macht gleichlautende Befehle zu verschiedenen Zeiten unterscheidbar.
      const id = m.getAttribute('data-chat-message-id') || '';
      return { found: true, messagePreview: body.substring(0, 100), timestamp: (time + ' ' + id).trim() };
    }
  }
  return { found: false };
}
