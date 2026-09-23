import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  createBot,
  createBotFromTemplate,
  fetchBotHistory,
  fetchBots,
  startBotRecording,
  stopBot,
  stopBotRecording,
} from '../store/botsSlice';
import {
  deleteBotTemplate,
  fetchBotTemplates,
} from '../store/botTemplatesSlice';
import StatusBadge from '../components/StatusBadge';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import HelpTip from '../components/HelpTip';
import SttLanguageSelect, { sttLanguageLabel } from '../components/SttLanguageSelect';
import BotTemplateDialog from '../components/BotTemplateDialog';
import type { BotTemplateSettings } from '../components/BotTemplateDialog';
import { scheduleSummary } from '../components/BotScheduleFields';
import PromptPresetSelect, { resolveSummarySelection } from '../components/PromptPresetSelect';
import { fetchPromptTemplates } from '../store/promptTemplatesSlice';
import { errorMessage, fetchUploadConfig } from '../api/client';
import { formatDateTime, formatTime } from '../utils/format';
import { useI18n } from '../i18n';
import type { translate } from '../i18n';
import type { BotTemplateView, BotView } from '../types';

/** Serverseitige Grenzen (BotTemplateController, BotController). */
const MAX_BOT_TEMPLATES = 100;
const MAX_BOT_NAME_LENGTH = 100;

/** Einzeiler unter dem Vorlagennamen: Bot-Name, Modus und Sprache der Aufnahme. */
const summaryOf = (template: BotTemplateView, t: typeof translate): string => {
  const parts = [
    template.botName,
    template.recordVideo ? t('bots.modeVideo') : t('bots.modeAudio'),
    template.aiAnalysis ? t('bots.aiShort') : t('bots.noAiShort'),
  ];
  if (template.aiAnalysis && template.diarize) parts.push(t('bots.diarizeShort'));
  if (!template.autoRecord) parts.push(t('bots.manualRecordShort'));
  if (template.aiAnalysis && template.sttLanguage) {
    parts.push(sttLanguageLabel(template.sttLanguage, t));
  }
  if (template.aiAnalysis && template.summaryPreset && template.summaryTemplateName) {
    parts.push(t('botTemplates.presetShort', { name: template.summaryTemplateName }));
  }
  return parts.join(' · ');
};

/** Zeile zum Zeitplan: Tage und Zeiten, dazu der nächste bzw. laufende Termin. */
const scheduleLineOf = (template: BotTemplateView, t: typeof translate): string | null => {
  const { schedule } = template;
  if (schedule.days.length === 0 || !schedule.start) return null;
  const base = scheduleSummary(schedule, t);
  if (!schedule.enabled) return `${base} · ${t('botTemplates.schedule.off')}`;
  if (!schedule.nextStart) return base;
  const running = new Date(schedule.nextStart).getTime() <= Date.now();
  return running
    ? `${base} · ${t('botTemplates.schedule.running', { time: formatTime(schedule.nextEnd) })}`
    : `${base} · ${t('botTemplates.schedule.next', { date: formatDateTime(schedule.nextStart) })}`;
};

