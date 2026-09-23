import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { createBotTemplate, updateBotTemplate } from '../store/botTemplatesSlice';
import { fetchPromptTemplates } from '../store/promptTemplatesSlice';
import Modal from './Modal';
import Alert from './Alert';
import HelpTip from './HelpTip';
import SttLanguageSelect from './SttLanguageSelect';
import PromptPresetSelect, {
  findOwnTemplate,
  resolveSummarySelection,
} from './PromptPresetSelect';
import BotScheduleFields, {
  scheduleRequestOf,
  scheduleValid,
} from './BotScheduleFields';
import { errorMessage } from '../api/client';
import { useI18n } from '../i18n';
import type { BotTemplateRequest, BotTemplateView } from '../types';

/** Serverseitige Grenzen (BotTemplateController, BotController). */
const MAX_NAME_LENGTH = 100;
const MAX_BOT_NAME_LENGTH = 100;

/**
 * Einstellungen einer Vorlage ohne ihren Namen – so kommen sie aus dem
 * Bot-Formular. Dort gibt es keinen Zeitplan; die Auswahl der
 * Auswertungs-Vorlage wird als Auswahl übergeben und hier aufgelöst.
 */
export type BotTemplateSettings = Omit<
  BotTemplateRequest,
  'name' | 'schedule' | 'summaryPrompt' | 'summaryTemplateName' | 'summaryModel' | 'summaryTemperature'
>;

interface BotTemplateDialogProps {
  /** Zu bearbeitende Vorlage; null = neue Vorlage anlegen. */
  template: BotTemplateView | null;
  /** Vorbelegung einer neuen Vorlage, etwa aus dem ausgefüllten Bot-Formular. */
  prefill?: BotTemplateSettings;
  /** Sprechererkennung nur anbieten, wenn der Admin sie freigeschaltet hat. */
  diarizeAllowed: boolean;
  /** Sprachvorgabe des Administrators – beschriftet die Standard-Auswahl. */
  defaultSttLanguage: string;
  onClose: () => void;
}

/**
 * Anlegen und Bearbeiten einer persönlichen Bot-Vorlage: dieselben Angaben wie
 * im Bot-Formular, nur mit einem Namen davor. Gespeichert wird hier, damit die
 * Liste im Bot-Bereich nur noch auswählen und starten muss.
 */
