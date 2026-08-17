import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  createBot,
  fetchBotHistory,
  fetchBots,
  startBotRecording,
  stopBot,
  stopBotRecording,
} from '../store/botsSlice';
import StatusBadge from '../components/StatusBadge';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import HelpTip from '../components/HelpTip';
import SttLanguageSelect from '../components/SttLanguageSelect';
import { errorMessage, fetchUploadConfig } from '../api/client';
import { formatDateTime } from '../utils/format';
import { useI18n } from '../i18n';
import type { BotView } from '../types';

export default function BotsPage() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { items, loading, loaded, error, history, historyLoading, historyError } =
    useAppSelector((s) => s.bots);

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
  const [formError, setFormError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);

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
        }),
      ).unwrap();
      setMeetingUrl('');
    } catch (err) {
      setFormError(errorMessage(err));
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="page">
      <h1>{t('bots.heading')}</h1>

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
          <button type="submit" className="btn btn-primary" disabled={creating || !meetingUrl.trim()}>
            {creating ? t('bots.submitting') : t('bots.submit')}
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