export default function BotsPage() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { items, loading, loaded, error, history, historyLoading, historyError } =
    useAppSelector((s) => s.bots);
  const templates = useAppSelector((s) => s.botTemplates);
  const promptTemplates = useAppSelector((s) => s.promptTemplates);

  const [meetingUrl, setMeetingUrl] = useState('');
  const [botName, setBotName] = useState('RecorderBot');
  const [autoRecord, setAutoRecord] = useState(true);
  const [recordVideo, setRecordVideo] = useState(false);
  const [aiAnalysis, setAiAnalysis] = useState(true);
  const [diarize, setDiarize] = useState(false);
  const [diarizeAllowed, setDiarizeAllowed] = useState(false);
  // '' = Sprachvorgabe des Administrators
  const [sttLanguage, setSttLanguage] = useState('');
  const [defaultSttLanguage, setDefaultSttLanguage] = useState('');
  // Auswertungs-Vorlage: '' = „Meeting (Standard)"
  const [preset, setPreset] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  /** Offener Vorlagen-Dialog: bestehende Vorlage bearbeiten oder neue anlegen. */
  const [editing, setEditing] = useState<
    { template: BotTemplateView | null; prefill?: BotTemplateSettings } | null
  >(null);
  const [confirmDeleteTemplate, setConfirmDeleteTemplate] = useState<BotTemplateView | null>(null);
  /** Vorlage, aus der gerade ein Bot startet bzw. die gerade gelöscht wird. */
  const [templateBusyId, setTemplateBusyId] = useState<string | null>(null);
  const [templateError, setTemplateError] = useState<string | null>(null);

  // Rahmenbedingungen der Auswertung: Sprechererkennung nur anzeigen, wenn der
  // Admin sie freigeschaltet hat; seine Sprachvorgabe beschriftet die Auswahl.
  useEffect(() => {
    fetchUploadConfig()
      .then((cfg) => {
        setDiarizeAllowed(cfg.diarizeAllowed);
        setDefaultSttLanguage(cfg.sttLanguage);
      })
      .catch(() => setDiarizeAllowed(false));
  }, []);

  // Polling alle 5 Sekunden – nur solange diese Seite aktiv ist.
  useEffect(() => {
    dispatch(fetchBots(false));
    const timer = setInterval(() => {
      dispatch(fetchBots(true));
    }, 5000);
    return () => clearInterval(timer);
  }, [dispatch]);

  useEffect(() => {
    void dispatch(fetchBotTemplates());
  }, [dispatch]);

  useEffect(() => {
    if (!promptTemplates.loaded && !promptTemplates.loading) {
      void dispatch(fetchPromptTemplates());
    }
  }, [dispatch, promptTemplates.loaded, promptTemplates.loading]);

  useEffect(() => {
    if (historyOpen) {
      dispatch(fetchBotHistory());
    }
  }, [historyOpen, dispatch]);

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setCreating(true);
    try {
      await dispatch(
        createBot({
          meetingUrl: meetingUrl.trim(),
          botName: botName.trim() || 'RecorderBot',
          autoRecord,
          recordVideo,
          aiAnalysis,
          diarize,
          sttLanguage,
          ...resolveSummarySelection(preset, promptTemplates.items),
        }),
      ).unwrap();
      setMeetingUrl('');
    } catch (err) {
      setFormError(errorMessage(err));
    } finally {
      setCreating(false);
    }
  };

  /** Vorlage ins Formular übernehmen – für den Fall, dass noch etwas abweicht. */
  const applyTemplate = (template: BotTemplateView) => {
    setFormError(null);
    setMeetingUrl(template.meetingUrl);
    setBotName(template.botName);
    setAutoRecord(template.autoRecord);
    setRecordVideo(template.recordVideo);
    setAiAnalysis(template.aiAnalysis);
    setDiarize(template.aiAnalysis && template.diarize);
    setSttLanguage(template.sttLanguage ?? '');
    setPreset(template.summaryPreset ?? '');
  };

  /** Der kurze Weg aus dem Issue: Vorlage auswählen, Bot startet. */
  const startFromTemplate = async (template: BotTemplateView) => {
    setTemplateError(null);
    setTemplateBusyId(template.id);
    try {
      await dispatch(createBotFromTemplate(template.id)).unwrap();
    } catch (err) {
      setTemplateError(errorMessage(err));
    } finally {
      setTemplateBusyId(null);
    }
  };

  const handleDeleteTemplate = async () => {
    if (!confirmDeleteTemplate) return;
    setTemplateError(null);
    setTemplateBusyId(confirmDeleteTemplate.id);
    try {
      await dispatch(deleteBotTemplate(confirmDeleteTemplate.id)).unwrap();
      setConfirmDeleteTemplate(null);
    } catch (err) {
      setTemplateError(errorMessage(err));
      setConfirmDeleteTemplate(null);
    } finally {
      setTemplateBusyId(null);
    }
  };

  return (
    <div className="page">
      <h1>{t('bots.heading')}</h1>

      <section className="card">
        <div className="section-head">
          <h2>{t('botTemplates.section')}</h2>
          <button
            type="button"
            className="btn btn-sm"
            disabled={templates.items.length >= MAX_BOT_TEMPLATES}
            title={
              templates.items.length >= MAX_BOT_TEMPLATES
                ? t('botTemplates.limitReached', { max: MAX_BOT_TEMPLATES })
                : undefined
            }
            onClick={() => setEditing({ template: null })}
          >
            {t('botTemplates.new')}
          </button>
        </div>
        <p className="muted">{t('botTemplates.intro')}</p>
        {templates.error && <Alert kind="error">{templates.error}</Alert>}
        {templateError && <Alert kind="error">{templateError}</Alert>}
        {templates.loading && !templates.loaded && (
          <Spinner label={t('botTemplates.loading')} />
        )}
        {templates.loaded && templates.items.length === 0 && (
          <p className="muted">{t('botTemplates.empty')}</p>
        )}
        {templates.items.length > 0 && (
          <ul className="bot-template-list">
            {templates.items.map((template) => (
              <li key={template.id} className="bot-template-row">
                <div className="bot-template-info">
                  <strong>{template.name}</strong>
                  <span className="muted url-wrap" title={template.meetingUrl}>
                    {template.meetingUrl}
                  </span>
                  <span className="muted bot-template-summary">{summaryOf(template, t)}</span>
                  {scheduleLineOf(template, t) && (
                    <span
                      className={`bot-template-schedule${template.schedule.enabled ? ' active' : ''}`}
                    >
                      {scheduleLineOf(template, t)}
                    </span>
                  )}
                </div>
                <div className="bot-template-actions">
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    disabled={templateBusyId !== null}
                    onClick={() => void startFromTemplate(template)}
                  >
                    {templateBusyId === template.id ? t('bots.submitting') : t('bots.submit')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    title={t('botTemplates.applyHint')}
                    onClick={() => applyTemplate(template)}
                  >
                    {t('botTemplates.apply')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setEditing({ template })}
                  >
                    {t('common.edit')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm btn-danger-text"
                    disabled={templateBusyId !== null}
                    onClick={() => setConfirmDeleteTemplate(template)}
                  >
                    {t('common.delete')}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card">
        <h2>{t('bots.startSection')}</h2>
        {formError && <Alert kind="error">{formError}</Alert>}
        <form onSubmit={handleCreate} className="bot-form">
          <div className="form-field grow">
            <label htmlFor="bot-url">{t('bots.meetingUrl')}</label>
            <input
              id="bot-url"
              type="url"
              value={meetingUrl}
              onChange={(e) => setMeetingUrl(e.target.value)}
              placeholder="https://bbb.example.org/rooms/…"
              required
            />
          </div>
          <div className="form-field">
            <label htmlFor="bot-name">{t('bots.botName')}</label>
            <input
              id="bot-name"
              type="text"
              value={botName}
              maxLength={MAX_BOT_NAME_LENGTH}
              onChange={(e) => setBotName(e.target.value)}
            />
          </div>
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={autoRecord}
              onChange={(e) => setAutoRecord(e.target.checked)}
            />
            {t('bots.autoRecord')}
          </label>
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={recordVideo}
              onChange={(e) => setRecordVideo(e.target.checked)}
            />
            {t('bots.recordVideo')}
          </label>
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={aiAnalysis}
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
                disabled={!aiAnalysis}
                onChange={(e) => setDiarize(e.target.checked)}
              />
              {t('bots.diarize')}
            </label>
          )}
          <div className="form-field">
            <label htmlFor="bot-stt-language">
              {t('sttLanguage.label')}
              <HelpTip text={t('sttLanguage.help')} />
            </label>
            <SttLanguageSelect
              id="bot-stt-language"
              value={sttLanguage}
              defaultLanguage={defaultSttLanguage}
              disabled={!aiAnalysis}
              onChange={setSttLanguage}
            />
          </div>
          <div className="form-field">
            <label htmlFor="bot-preset">
              {t('botTemplates.presetLabel')}
              <HelpTip text={t('botTemplates.presetHelp')} />
            </label>
            <PromptPresetSelect
              id="bot-preset"
              value={preset}
              templates={promptTemplates.items}
              disabled={!aiAnalysis}
              onChange={setPreset}
            />
          </div>
          <button type="submit" className="btn btn-primary" disabled={creating || !meetingUrl.trim()}>
            {creating ? t('bots.submitting') : t('bots.submit')}
          </button>
          <button
            type="button"
            className="btn"
            disabled={
              creating ||
              !meetingUrl.trim() ||
              templates.items.length >= MAX_BOT_TEMPLATES
            }
            title={
              templates.items.length >= MAX_BOT_TEMPLATES
                ? t('botTemplates.limitReached', { max: MAX_BOT_TEMPLATES })
                : t('botTemplates.saveAsHint')
            }
            onClick={() =>
              setEditing({
                template: null,
                prefill: {
                  meetingUrl: meetingUrl.trim(),
                  botName: botName.trim(),
                  autoRecord,
                  recordVideo,
                  aiAnalysis,
                  diarize,
                  sttLanguage,
                  summaryPreset: preset || null,
                },
              })
            }
          >
            {t('botTemplates.saveAs')}
          </button>
        </form>
      </section>

      <section>
        <h2>{t('bots.activeSection')}</h2>
        {error && <Alert kind="error">{error}</Alert>}
        {loading && !loaded && <Spinner label={t('bots.loading')} />}
        {loaded && items.length === 0 && (
          <p className="muted">{t('bots.empty')}</p>
        )}
        <div className="card-grid">
          {items.map((bot) => (
            <BotCard key={bot.sessionId} bot={bot} />
          ))}
        </div>
      </section>

      <section className="history-section">
        <button
          type="button"
          className="collapse-toggle"
          onClick={() => setHistoryOpen((v) => !v)}
          aria-expanded={historyOpen}
        >
          <span className={`chevron${historyOpen ? ' open' : ''}`}>▸</span> {t('bots.history')}
        </button>
        {historyOpen && (
          <div className="card">
            {historyLoading && <Spinner label={t('bots.historyLoading')} />}
            {historyError && <Alert kind="error">{historyError}</Alert>}
            {!historyLoading && !historyError && history.length === 0 && (
              <p className="muted">{t('bots.historyEmpty')}</p>
            )}
            {!historyLoading && history.length > 0 && (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t('bots.historyStarted')}</th>
                      <th>{t('bots.historyEnded')}</th>
                      <th>{t('bots.botName')}</th>
                      <th>{t('bots.historyRoom')}</th>
                      <th>{t('common.status')}</th>
                      <th>{t('bots.historyError')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.map((h) => (
                      <tr key={h.id}>
                        <td>{formatDateTime(h.createdAt)}</td>
                        <td>{formatDateTime(h.endedAt)}</td>
                        <td>{h.botName}</td>
                        <td className="cell-url" title={h.meetingUrl}>{h.roomName ?? h.meetingUrl}</td>
                        <td>
                          <StatusBadge status={h.status} />
                        </td>
                        <td className="cell-error">{h.lastError ?? '–'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </section>

      {editing && (
        <BotTemplateDialog
          template={editing.template}
          prefill={editing.prefill}
          diarizeAllowed={diarizeAllowed}
          defaultSttLanguage={defaultSttLanguage}
          onClose={() => setEditing(null)}
        />
      )}

      {confirmDeleteTemplate && (
        <ConfirmDialog
          title={t('botTemplates.confirmDeleteTitle')}
          message={t('botTemplates.confirmDeleteMessage', { name: confirmDeleteTemplate.name })}
          confirmLabel={t('common.delete')}
          danger
          busy={templateBusyId === confirmDeleteTemplate.id}
          onConfirm={() => void handleDeleteTemplate()}
          onCancel={() => setConfirmDeleteTemplate(null)}
        />
      )}
    </div>
  );
}

function BotCard({ bot }: { bot: BotView }) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<'stopBot' | 'discard' | null>(null);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      await dispatch(fetchBots(true));
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const canStartRecording = bot.status === 'JOINED' && !bot.recordingId;
  const isRecording = bot.status === 'RECORDING';

  return (
    <div className="card bot-card">
      <div className="bot-card-head">
        <strong>{bot.botName}</strong>
        <StatusBadge status={bot.status} />
      </div>
      <div className="bot-card-meta">
        <div className="meta-row">
          <span className="meta-label">{t('bots.cardMeeting')}</span>
          <span className="meta-value url-wrap" title={bot.meetingUrl}>
            {bot.roomName ?? bot.meetingUrl}
          </span>
        </div>
        <div className="meta-row">
          <span className="meta-label">{t('bots.cardParticipants')}</span>
          <span className="meta-value">{bot.participants}</span>
        </div>
        <div className="meta-row">
          <span className="meta-label">{t('bots.cardAudioTracks')}</span>
          <span className="meta-value">{bot.audioTracks}</span>
        </div>
        <div className="meta-row">
          <span className="meta-label">{t('bots.cardMode')}</span>
          <span className="meta-value">
            {bot.recordVideo ? t('bots.modeVideo') : t('bots.modeAudio')}
            {bot.aiAnalysis ? t('bots.modeWithAi') : t('bots.modeWithoutAi')}
          </span>
        </div>
        <div className="meta-row">
          <span className="meta-label">{t('bots.cardStarted')}</span>
          <span className="meta-value">{formatDateTime(bot.createdAt)}</span>
        </div>
        {bot.scheduledStopAt && (
          <div className="meta-row">
            <span className="meta-label">{t('bots.cardScheduledStop')}</span>
            <span className="meta-value">{formatTime(bot.scheduledStopAt)}</span>
          </div>
        )}
        {bot.recordingId && (
          <div className="meta-row">
            <span className="meta-label">{t('bots.cardRecording')}</span>
            <span className="meta-value">
              <Link to={`/recordings/${bot.recordingId}`}>{t('bots.cardRecordingLink')}</Link>
            </span>
          </div>
        )}
      </div>
      {bot.lastError && <Alert kind="error">{bot.lastError}</Alert>}
      {actionError && <Alert kind="error">{actionError}</Alert>}
      <div className="bot-card-actions">
        {canStartRecording && (
          <button
            type="button"
            className="btn btn-primary btn-sm"
            disabled={busy}
            title={t('bots.startRecordingHint')}
            onClick={() => run(() => dispatch(startBotRecording(bot.sessionId)).unwrap())}
          >
            {t('bots.startRecording')}
          </button>
        )}
        {isRecording && (
          <>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              disabled={busy}
              title={t('bots.stopRecordingHint')}
              onClick={() =>
                run(() =>
                  dispatch(
                    stopBotRecording({ sessionId: bot.sessionId, discard: false }),
                  ).unwrap(),
                )
              }
            >
              {t('bots.stopRecording')}
            </button>
            <button
              type="button"
              className="btn btn-sm"
              disabled={busy}
              title={t('bots.discardHint')}
              onClick={() => setConfirm('discard')}
            >
              {t('bots.discard')}
            </button>
          </>
        )}
        <button
          type="button"
          className="btn btn-danger btn-sm"
          disabled={busy}
          title={t('bots.stopBotHint')}
          onClick={() => setConfirm('stopBot')}
        >
          {t('bots.stopBot')}
        </button>
      </div>

      {confirm === 'discard' && (
        <ConfirmDialog
          title={t('bots.confirmDiscardTitle')}
          message={t('bots.confirmDiscardMessage')}
          confirmLabel={t('bots.discard')}
          danger
          busy={busy}
          onConfirm={() =>
            run(() =>
              dispatch(stopBotRecording({ sessionId: bot.sessionId, discard: true })).unwrap(),
            )
          }
          onCancel={() => setConfirm(null)}
        />
      )}
      {confirm === 'stopBot' && (
        <ConfirmDialog
          title={t('bots.confirmStopTitle')}
          message={t('bots.confirmStopMessage')}
          confirmLabel={t('bots.stopBot')}
          danger
          busy={busy}
          onConfirm={() => run(() => dispatch(stopBot(bot.sessionId)).unwrap())}
          onCancel={() => setConfirm(null)}
        />
      )}
    </div>
  );
}
