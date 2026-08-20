import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import Modal from './Modal';
import Alert from './Alert';
import ConfirmDialog from './ConfirmDialog';
import HelpTip from './HelpTip';
import PromptPresetSelect, {
  findOwnTemplate,
  presetLabel,
  resolvePresetPrompt,
} from './PromptPresetSelect';
import SttLanguageSelect from './SttLanguageSelect';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { updateSummaryOptions } from '../store/recordingsSlice';
import {
  createPromptTemplate,
  deletePromptTemplate,
  fetchPromptTemplates,
  updatePromptTemplate,
} from '../store/promptTemplatesSlice';
import { errorMessage } from '../api/client';
import { useI18n } from '../i18n';
import type { SummaryOptionsView } from '../types';

/** Zielsprachen der Zusammenfassung (unabhängig von der Oberflächensprache). */
/** Bandbreite der Temperatur bei OpenAI-kompatiblen Endpunkten (PromptTemplateController). */
const MAX_TEMPERATURE = 2;

const SUMMARY_LANGUAGES = [
  { value: 'de', labelKey: 'summaryOptions.langDe' },
  { value: 'en', labelKey: 'summaryOptions.langEn' },
  { value: 'fr', labelKey: 'summaryOptions.langFr' },
] as const;

interface SummaryOptionsDialogProps {
  recordingId: string;
  options: SummaryOptionsView;
  onClose: () => void;
}

/**
 * Dialog für die pro-Aufnahme-Einstellungen der KI-Zusammenfassung
 * (Auswertungs-Prompt, maximale Länge, Sprache). Leere Felder bedeuten
 * "Admin-Standard verwenden"; die Einstellungen wirken bei der nächsten
 * Auswertung.
 */
