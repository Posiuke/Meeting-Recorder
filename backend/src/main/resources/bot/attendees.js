// Liest die Teilnehmerliste aus dem BBB-DOM.
// Wichtig: Jede Zeile enthaelt ein Avatar-KUERZEL (z.B. "Re", "Mi") UND den
// vollen Namen (z.B. "RecorderBot", "Micha (Ich)"). Frueher wurde faelschlich
// das Kuerzel abgegriffen -> der Bot erkannte sich selbst nicht wieder.
// Strategie: pro Listeneintrag den vollen Namen bestimmen, Avatar-Initialen
// gezielt ueberspringen. Fallback: Brute-Force-TreeWalker inkl. Shadow-Roots.
() => {
  const clean = (s) => (s || '').replace(/[ \s]+/g, ' ').trim();

  // Element gehoert zum Avatar/den Initialen? -> beim Namen ignorieren.
  const isAvatar = (el) => {
    const cls = (el.getAttribute && el.getAttribute('class')) || '';
    const dt = (el.getAttribute && el.getAttribute('data-test')) || '';
    return /avatar|initial/i.test(cls) || /avatar/i.test(dt);
  };

  // Vollen Namen eines Listeneintrags ermitteln.
  const pickName = (item) => {
    const candidates = [];

    // 1) aria-label am Eintrag traegt in BBB oft den vollen Namen.
    const al = clean(item.getAttribute && item.getAttribute('aria-label'));
    if (al) candidates.push(al);

    // 2) Bekannte Namensfelder bevorzugen.
    const named = item.querySelector(
      '[data-test="userName"], [data-test="userListItemName"], [class*="userName"], [class*="userNameMain"]'
    );
    if (named && !isAvatar(named)) candidates.push(clean(named.textContent));

    // 3) Fallback: laengsten Text aller Nicht-Avatar-Elemente nehmen
    //    (Initialen sind nur 1-2 Zeichen, der Name ist laenger).
    item.querySelectorAll('*').forEach((el) => {
      if (isAvatar(el)) return;
      const t = clean(el.textContent);
      if (t) candidates.push(t);
    });

    const usable = candidates.filter((t) => t.length > 2);
    if (usable.length === 0) return clean(item.textContent);
    usable.sort((a, b) => b.length - a.length);
    return usable[0];
  };

  const out = new Set();
  const items = document.querySelectorAll('[data-test="userListItem"], [data-test^="userListItem"]');
  items.forEach((item) => {
    const name = pickName(item);
    if (name) out.add(name);
  });
  if (out.size > 0) return Array.from(out);

  // --- Fallback: TreeWalker inkl. Shadow-Roots ---
  const results = new Set();
  const walk = (root) => {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    let node = walker.currentNode;
    while (node) {
      const el = node;
      try {
        const role = el.getAttribute?.('role') || '';
        const cls = el.getAttribute?.('class') || '';
        if ((role === 'listitem' || /user|teilnehmer|participant/i.test(cls)) && !isAvatar(el)) {
          const t = clean(el.textContent);
          if (t) results.add(t);
        }
        const sr = el.shadowRoot;
        if (sr) walk(sr);
      } catch {}
      node = walker.nextNode();
    }
  };
  walk(document);
  return Array.from(results);
}