export default function BotTemplateDialog({
  template,
  prefill,
  diarizeAllowed,
  defaultSttLanguage,
  onClose,
}: BotTemplateDialogProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const {
    items: promptTemplates,
    loaded: promptTemplatesLoaded,
    loading: promptTemplatesLoading,
  } = useAppSelector((s) => s.promptTemplates);

  // Eigene Auswertungs-Vorlagen nur laden, wenn sie noch nicht im Store sind
  useEffect(() => {
    if (!promptTemplatesLoaded && !promptTemplatesLoading) {
      void dispatch(fetchPromptTemplates());
    }
  }, [dispatch, promptTemplatesLoaded, promptTemplatesLoading]);

  const [name, setName] = useState(template?.name ?? '');
  const [meetingUrl, setMeetingUrl] = useState(template?.meetingUrl ?? prefill?.meetingUrl ?? '');
  const [botName, setBotName] = useState(template?.botName ?? prefill?.botName ?? 'RecorderBot');
  const [autoRecord, setAutoRecord] = useState(template?.autoRecord ?? prefill?.autoRecord ?? true);
  const [recordVideo, setRecordVideo] = useState(
    template?.recordVideo ?? prefill?.recordVideo ?? false,
  );
  const [aiAnalysis, setAiAnalysis] = useState(template?.aiAnalysis ?? prefill?.aiAnalysis ?? true);
  const [diarize, setDiarize] = useState(template?.diarize ?? prefill?.diarize ?? false);
  const [sttLanguage, setSttLanguage] = useState(
    template?.sttLanguage ?? prefill?.sttLanguage ?? '',
  );
  const [schedule, setSchedule] = useState(() => scheduleRequestOf(template?.schedule));
  // '' = „Meeting (Standard)", 'tpl:<id>' = eigene Vorlage, sonst integrierte
  const [preset, setPreset] = useState(
    template?.summaryPreset ?? prefill?.summaryPreset ?? '',
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Gewählte eigene Vorlage inzwischen gelöscht? Dann arbeitet die Bot-Vorlage
  // mit ihrem gespeicherten Stand weiter – das soll man sehen.
  const presetMissing =
    promptTemplatesLoaded &&
    preset.startsWith('tpl:') &&
    !findOwnTemplate(preset, promptTemplates);

  const trimmedName = name.trim();
  const canSave =
    !busy && trimmedName !== '' && meetingUrl.trim() !== '' && scheduleValid(schedule);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSave) return;
    setBusy(true);
    setError(null);
    const body: BotTemplateRequest = {
      name: trimmedName,
      meetingUrl: meetingUrl.trim(),
      botName: botName.trim(),
      autoRecord,
      recordVideo,
      aiAnalysis,
      diarize,
      sttLanguage,
      schedule,
      summaryPreset: preset || null,
      ...resolveSummarySelection(preset, promptTemplates, template),
    };
    try {
      if (template) {
        await dispatch(updateBotTemplate({ id: template.id, ...body })).unwrap();
      } else {
        await dispatch(createBotTemplate(body)).unwrap();
      }
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={template ? t('botTemplates.editTitle') : t('botTemplates.newTitle')}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            {t('common.cancel')}
          </button>
          <button
            type="submit"
            form="bot-template-form"
            className="btn btn-primary"
            disabled={!canSave}
          >
            {busy ? t('common.saving') : t('common.save')}
          </button>
        </>
      }
    >
      {error && <Alert kind="error">{error}</Alert>}
      <form id="bot-template-form" onSubmit={handleSubmit}>
        <div className="form-field">
          <label htmlFor="bot-template-name">{t('botTemplates.nameLabel')}</label>
          <input
            id="bot-template-name"
            type="text"
            value={name}
            maxLength={MAX_NAME_LENGTH}
            placeholder={t('botTemplates.namePlaceholder')}
            disabled={busy}
            autoFocus
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div className="form-field">
          <label htmlFor="bot-template-url">{t('bots.meetingUrl')}</label>
          <input
            id="bot-template-url"
            type="url"
            value={meetingUrl}
            placeholder="https://bbb.example.org/rooms/…"
            disabled={busy}
            required
            onChange={(e) => setMeetingUrl(e.target.value)}
          />
        </div>

        <div className="form-field">
          <label htmlFor="bot-template-bot-name">{t('bots.botName')}</label>
          <input
            id="bot-template-bot-name"
            type="text"
            value={botName}
            maxLength={MAX_BOT_NAME_LENGTH}
            disabled={busy}
            onChange={(e) => setBotName(e.target.value)}
          />
        </div>

        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={autoRecord}
            disabled={busy}
            onChange={(e) => setAutoRecord(e.target.checked)}
          />
          {t('bots.autoRecord')}
        </label>
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={recordVideo}
            disabled={busy}
            onChange={(e) => setRecordVideo(e.target.checked)}
          />
          {t('bots.recordVideo')}
        </label>
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={aiAnalysis}
            disabled={busy}
            onChange={(e) => {
              setAiAnalysis(e.target.checked);
              if (!e.target.checked) setDiarize(false);
            }}
          />
          {t('bots.aiAnalysis')}
        </label>
        {diarizeAllowed && (
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={diarize}
              disabled={busy || !aiAnalysis}
              onChange={(e) => setDiarize(e.target.checked)}
            />
            {t('bots.diarize')}
          </label>
        )}

        <div className="form-field">
          <label htmlFor="bot-template-stt-language">
            {t('sttLanguage.label')}
            <HelpTip text={t('sttLanguage.help')} />
          </label>
          <SttLanguageSelect
            id="bot-template-stt-language"
            value={sttLanguage ?? ''}
            defaultLanguage={defaultSttLanguage}
            disabled={busy || !aiAnalysis}
            onChange={setSttLanguage}
          />
        </div>

        <div className="form-field">
          <label htmlFor="bot-template-preset">
            {t('botTemplates.presetLabel')}
            <HelpTip text={t('botTemplates.presetHelp')} />
          </label>
          <PromptPresetSelect
            id="bot-template-preset"
            value={presetMissing ? '' : preset}
            templates={promptTemplates}
            disabled={busy || !aiAnalysis}
            onChange={setPreset}
          />
          {presetMissing && (
            <span className="muted upload-preset-hint">
              {t('botTemplates.presetMissing', { name: template?.summaryTemplateName ?? '' })}
            </span>
          )}
        </div>

        <BotScheduleFields value={schedule} disabled={busy} onChange={setSchedule} />
        {schedule.enabled && !scheduleValid(schedule) && (
          <span className="muted schedule-hint">{t('botTemplates.schedule.invalid')}</span>
        )}
      </form>
    </Modal>
  );
}
