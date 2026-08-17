import { useI18n } from '../i18n';
import type { translate } from '../i18n';

/**
 * Sprachen, die zur Auswahl stehen. Bewusst eine überschaubare Liste gängiger
 * Sprachen – welche das Modell wirklich kann, entscheidet der Whisper-Server.
 * Eine abweichende, bereits gespeicherte Angabe bleibt trotzdem wählbar (siehe
 * unten), damit sie beim Speichern nicht verloren geht.
 */
export const STT_LANGUAGES = [
  'de',
  'en',
  'fr',
  'es',
  'it',
  'nl',
  'pl',
  'pt',
  'ru',
  'tr',
  'uk',
  'ar',
  'zh',
] as const;

/** Whisper erkennt die Sprache selbst – gleicher Wert wie im Backend. */
export const STT_AUTO = 'auto';

interface SttLanguageSelectProps {
  id: string;
  /** '' = Standardvorgabe des Administrators, 'auto' = automatisch erkennen, sonst Sprachcode. */
  value: string;
  /**
   * Sprachcode aus den Admin-Einstellungen (`whisper.language`), für die
   * Beschriftung der Standard-Auswahl. Leer bedeutet dort „automatisch erkennen".
   */
  defaultLanguage?: string | null;
  disabled?: boolean;
  onChange: (value: string) => void;
}

/** Beschriftung eines Sprachcodes; unbekannte Codes bleiben, wie sie sind. */
export function sttLanguageLabel(code: string, t: typeof translate): string {
  const known = STT_LANGUAGES.find((c) => c === code);
  if (known) return t(`sttLanguage.lang.${known}`);
  return code === STT_AUTO ? t('sttLanguage.auto') : code;
}

/**
 * Auswahlfeld für die Sprache der **Spracherkennung** (nicht der
 * Zusammenfassung). Gemeinsam genutzt von Upload-Dialog, Bot-Formular und
 * „Auswertung anpassen", damit die Wahl überall dieselbe Bedeutung hat:
 * leer = Admin-Standard, `auto` = Whisper entscheidet selbst.
 */
export default function SttLanguageSelect({
  id,
  value,
  defaultLanguage,
  disabled,
  onChange,
}: SttLanguageSelectProps) {
  const { t } = useI18n();
  const fallback = (defaultLanguage ?? '').trim();
  const defaultLabel =
    fallback === '' || fallback === STT_AUTO
      ? t('sttLanguage.defaultAuto')
      : t('sttLanguage.default', { language: sttLanguageLabel(fallback, t) });

  return (
    <select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      <option value="">{defaultLabel}</option>
      <option value={STT_AUTO}>{t('sttLanguage.auto')}</option>
      {STT_LANGUAGES.map((code) => (
        <option key={code} value={code}>
          {t(`sttLanguage.lang.${code}`)}
        </option>
      ))}
      {/* Bereits gespeicherter Code, den die Liste nicht kennt – sonst würde er
          beim nächsten Speichern still verschwinden. */}
      {value !== '' &&
        value !== STT_AUTO &&
        !(STT_LANGUAGES as readonly string[]).includes(value) && (
          <option value={value}>{value}</option>
        )}
    </select>
  );
}
