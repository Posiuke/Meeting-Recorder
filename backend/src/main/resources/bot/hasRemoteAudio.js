// Prueft, ob mindestens ein Audio-Element mit Medien vorhanden ist
// (Join gilt erst als abgeschlossen, wenn Remote-Audio anliegt).
() => {
  try {
    const audios = Array.from(document.querySelectorAll('audio'));
    if (audios.length === 0) return false;
    for (const a of audios) {
      try {
        if (a.currentSrc) return true;
        if (a.readyState && a.readyState > 0) return true;
        const classes = a.getAttribute('class') || '';
        if (/remote/i.test(classes)) return true;
      } catch {}
    }
    return false;
  } catch { return false; }
}
