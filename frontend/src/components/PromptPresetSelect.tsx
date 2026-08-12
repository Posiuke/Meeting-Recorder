import { translate, useI18n } from '../i18n';
import type { PromptTemplateView } from '../types';

/**
 * Integrierte Prompt-Vorlagen für typische Nicht-Meeting-Inhalte. Beschriftung
 * und Prompt kommen aus den Übersetzungen – ein englischsprachiger Nutzer soll
 * keinen deutschen Prompt vorgesetzt bekommen.
 */
export const PRESET_KEYS = [
  { key: 'talk', labelKey: 'summaryOptions.presets.talkLabel', promptKey: 'summaryOptions.presets.talkPrompt' },
  { key: 'interview', labelKey: 'summaryOptions.presets.interviewLabel', promptKey: 'summaryOptions.presets.interviewPrompt' },
  { key: 'note', labelKey: 'summaryOptions.presets.noteLabel', promptKey: 'summaryOptions.presets.notePrompt' },
] as const;

/** Eigene Vorlage zur Auswahl, sofern eine gewählt ist (Auswahl `tpl:<id>`). */
export function findOwnTemplate(
  selection: string,
  templates: PromptTemplateView[],
): PromptTemplateView | undefined {
  return selection.startsWith('tpl:')
    ? templates.find((t) => t.id === selection.slice(4))
    : undefined;
}

/**
 * Prompt zur getroffenen Auswahl: leerer String bei „Meeting (Standard)" –
 * das bedeutet überall „Standardvorgabe des Administrators verwenden".
 */
export function resolvePresetPrompt(selection: string, templates: PromptTemplateView[]): string {
  if (selection === '') return '';
  const own = findOwnTemplate(selection, templates);
  if (own) return own.prompt;
  const builtIn = PRESET_KEYS.find((p) => p.key === selection);
  return builtIn ? translate(builtIn.promptKey) : '';
}

interface PromptPresetSelectProps {
  id: string;
  /** '' = Standard, 'tpl:<id>' = eigene Vorlage, sonst Schlüssel einer integrierten Vorlage. */
  value: string;
  templates: PromptTemplateView[];
  disabled?: boolean;
  onChange: (selection: string) => void;
}

/**
 * Auswahlfeld für die Auswertungs-Vorlage: integrierte Vorlagen und die eigenen
 * gespeicherten. Gemeinsam genutzt von „Auswertung anpassen" (pro Aufnahme) und
 * vom Upload-Dialog, damit an beiden Stellen dieselben Vorlagen zur Wahl stehen.
 */
export default function PromptPresetSelect({
  id,
  value,
  templates,
  disabled,
  onChange,
}: PromptPresetSelectProps) {
  const { t } = useI18n();
  return (
    <select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t('summaryOptions.presetDefault')}</option>
      <optgroup label={t('summaryOptions.presetBuiltIn')}>
        {PRESET_KEYS.map((p) => (
          <option key={p.key} value={p.key}>
            {t(p.labelKey)}
          </option>
        ))}
      </optgroup>
      {templates.length > 0 && (
        <optgroup label={t('summaryOptions.presetMine')}>
          {templates.map((template) => (
            <option key={template.id} value={`tpl:${template.id}`}>
              {template.name}
            </option>
          ))}
        </optgroup>
      )}
    </select>
  );
}
