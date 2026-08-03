// Liefert den gesamten sichtbaren Chat als eine Textzeile pro Nachricht
// ("User: Body"), fuer die Befehls-Erkennung (STOP/START nach Marker).
// Wichtig fuer dieses BBB: Nachrichten-Element = data-test="chatMessageItem",
// Body = data-test="messageContent". Sortierung nach data-sequence (aeltestes
// zuerst), damit "Text nach Marker" chronologisch korrekt ist.
() => {
  function cleanText(html) {
    return html
      .replace(/<\s*br\s*\/?>/gi, ' ')
      .replace(/<\/\s*p\s*>/gi, ' ')
      .replace(/<\/\s*div\s*>/gi, ' ')
      .replace(/<[^>]+>/g, '')
      .replace(/ /g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  const seqOf = (el) => {
    const holder = (el.closest && el.closest('[data-sequence]')) || el;
    const s = parseInt((holder.getAttribute && holder.getAttribute('data-sequence')) || '', 10);
    return isNaN(s) ? 0 : s;
  };

  const nodes = Array.from(document.querySelectorAll(
    '[data-test="chatMessageItem"], [data-test="chatMessage"], [data-test^="chatMessage"]'
  ));
  nodes.sort((a, b) => seqOf(a) - seqOf(b));

  return nodes.map(m => {
    const userEl = m.querySelector('[data-test="chatMessageUser"], [data-test="userName"]');
    const bodyEl = m.querySelector('[data-test="messageContent"], [data-test="chatMessageBody"]');
    const user = userEl ? cleanText(userEl.innerHTML) : '';
    const body = bodyEl ? cleanText(bodyEl.innerHTML) : cleanText(m.innerHTML);
    if (user && body) return user + ': ' + body;
    return body || '';
  }).filter(line => line.length > 0).join('\n');
}
