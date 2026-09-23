// Liest alle Chat-Nachrichten in DOM-Reihenfolge (aeltestes zuerst) als
// Objekte { own, time, text }. Parameter: Liste der Texte, die der Bot selbst
// gesendet hat (whitespace-normalisiert).
//
// BBB-DOM (3.x): Nachricht = data-test="chatMessageItem", Text =
// data-test="messageContent", Uhrzeit = <time> (nur an der letzten Nachricht
// einer Gruppe). Den Absendernamen traegt die einzelne Nachricht NICHT.
//
// "own" = vom Bot selbst gesendet. Erkennung: BBB zeigt "Nachricht bearbeiten"
// nur dem Autor einer Nachricht - auch Moderatoren nicht bei fremden. Nur wenn
// im ganzen Chat KEIN Bearbeiten-Button existiert (Bearbeiten in BBB
// abgeschaltet), zaehlt als Rueckfall jeder Text, den der Bot selbst geschickt
// hat. Beides zu mischen waere falsch: Tippt ein Teilnehmer woertlich dasselbe
// wie der Bot, wuerde seine Nachricht sonst verschluckt.
(sentTexts) => {
  const norm = (s) => (s || '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
  const sent = new Set((sentTexts || []).map(norm));

  function htmlToText(html) {
    return html
      .replace(/<\s*br\s*\/?>/gi, '\n')
      .replace(/<\/\s*p\s*>/gi, '\n')
      .replace(/<\/\s*div\s*>/gi, '\n')
      .replace(/<[^>]+>/g, '')
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/\r?\n/g, '\n')
      .split('\n').map(l => l.trim()).filter(l => l.length > 0).join('\n');
  }

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

  const out = [];
  for (const m of nodes) {
    try {
      const bodyEl = m.querySelector('[data-test="messageContent"], [data-test="chatMessageBody"]');
      const text = bodyEl ? htmlToText(bodyEl.innerHTML) : '';
      if (!text) continue;
      const time = (m.querySelector('time, [data-test="chatMessageTime"]')?.textContent || '').trim();
      const own = editButtons
        ? !!m.querySelector('[data-test="editMessageButton"]')
        : sent.has(norm(text));
      out.push({ own, time, text });
    } catch (e) { /* einzelne kaputte Nachricht ueberspringen */ }
  }
  return out;
}
