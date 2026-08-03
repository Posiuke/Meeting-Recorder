// Ermittelt den Raum-/Meetingnamen aus der BBB-Oberflaeche.
// Bevorzugt das Navbar-Element (data-test="presentationTitle"), sonst den
// Dokumenttitel des html5client ("BigBlueButton - <Raumname> - <Ansicht>").
// Liefert '' wenn (noch) kein Name ermittelbar ist.
() => {
  const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
  try {
    const el = document.querySelector('[data-test="presentationTitle"]');
    const t = clean(el && el.textContent);
    if (t) return t;
  } catch {}
  try {
    const parts = clean(document.title).split(' - ').map(clean).filter(Boolean);
    if (parts.length >= 2 && /bigbluebutton/i.test(parts[0])) return parts[1];
    if (parts.length >= 2 && /bigbluebutton/i.test(parts[parts.length - 1])) return parts[0];
    if (parts.length === 1 && !/bigbluebutton/i.test(parts[0])) return parts[0];
  } catch {}
  return '';
}
