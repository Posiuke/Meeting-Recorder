// Extrahiert alle Chat-Nachrichten inkl. Zeit und Absender als Liste von
// Strings im Format "[Zeit] User:\nBody" (fuer Chat-Protokoll und KI-Kontext).
// BBB-DOM hier: Nachricht = data-test="chatMessageItem", Body =
// data-test="messageContent". Sortierung nach data-sequence (aeltestes zuerst).
() => {
  function htmlToTextLocal(html) {
    return html
      .replace(/<\s*br\s*\/?>/gi, '\n')
      .replace(/<\/\s*p\s*>/gi, '\n')
      .replace(/<\/\s*div\s*>/gi, '\n')
      .replace(/<[^>]+>/g, '')
      .replace(/ /g, ' ')
      .replace(/\r?\n/g, '\n')
      .split('\n').map(l => l.trim()).filter(l => l.length > 0).join('\n');
  }

  const seqOf = (el) => {
    const holder = (el.closest && el.closest('[data-sequence]')) || el;
    const s = parseInt((holder.getAttribute && holder.getAttribute('data-sequence')) || '', 10);
    return isNaN(s) ? 0 : s;
  };

  const out = [];
  const msgNodes = Array.from(document.querySelectorAll(
    '[data-test="chatMessageItem"], [data-test="chatMessage"], [data-test^="chatMessage"]'
  ));
  msgNodes.sort((a, b) => seqOf(a) - seqOf(b));

  for (const m of msgNodes) {
    try {
      const time = (m.querySelector('[data-test="chatMessageTime"]')?.textContent || '').trim();
      const user = (m.querySelector('[data-test="chatMessageUser"], [data-test="userName"]')?.textContent || '').trim();
      const bodyEl = m.querySelector('[data-test="messageContent"], [data-test="chatMessageBody"]');
      const body = bodyEl ? htmlToTextLocal(bodyEl.innerHTML) : htmlToTextLocal(m.innerHTML);
      const prefix = [];
      if (time) prefix.push('[' + time + ']');
      if (user) prefix.push(user + ':');
      out.push(prefix.length ? prefix.join(' ') + '\n' + body : body);
    } catch {}
  }
  return out;
}
