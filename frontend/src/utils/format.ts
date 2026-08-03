/**
 * Locale für alle Datums-, Zeit- und Zahlenformate. Wird beim Sprachwechsel
 * gesetzt (siehe i18n) – bewusst als Modul-Zustand, damit die Formatierer
 * überall ohne zusätzlichen Parameter aufrufbar bleiben.
 */
let locale = 'de-DE';

export function setFormatLocale(next: string): void {
  locale = next;
}

/** Kurzzeichen für „kein Wert" – sprachunabhängig. */
const EMPTY = '–';

/** Datum/Uhrzeit in der aktuellen Sprache formatieren. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Nur Uhrzeit (HH:mm) formatieren. */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
}

/**
 * Dauer als mm:ss bzw. h:mm formatieren. Die Einheiten „h" und „min" sind in
 * beiden Sprachen gleich und bleiben deshalb fest.
 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return EMPTY;
  const totalSec = Math.round(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) {
    return `${h}:${String(m).padStart(2, '0')} h`;
  }
  return `${m}:${String(s).padStart(2, '0')} min`;
}

/** Zeitmarke im Transkript: mm:ss unter einer Stunde, sonst h:mm:ss. */
export function formatTimestamp(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) {
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/** Dateigröße menschenlesbar formatieren (Einheiten sind international). */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return EMPTY;
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let i = -1;
  do {
    value /= 1024;
    i++;
  } while (value >= 1024 && i < units.length - 1);
  return `${value.toLocaleString(locale, { maximumFractionDigits: 1 })} ${units[i]}`;
}
