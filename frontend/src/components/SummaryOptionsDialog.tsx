import { useEffect, useState } from 'react';
import Modal from './Modal';
import Alert from './Alert';
import ConfirmDialog from './ConfirmDialog';
import HelpTip from './HelpTip';
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

/**
 * Integrierte Prompt-Vorlagen für typische Nicht-Meeting-Inhalte. Beschriftung
 * und Prompt kommen aus den Übersetzungen – ein englischsprachiger Nutzer soll
 * keinen deutschen Prompt vorgesetzt bekommen.
 */
const PRESET_KEYS = [
  { key: 'talk', labelKey: 'summaryOptions.presets.talkLabel', promptKey: 'summaryOptions.presets.talkPrompt' },
  { key: 'interview', labelKey: 'summaryOptions.presets.interviewLabel', promptKey: 'summaryOptions.presets.interviewPrompt' },
  { key: 'note', labelKey: 'summaryOptions.presets.noteLabel', promptKey: 'summaryOptions.presets.notePrompt' },
] as const;

/** Zielsprachen der Zusammenfassung (unabhängig von der Oberflächensprache). */
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
  const [maxWords, setMaxWords] = useState(options.maxWords?.toString() ?? '');
  const [language, setLanguage] = useState(options.language ?? '');
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

  const maxWordsNum = maxWords.trim() === '' ? null : Number(maxWords);
  const maxWordsInvalid =
    maxWordsNum !== null && (!Number.isInteger(maxWordsNum) || maxWordsNum < 10 || maxWordsNum > 10000);

  // Auswahl "tpl:<id>" = eigene Vorlage, sonst integrierte Vorlage
  const selectedTemplate = preset.startsWith('tpl:')
    ? templates.find((t) => t.id === preset.slice(4))
    : undefined;

  const applyPreset = (key: string) => {
    setPreset(key);
    if (key === '') {
      setPrompt('');
    } else if (key.startsWith('tpl:')) {
      const found = templates.find((t) => t.id === key.slice(4));
      if (found) setPrompt(found.prompt);
    } else {
      const found = PRESET_KEYS.find((p) => p.key === key);
      if (found) setPrompt(t(found.promptKey));
    }
  };

  const handleCreateTemplate = async () => {
    if (!templateName.trim() || !prompt.trim()) return;
    setError(null);
    setTemplateBusy(true);
    try {
      const created = await dispatch(
        createPromptTemplate({ name: templateName.trim(), prompt: prompt.trim() }),
      ).unwrap();
      setPreset(`tpl:${created.id}`);
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
      setConfirmTemplateDelete(false);
    } catch (e) {
      setError(errorMessage(e));
      setConfirmTemplateDelete(false);
    } finally {
      setTemplateBusy(false);
    }
  };

  const handleSave = async () => {
    if (maxWordsInvalid) return;
    setError(null);
    setBusy(true);
    try {
      await dispatch(
        updateSummaryOptions({
          id: recordingId,
          prompt: prompt.trim() === '' ? null : prompt.trim(),
          maxWords: maxWordsNum,
          language: language === '' ? null : language,
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
            disabled={busy || maxWordsInvalid}
            onClick={handleSave}
          >
            {busy ? t('common.saving') : t('common.save')}
          </button>
        </>
      }
    >
      <p className="muted">{t('summaryOptions.intro')}</p>

      {error && <Alert kind="error">{error}</Alert>}

      <div className="form-field">
        <label htmlFor="so-preset">
          {t('summaryOptions.presetLabel')}
          <HelpTip text={t('summaryOptions.presetHelp')} />
        </label>
        <select id="so-preset" value={preset} disabled={busy} onChange={(e) => applyPreset(e.target.value)}>
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
              {templates.map((t) => (
                <option key={t.id} value={`tpl:${t.id}`}>
                  {t.name}
                </option>
              ))}
            </optgroup>
          )}
        </select>
        {templatesError && (
          <span className="field-error">
            {t('summaryOptions.templatesError', { message: templatesError })}
          </span>
        )}
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
          onChange={(e) => setPrompt(e.target.value)}
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
                    disabled={busy || templateBusy || !prompt.trim() || prompt.trim() === selectedTemplate.prompt}
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