export default function SummaryOptionsDialog({
  recordingId,
  options,
  onClose,
}: SummaryOptionsDialogProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const {
    items: templates,
    loaded: templatesLoaded,
    loading: templatesLoading,
    error: templatesError,
  } = useAppSelector((s) => s.promptTemplates);
  const [prompt, setPrompt] = useState(options.prompt ?? '');
  /**
   * Name der Vorlage, aus der der Prompt stammt (null = keine benannte). Er
   * benennt später die erzeugte Fassung. Sobald der Prompt von Hand geändert
   * wird, ist er nicht mehr der der Vorlage – dann fällt der Name weg, statt
   * eine Fassung falsch zu beschriften.
   */
  const [promptTemplateName, setPromptTemplateName] = useState(options.templateName ?? null);
  /** Modell und Temperatur dieser Aufnahme; leer = Vorgabe des Administrators. */
  const [model, setModel] = useState(options.model ?? '');
  const [temperature, setTemperature] = useState(options.temperature?.toString() ?? '');
  const [maxWords, setMaxWords] = useState(options.maxWords?.toString() ?? '');
  const [language, setLanguage] = useState(options.language ?? '');
  const [sttLanguage, setSttLanguage] = useState(options.sttLanguage ?? '');
  const [preset, setPreset] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [templateSaveOpen, setTemplateSaveOpen] = useState(false);
  const [templateName, setTemplateName] = useState('');
  const [templateBusy, setTemplateBusy] = useState(false);
  const [confirmTemplateDelete, setConfirmTemplateDelete] = useState(false);

  // Vorlagen nur laden, wenn sie noch nicht im Store sind (der Dialog wird oft
  // mehrfach hintereinander geoeffnet)
  useEffect(() => {
    if (!templatesLoaded && !templatesLoading) {
      dispatch(fetchPromptTemplates());
    }
  }, [dispatch, templatesLoaded, templatesLoading]);

  const defaultPrompt = (options.defaultPrompt ?? '').trim();
  const maxWordsNum = maxWords.trim() === '' ? null : Number(maxWords);
  const maxWordsInvalid =
    maxWordsNum !== null && (!Number.isInteger(maxWordsNum) || maxWordsNum < 10 || maxWordsNum > 10000);
  const temperatureNum = temperature.trim() === '' ? null : Number(temperature);
  const temperatureInvalid =
    temperatureNum !== null &&
    (Number.isNaN(temperatureNum) || temperatureNum < 0 || temperatureNum > MAX_TEMPERATURE);

  // Auswahl "tpl:<id>" = eigene Vorlage, sonst integrierte Vorlage
  const selectedTemplate = findOwnTemplate(preset, templates);

  const applyPreset = (key: string) => {
    setPreset(key);
    setPrompt(resolvePresetPrompt(key, templates));
    setPromptTemplateName(presetLabel(key, templates));
    // Eine eigene Vorlage bringt ihr Modell und ihre Temperatur mit; hat sie
    // keine, gilt wieder die Vorgabe des Administrators. Genau darüber lassen
    // sich zwei Modelle an derselben Aufnahme vergleichen.
    const own = findOwnTemplate(key, templates);
    setModel(own?.model ?? '');
    setTemperature(own?.temperature?.toString() ?? '');
  };

  /**
   * Standardvorgabe des Administrators in das Eingabefeld holen, damit sie sich
   * anpassen laesst statt nur komplett ersetzt werden zu koennen. Die Auswahl
   * springt dabei zurueck auf "Standard", sonst wuerde ein anschliessendes
   * "Vorlage aktualisieren" die zuvor gewaehlte eigene Vorlage mit dem
   * Standardtext ueberschreiben.
   */
  const applyDefaultPrompt = () => {
    setPreset('');
    setPrompt(options.defaultPrompt ?? '');
    setPromptTemplateName(null);
  };

  const handleCreateTemplate = async () => {
    if (!templateName.trim() || !prompt.trim()) return;
    setError(null);
    setTemplateBusy(true);
    try {
      const created = await dispatch(
        createPromptTemplate({
          name: templateName.trim(),
          prompt: prompt.trim(),
          model: model.trim() === '' ? null : model.trim(),
          temperature: temperatureNum,
        }),
      ).unwrap();
      setPreset(`tpl:${created.id}`);
      setPromptTemplateName(created.name);
      setTemplateSaveOpen(false);
      setTemplateName('');
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setTemplateBusy(false);
    }
  };

  const handleUpdateTemplate = async () => {
    if (!selectedTemplate || !prompt.trim()) return;
    setError(null);
    setTemplateBusy(true);
    try {
      await dispatch(
        updatePromptTemplate({
          id: selectedTemplate.id,
          name: selectedTemplate.name,
          prompt: prompt.trim(),
          model: model.trim() === '' ? null : model.trim(),
          temperature: temperatureNum,
        }),
      ).unwrap();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setTemplateBusy(false);
    }
  };

  const handleDeleteTemplate = async () => {
    if (!selectedTemplate) return;
    setError(null);
    setTemplateBusy(true);
    try {
      await dispatch(deletePromptTemplate(selectedTemplate.id)).unwrap();
      setPreset('');
      setPromptTemplateName(null);
      setConfirmTemplateDelete(false);
    } catch (e) {
      setError(errorMessage(e));
      setConfirmTemplateDelete(false);
    } finally {
      setTemplateBusy(false);
    }
  };

  const handleSave = async () => {
    if (maxWordsInvalid || temperatureInvalid) return;
    setError(null);
    setBusy(true);
    try {
      await dispatch(
        updateSummaryOptions({
          id: recordingId,
          prompt: prompt.trim() === '' ? null : prompt.trim(),
          templateName: prompt.trim() === '' ? null : promptTemplateName,
          maxWords: maxWordsNum,
          language: language === '' ? null : language,
          sttLanguage: sttLanguage === '' ? null : sttLanguage,
          model: model.trim() === '' ? null : model.trim(),
          temperature: temperatureNum,
        }),
      ).unwrap();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={t('summaryOptions.title')}
      onClose={busy ? () => {} : onClose}
      wide
      footer={
        <>
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy || maxWordsInvalid || temperatureInvalid}
            onClick={handleSave}
          >
            {busy ? t('common.saving') : t('common.save')}
          </button>
        </>
      }
    >
      <p className="muted">{t('summaryOptions.intro')}</p>

      {error && <Alert kind="error">{error}</Alert>}

      {/* Steht vor den Auswertungs-Feldern, weil die Spracherkennung vor der
          Auswertung läuft: Sie wirkt nur, solange noch transkribiert wird. */}
      <div className="form-field">
        <label htmlFor="so-stt-language">
          {t('sttLanguage.label')}
          <HelpTip text={t('sttLanguage.help')} />
        </label>
        <SttLanguageSelect
          id="so-stt-language"
          value={sttLanguage}
          defaultLanguage={options.defaultSttLanguage}
          disabled={busy}
          onChange={setSttLanguage}
        />
        <span className="muted upload-preset-hint">{t('sttLanguage.retranscribeHint')}</span>
      </div>

      <div className="form-field">
        <label htmlFor="so-preset">
          {t('summaryOptions.presetLabel')}
          <HelpTip text={t('summaryOptions.presetHelp')} />
        </label>
        <PromptPresetSelect
          id="so-preset"
          value={preset}
          templates={templates}
          disabled={busy}
          onChange={applyPreset}
        />
        {templatesError && (
          <span className="field-error">
            {t('summaryOptions.templatesError', { message: templatesError })}
          </span>
        )}
        {/* Ausfuehrliches Bearbeiten passiert im Tab "Vorlagen" - bewusst in
            einem neuen Browser-Tab, damit dieser Dialog nicht verloren geht. */}
        <span className="muted upload-preset-hint">
          <Link to="/templates" target="_blank" rel="noreferrer">
            {t('summaryOptions.manageTemplates')}
          </Link>{' '}
          {t('summaryOptions.manageTemplatesHint')}
        </span>
      </div>

      <div className="form-field">
        <label htmlFor="so-prompt">
          {t('summaryOptions.promptLabel')}
          <HelpTip text={t('summaryOptions.promptHelp')} />
        </label>
        <textarea
          id="so-prompt"
          rows={9}
          value={prompt}
          disabled={busy}
          placeholder={`${t('summaryOptions.promptPlaceholder')}\n\n${options.defaultPrompt}`}
          onChange={(e) => {
            setPrompt(e.target.value);
            // Von Hand geändert: der Text ist nicht mehr der der Vorlage
            setPromptTemplateName(null);
          }}
        />
        <div className="template-actions">
          {templateSaveOpen ? (
            <form
              className="template-save-form"
              onSubmit={(e) => {
                e.preventDefault();
                void handleCreateTemplate();
              }}
            >
              <input
                value={templateName}
                maxLength={100}
                placeholder={t('summaryOptions.templateNamePlaceholder')}
                disabled={templateBusy}
                autoFocus
                onChange={(e) => setTemplateName(e.target.value)}
              />
              <button
                type="submit"
                className="btn btn-primary btn-sm"
                disabled={templateBusy || !templateName.trim() || !prompt.trim()}
              >
                {templateBusy ? t('summaryOptions.templateSaving') : t('common.save')}
              </button>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                disabled={templateBusy}
                onClick={() => setTemplateSaveOpen(false)}
              >
                {t('common.cancel')}
              </button>
            </form>
          ) : (
            <>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                disabled={busy || templateBusy || defaultPrompt === '' || prompt.trim() === defaultPrompt}
                title={
                  defaultPrompt === ''
                    ? t('summaryOptions.loadDefaultUnavailable')
                    : t('summaryOptions.loadDefaultHint')
                }
                onClick={applyDefaultPrompt}
              >
                {t('summaryOptions.loadDefault')}
              </button>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                disabled={busy || templateBusy || !prompt.trim()}
                title={t('summaryOptions.saveAsTemplateHint')}
                onClick={() => {
                  setTemplateSaveOpen(true);
                  setTemplateName(
                    selectedTemplate
                      ? t('summaryOptions.copySuffix', { name: selectedTemplate.name })
                      : '',
                  );
                }}
              >
                {t('summaryOptions.saveAsTemplate')}
              </button>
              {selectedTemplate && (
                <>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    disabled={
                      busy ||
                      templateBusy ||
                      !prompt.trim() ||
                      (prompt.trim() === selectedTemplate.prompt &&
                        (model.trim() === '' ? null : model.trim()) === selectedTemplate.model &&
                        temperatureNum === selectedTemplate.temperature)
                    }
                    title={t('summaryOptions.updateTemplateHint', { name: selectedTemplate.name })}
                    onClick={handleUpdateTemplate}
                  >
                    {t('summaryOptions.updateTemplate')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm btn-danger-text"
                    disabled={busy || templateBusy}
                    title={t('summaryOptions.deleteTemplateHint', { name: selectedTemplate.name })}
                    onClick={() => setConfirmTemplateDelete(true)}
                  >
                    {t('summaryOptions.deleteTemplate')}
                  </button>
                </>
              )}
            </>
          )}
        </div>
      </div>

      <div className="form-row">
        <div className="form-field">
          <label htmlFor="so-max-words">
            {t('summaryOptions.maxWordsLabel')}
            <HelpTip text={t('summaryOptions.maxWordsHelp')} />
          </label>
          <input
            id="so-max-words"
            type="number"
            min={10}
            max={10000}
            step={10}
            value={maxWords}
            disabled={busy}
            placeholder={t('summaryOptions.maxWordsPlaceholder')}
            onChange={(e) => setMaxWords(e.target.value)}
          />
          {maxWordsInvalid && (
            <span className="field-error">{t('summaryOptions.maxWordsInvalid')}</span>
          )}
        </div>

        <div className="form-field">
          <label htmlFor="so-model">
            {t('summaryOptions.modelLabel')}
            <HelpTip text={t('summaryOptions.modelHelp')} />
          </label>
          <input
            id="so-model"
            type="text"
            value={model}
            maxLength={200}
            disabled={busy}
            placeholder={t('summaryOptions.modelPlaceholder', { model: options.defaultModel })}
            onChange={(e) => setModel(e.target.value)}
          />
        </div>

        <div className="form-field">
          <label htmlFor="so-temperature">
            {t('summaryOptions.temperatureLabel')}
            <HelpTip text={t('summaryOptions.temperatureHelp')} />
          </label>
          <input
            id="so-temperature"
            type="number"
            min={0}
            max={MAX_TEMPERATURE}
            step={0.1}
            value={temperature}
            disabled={busy}
            placeholder={t('summaryOptions.temperaturePlaceholder', {
              temperature: options.defaultTemperature,
            })}
            onChange={(e) => setTemperature(e.target.value)}
          />
          {temperatureInvalid && (
            <span className="field-error">
              {t('summaryOptions.temperatureInvalid', { max: MAX_TEMPERATURE })}
            </span>
          )}
        </div>

        <div className="form-field">
          <label htmlFor="so-language">
            {t('summaryOptions.languageLabel')}
            <HelpTip text={t('summaryOptions.languageHelp')} />
          </label>
          <select
            id="so-language"
            value={language}
            disabled={busy}
            onChange={(e) => setLanguage(e.target.value)}
          >
            <option value="">
              {t('summaryOptions.languageDefault', { language: options.defaultLanguage })}
            </option>
            {SUMMARY_LANGUAGES.map((l) => (
              <option key={l.value} value={l.value}>
                {t(l.labelKey)}
              </option>
            ))}
            {language !== '' && !SUMMARY_LANGUAGES.some((l) => l.value === language) && (
              <option value={language}>{language}</option>
            )}
          </select>
        </div>
      </div>

      {confirmTemplateDelete && selectedTemplate && (
        <ConfirmDialog
          title={t('summaryOptions.confirmDeleteTemplateTitle')}
          message={t('summaryOptions.confirmDeleteTemplateMessage', {
            name: selectedTemplate.name,
          })}
          confirmLabel={t('common.delete')}
          danger
          busy={templateBusy}
          onConfirm={handleDeleteTemplate}
          onCancel={() => setConfirmTemplateDelete(false)}
        />
      )}
    </Modal>
  );
}
