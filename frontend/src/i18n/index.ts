import { useSyncExternalStore } from 'react';
import { de } from './de';
import { en } from './en';
import { setFormatLocale } from '../utils/format';

/**
 * Mehrsprachigkeit ohne Zusatzbibliothek: Die Anwendung läuft in abgeschotteten
 * Netzen, in denen jede neue npm-Abhängigkeit erst durch den internen Mirror
 * muss – für zwei Wörterbücher und eine Ersetzungsfunktion lohnt das nicht.
 *
 * Die Sprache liegt in einem Modul-Zustand (nicht im Redux-Store), damit auch
 * Code außerhalb von React sie nutzen kann: Slices und der API-Client bilden
 * Fehlermeldungen über {@link translate}.
 */

export const LANGUAGES = [
  { code: 'de', label: 'Deutsch' },
  { code: 'en', label: 'English' },
] as const;

export type Language = (typeof LANGUAGES)[number]['code'];

/** Deutsch ist die Ausgangssprache und dient als Rückfall für fehlende Schlüssel. */
export const FALLBACK_LANGUAGE: Language = 'de';

const dictionaries: Record<Language, typeof de> = { de, en };

/** Locale für Datums-, Zeit- und Zahlenformate je Sprache. */
const LOCALES: Record<Language, string> = { de: 'de-DE', en: 'en-GB' };

const STORAGE_KEY = 'bbb_language';

/**
 * Alle Blattpfade des Wörterbuchs als String-Union ("recordings.title"). Damit
 * ist ein Tippfehler im Schlüssel ein Compile-Fehler statt eines Textes, der im
 * Browser als Schlüssel erscheint.
 */
type Leaves<T> = T extends string
  ? never
  : {
      [K in keyof T & string]: T[K] extends string ? K : `${K}.${Leaves<T[K]>}`;
    }[keyof T & string];

export type TranslationKey = Leaves<typeof de>;

export type TranslationVars = Record<string, string | number>;

let current: Language = FALLBACK_LANGUAGE;
const listeners = new Set<() => void>();

function lookup(dictionary: unknown, key: string): unknown {
  return key
    .split('.')
    .reduce<unknown>(
      (node, part) =>
        node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined,
      dictionary,
    );
}

/**
 * Übersetzt einen Schlüssel in die aktuelle Sprache. Platzhalter im Text werden
 * als `{{name}}` geschrieben und über `vars` gefüllt. Fehlt ein Schlüssel in der
 * gewählten Sprache, greift Deutsch.
 */
export function translate(key: TranslationKey, vars?: TranslationVars): string {
  const raw = lookup(dictionaries[current], key) ?? lookup(dictionaries[FALLBACK_LANGUAGE], key);
  if (typeof raw !== 'string') return key;
  if (!vars) return raw;
  return raw.replace(/\{\{(\w+)\}\}/g, (_, name: string) =>
    name in vars ? String(vars[name]) : `{{${name}}}`,
  );
}

export function getLanguage(): Language {
  return current;
}

export function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && LANGUAGES.some((l) => l.code === value);
}

/**
 * Sprache umstellen und überall bekannt machen: Formatierungen, das
 * `lang`-Attribut des Dokuments (für Vorlesehilfen und Silbentrennung) und der
 * lokale Merker, damit schon der Anmeldebildschirm richtig erscheint.
 */
export function setLanguage(language: Language): void {
  current = language;
  setFormatLocale(LOCALES[language]);
  if (typeof document !== 'undefined') {
    document.documentElement.lang = language;
  }
  try {
    localStorage.setItem(STORAGE_KEY, language);
  } catch {
    // Privater Modus ohne localStorage: Die Sprache gilt dann nur für diese Sitzung.
  }
  listeners.forEach((listener) => listener());
}

/**
 * Startsprache bestimmen. Reihenfolge: am Konto gespeicherte Sprache, zuletzt
 * lokal gemerkte, Browsersprache, Deutsch.
 */
export function resolveInitialLanguage(userLanguage?: string | null): Language {
  if (isLanguage(userLanguage)) return userLanguage;
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (isLanguage(stored)) return stored;
  } catch {
    // ohne localStorage weiter mit der Browsersprache
  }
  const browser = typeof navigator === 'undefined' ? '' : navigator.language.slice(0, 2).toLowerCase();
  return isLanguage(browser) ? browser : FALLBACK_LANGUAGE;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Zugriff auf Übersetzung und Sprache in Komponenten. Der Aufruf abonniert den
 * Sprachwechsel – die Komponente rendert dabei automatisch neu.
 */
export function useI18n(): {
  t: typeof translate;
  language: Language;
  setLanguage: typeof setLanguage;
} {
  const language = useSyncExternalStore(subscribe, getLanguage, getLanguage);
  return { t: translate, language, setLanguage };
}

// Beim Laden des Moduls einmal anwenden, damit Formate und lang-Attribut passen,
// bevor die erste Komponente rendert.
setLanguage(resolveInitialLanguage());
