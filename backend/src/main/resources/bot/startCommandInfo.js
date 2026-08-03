// Sucht (neueste zuerst) nach einem START-Befehl im gesamten Chat und liefert
// Metadaten der Nachricht (fuer Debounce/Logging). Parameter: escaped Regex.
// BBB-DOM hier: Nachricht = data-test="chatMessageItem", Body =
// data-test="messageContent". Bot-eigene Hinweismeldungen (mit Marker
// "[RECxxxxxxxxxxxx]") werden uebersprungen -> kein Selbst-Trigger.
(escapedCmd) => {
  // \b nur setzen, wenn der Befehlsrand ein Wortzeichen ist: bei Befehlen wie
  // "!start" gibt es vor dem "!" keine Wortgrenze, starres \b matcht dann nie.
  // (escapedCmd beginnt bei Sonderzeichen mit "\", das ist kein Wortzeichen.)
  const wordChar = /\w/;
  const prefix = wordChar.test(escapedCmd[0]) ? '\\b' : '';
  const suffix = wordChar.test(escapedCmd[escapedCmd.length - 1]) ? '\\b' : '';
  const cmdRegex = new RegExp(prefix + escapedCmd + suffix, 'i');
  const botMarker = /\[REC[0-9A-Za-z]{12}\]/;

  const seqOf = (el) => {
    const holder = (el.closest && el.closest('[data-sequence]')) || el;
    const s = parseInt((holder.getAttribute && holder.getAttribute('data-sequence')) || '', 10);
    return isNaN(s) ? 0 : s;
  };

  const nodes = Array.from(document.querySelectorAll(
    '[data-test="chatMessageItem"], [data-test="chatMessage"], [data-test^="chatMessage"]'
  ));
  nodes.sort((a, b) => seqOf(a) - seqOf(b));

  for (let i = nodes.length - 1; i >= 0; i--) {
    const m = nodes[i];
    const bodyEl = m.querySelector('[data-test="messageContent"], [data-test="chatMessageBody"]');
    const timeEl = m.querySelector('[data-test="chatMessageTime"]');
    const userEl = m.querySelector('[data-test="chatMessageUser"], [data-test="userName"]');
    const body = bodyEl ? (bodyEl.textContent || '') : (m.textContent || '');
    if (botMarker.test(body)) continue; // Bot-eigene Nachricht ignorieren
    if (cmdRegex.test(body)) {
      const time = timeEl ? timeEl.textContent || '' : '';
      const user = userEl ? userEl.textContent || '' : '';
      return { found: true, messagePreview: (user + ': ' + body.substring(0, 100)).trim(), timestamp: time };
    }
  }
  return { found: false };
}
