import { translate, useI18n } from '../i18n';
import type { PromptTemplateView, SummarySelection } from '../types';

/**
 * Integrierte Prompt-Vorlagen für typische Inhalte. Beschriftung und Prompt
 * kommen aus den Übersetzungen – ein englischsprachiger Nutzer soll keinen
 * deutschen Prompt vorgesetzt bekommen.
 *
 * „Meeting" steht hier als ausgeschriebener Prompt zur Verfügung (anpassbar,
 * unabhängig von späteren Änderungen des Administrators); die Auswahl
 * „Meeting (Standard)" bleibt dagegen ein leerer Prompt und folgt damit immer
 * der aktuellen Vorgabe des Administrators.
 */
export const PRESET_KEYS = [
  { key: 'meeting', labelKey: 'summaryOptions.presets.meetingLabel', promptKey: 'summaryOptions.presets.meetingPrompt' },
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
 * Name der getroffenen Auswahl, wie er einer erzeugten Fassung als Beschriftung
 * mitgegeben wird: `null` bei „Meeting (Standard)" – dort gibt es keine benannte
 * Vorlage, sondern die jeweils aktuelle Vorgabe des Administrators.
 */
export function presetLabel(
  selection: string,
  templates: PromptTemplateView[],
): string | null {
  if (selection === '') return null;
  const own = findOwnTemplate(selection, templates);
  if (own) return own.name;
  const builtIn = PRESET_KEYS.find((p) => p.key === selection);
  return builtIn ? translate(builtIn.labelKey) : null;
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

/** Gespeicherter Stand einer Auswahl, etwa aus einer Bot-Vorlage. */
export interface StoredSummarySelection extends SummarySelection {
  summaryPreset: string | null;
}

/**
 * Prompt, Name, Modell und Temperatur zur getroffenen Auswahl – so, wie sie an
 * den Server gehen. Eine eigene Vorlage bringt ihr Modell und ihre Temperatur
 * mit; sonst gilt die Vorgabe des Administrators.
 *
 * Ist die gewählte eigene Vorlage inzwischen gelöscht, bleibt bei
 * unveränderter Auswahl der gespeicherte Stand (`stored`) erhalten, statt
 * stillschweigend auf den Standard zu fallen.
 */
export function resolveSummarySelection(
  selection: string,
  templates: PromptTemplateView[],
  stored?: StoredSummarySelection | null,
): SummarySelection {
  const own = findOwnTemplate(selection, templates);
  if (selection.startsWith('tpl:') && !own && stored && stored.summaryPreset === selection) {
    return {
      summaryPrompt: stored.summaryPrompt ?? null,
      summaryTemplateName: stored.summaryTemplateName ?? null,
      summaryModel: stored.summaryModel ?? null,
      summaryTemperature: stored.summaryTemperature ?? null,
    };
  }
  return {
    summaryPrompt: resolvePresetPrompt(selection, templates) || null,
    summaryTemplateName: presetLabel(selection, templates),
    summaryModel: own?.model ?? null,
    summaryTemperature: own?.temperature ?? null,
  };
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
