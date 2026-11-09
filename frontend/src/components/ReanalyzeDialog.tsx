import { useEffect, useRef, useState } from 'react';
import Modal from './Modal';
import Alert from './Alert';
import HelpTip from './HelpTip';
import PromptPresetSelect, {
  CUSTOM_PRESET,
  presetForPrompt,
  resolvePresetPrompt,
  resolveSummarySelection,
} from './PromptPresetSelect';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { fetchPromptTemplates } from '../store/promptTemplatesSlice';
import {
  fetchRecordingDetail,
  reprocessRecording,
  updateSummaryOptions,
} from '../store/recordingsSlice';
import { errorMessage } from '../api/client';
import { useI18n } from '../i18n';
import type { SummaryOptionsView } from '../types';

interface ReanalyzeDialogProps {
  recordingId: string;
  options: SummaryOptionsView;
  onClose: () => void;
  /** Öffnet „Auswertung anpassen" für Länge, Sprache und Modell. */
  onOpenOptions: () => void;
}

/**
 * Neue Zusammenfassung einer schon ausgewerteten Aufnahme – mit Wahl der
 * Vorlage direkt hier, statt vorher über „Auswertung anpassen" gehen zu
 * müssen. Vorbelegt ist die an der Aufnahme hinterlegte Vorlage. Eine andere
 * Wahl wird an der Aufnahme gespeichert (sie gilt damit auch für spätere
 * Auswertungen), Länge, Sprache und Modell bleiben dabei unberührt. Die neue
 * Fassung kommt neben die vorhandenen, nichts geht verloren.
 */
export default function ReanalyzeDialog({
  recordingId,
  options,
  onClose,
  onOpenOptions,
}: ReanalyzeDialogProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const {
    items: templates,
    loaded: templatesLoaded,
    loading: templatesLoading,
  } = useAppSelector((s) => s.promptTemplates);
  const initial = presetForPrompt(options.prompt, templates);
  const [selection, setSelection] = useState(initial);
  const touched = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!templatesLoaded && !templatesLoading) {
      dispatch(fetchPromptTemplates());
    }
  }, [dispatch, templatesLoaded, templatesLoading]);

  // Eigene Vorlagen kommen ggf. erst nach dem Öffnen an.
  useEffect(() => {
    if (templatesLoaded && !touched.current) {
      setSelection(presetForPrompt(options.prompt, templates));
    }
  }, [templatesLoaded, templates, options.prompt]);

  const changed = selection !== presetForPrompt(options.prompt, templates);
  const previewPrompt =
    selection === CUSTOM_PRESET
      ? (options.prompt ?? '')
      : resolvePresetPrompt(selection, templates) || options.defaultPrompt;

  const handleStart = async () => {
    setError(null);
    setBusy(true);
    try {
      if (changed && selection !== CUSTOM_PRESET) {
        const resolved = resolveSummarySelection(selection, templates);
        await dispatch(
          updateSummaryOptions({
            id: recordingId,
            prompt: resolved.summaryPrompt ?? null,
            templateName: resolved.summaryPrompt ? (resolved.summaryTemplateName ?? null) : null,
            maxWords: options.maxWords,
            language: options.language,
            sttLanguage: options.sttLanguage,
            model: resolved.summaryModel ?? null,
            temperature: resolved.summaryTemperature ?? null,
          }),
        ).unwrap();
      }
      await dispatch(reprocessRecording(recordingId)).unwrap();
      await dispatch(fetchRecordingDetail({ id: recordingId, silent: true }));
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={t('recordingDetail.reanalyzeTitle')}
      onClose={busy ? () => {} : onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={handleStart}>
            {busy ? t('recordingDetail.reanalyzeBusy') : t('recordingDetail.reanalyzeStart')}
          </button>
        </>
      }
    >
      <p className="muted">{t('recordingDetail.reanalyzeIntro')}</p>
      {error && <Alert kind="error">{error}</Alert>}

      <div className="form-field">
        <label htmlFor="reanalyze-preset">
          {t('summaryOptions.presetLabel')}
          <HelpTip text={t('summaryOptions.presetHelp')} />
        </label>
        <PromptPresetSelect
          id="reanalyze-preset"
          value={selection}
          templates={templates}
          disabled={busy}
          onChange={(value) => {
            touched.current = true;
            setSelection(value);
          }}
        />
        <span className="muted upload-preset-hint">
          {changed
            ? t('recordingDetail.reanalyzeChangedHint')
            : t('recordingDetail.reanalyzeCurrentHint')}
        </span>
      </div>

      {previewPrompt && (
        <details className="summary-prompt">
          <summary>{t('recordingDetail.reanalyzePromptPreview')}</summary>
          <pre className="log-pre">{previewPrompt}</pre>
        </details>
      )}

      <p className="muted">
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={busy}
          onClick={onOpenOptions}
        >
          {t('recordingDetail.reanalyzeMoreOptions')}
        </button>
      </p>
    </Modal>
  );
}
