import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  addRecordingDocument,
  clearDetail,
  deleteRecording,
  deleteRecordingDocument,
  deleteSummary,
  extractRecordingDocument,
  fetchRecordingDetail,
  fetchTranscript,
  processRecording,
  reprocessRecording,
  retranscribeRecording,
  setCurrentSummary,
  transcribeRecording,
  updateParticipant,
  updateSummary,
} from '../store/recordingsSlice';
import StatusBadge from '../components/StatusBadge';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import Markdown from '../components/Markdown';
import ShareDialog from '../components/ShareDialog';
import TranscriptList from '../components/TranscriptList';
import SummaryOptionsDialog from '../components/SummaryOptionsDialog';
import TagEditor from '../components/TagEditor';
import {
  audioUrl,
  documentDownloadUrl,
  documentTextUrl,
  errorMessage,
  fetchDocumentConfig,
  fullAudioDownloadUrl,
  fullAudioUrl,
  summaryDownloadUrl,
  transcriptDownloadUrl,
  videoDownloadUrl,
  videoUrl,
} from '../api/client';
import { formatBytes, formatDateTime, formatDuration } from '../utils/format';
import { useI18n } from '../i18n';
import type { TranslationKey } from '../i18n';
import type {
  DocumentConfigView,
  ParticipantView,
  RecordingDocumentView,
  RecordingSource,
  RecordingStatus,
  SummaryView,
  TranscriptEntry,
} from '../types';

/** Übersetzungsschlüssel für die Herkunft einer Aufnahme ohne Meeting-URL. */
const SOURCE_KEYS: Record<RecordingSource, TranslationKey> = {
  BOT: 'recordingDetail.sourceBot',
  UPLOAD: 'recordingDetail.sourceUpload',
  CAPTURE: 'recordingDetail.sourceCapture',
};

type TabKey = 'summary' | 'transcript' | 'chat' | 'participants' | 'documents' | 'jobs';

const TABS: { key: TabKey; labelKey: TranslationKey }[] = [
  { key: 'summary', labelKey: 'recordingDetail.tabSummary' },
  { key: 'transcript', labelKey: 'recordingDetail.tabTranscript' },
  { key: 'chat', labelKey: 'recordingDetail.tabChat' },
  { key: 'participants', labelKey: 'recordingDetail.tabParticipants' },
  { key: 'documents', labelKey: 'recordingDetail.tabDocuments' },
  { key: 'jobs', labelKey: 'recordingDetail.tabJobs' },
];

const POLL_STATUSES: RecordingStatus[] = ['RECORDING', 'FINALIZING', 'PROCESSING'];

/**
 * Zeile, die zur Abspielposition gehört: die letzte, die bereits begonnen hat.
 * Eine Viertelsekunde Vorlauf, weil der Sprung an eine Zeitmarke im Player
 * gerne minimal davor landet – sonst würde die eigene Zeile nicht leuchten.
 */
function findActiveIndex(entries: TranscriptEntry[], seconds: number): number {
  let active = -1;
  for (let i = 0; i < entries.length; i++) {
    if (entries[i].startSeconds <= seconds + 0.25) active = i;
    else break;
  }
  return active;
}

export default function RecordingDetailPage() {
  const { t } = useI18n();
  const { id } = useParams<{ id: string }>();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const {
    detail,
    detailLoading,
    detailError,
    transcript,
    transcriptLoading,
    transcriptError,
  } = useAppSelector((s) => s.recordings);

  const [tab, setTab] = useState<TabKey>('summary');
  const [shareOpen, setShareOpen] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [processBusy, setProcessBusy] = useState(false);
  const [transcribeBusy, setTranscribeBusy] = useState(false);
  const [confirmReprocess, setConfirmReprocess] = useState(false);
  const [reprocessBusy, setReprocessBusy] = useState(false);
  const [confirmRetranscribe, setConfirmRetranscribe] = useState(false);
  const [retranscribeBusy, setRetranscribeBusy] = useState(false);
  const [summaryOptionsOpen, setSummaryOptionsOpen] = useState(false);
  /** Transkript-Tab: geglättete Fassung (Standard) oder Whisper-Original. */
  const [showOriginal, setShowOriginal] = useState(false);
  // Wiedergabe der Gesamt-Tonspur: Element für den Sprung, Zeile für die Anzeige
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [activeIndex, setActiveIndex] = useState(-1);
  // Tonspur nicht abspielbar (z.B. alte Aufnahme, deren Audiodateien nicht mehr
  // liegen, wo die Datenbank sie vermutet): Dann verspricht die Oberfläche den
  // Sprung gar nicht erst.
  const [audioBroken, setAudioBroken] = useState(false);

  useEffect(() => {
    if (!id) return;
    // Zustand der Wiedergabe gehört zur Aufnahme, nicht zur Seite
    setAudioBroken(false);
    setActiveIndex(-1);
    dispatch(fetchRecordingDetail({ id }));
    return () => {
      dispatch(clearDetail());
    };
  }, [dispatch, id]);

  // Polling, solange die Aufnahme läuft/ausgewertet wird ODER ein Verarbeitungs-
  // Job aktiv ist (PENDING/RUNNING). Letzteres deckt die Phase direkt nach
  // "Jetzt auswerten" ab, in der die Aufnahme noch RECORDED ist.
  const status = detail?.recording.status;
  const videoProcessing =
    detail?.recording.videoStatus === 'RECORDING' ||
    detail?.recording.videoStatus === 'MUXING';
  const hasActiveJob = detail?.jobs?.some(
    (j) => j.status === 'PENDING' || j.status === 'RUNNING',
  ) ?? false;
  // Ein nur WARTENDER Job (z.B. der beim Upload automatisch angelegte, der aufs
  // Nachtfenster wartet) blockiert die manuellen Aktionen nicht: Das Backend
  // stuft ihn beim Klick auf Sofort-Auswertung bzw. Nur-Transkription um.
  const hasRunningJob = detail?.jobs?.some((j) => j.status === 'RUNNING') ?? false;
  // Eine bereits angeforderte volle Sofort-Auswertung darf nicht zur
  // Nur-Transkription herabgestuft werden (das Backend lehnt das ab) -
  // der Button wird dann gar nicht erst angeboten.
  const hasPendingFullImmediate = detail?.jobs?.some(
    (j) => j.status === 'PENDING' && j.immediate && !j.transcribeOnly,
  ) ?? false;
  useEffect(() => {
    // Eine Unterlage in der Textextraktion (bei OCR dauert das Minuten) ist der
    // dritte Grund weiterzufragen - sonst bliebe "wird gelesen…" stehen.
    const documentsPending = detail?.documents.some((d) => d.status === 'PENDING') ?? false;
    const shouldPoll =
      (status && POLL_STATUSES.includes(status)) || hasActiveJob || videoProcessing || documentsPending;
    if (!id || !shouldPoll) return;
    const timer = setInterval(() => {
      dispatch(fetchRecordingDetail({ id, silent: true }));
    }, 4000);
    return () => clearInterval(timer);
  }, [dispatch, id, status, hasActiveJob, videoProcessing, detail?.documents]);

  // Transkript erst laden, wenn der Tab geöffnet wird.
  useEffect(() => {
    if (id && tab === 'transcript' && transcript === null && !transcriptLoading && !transcriptError) {
      dispatch(fetchTranscript(id));
    }
  }, [dispatch, id, tab, transcript, transcriptLoading, transcriptError]);

  // Nach Abschluss eines Verarbeitungs-Jobs das Transkript aktualisieren,
  // sofern es bereits angezeigt wurde (z.B. nach erneuter Transkription).
  const prevActiveJob = useRef(hasActiveJob);
  useEffect(() => {
    if (id && prevActiveJob.current && !hasActiveJob && transcript !== null) {
      dispatch(fetchTranscript(id));
    }
    prevActiveJob.current = hasActiveJob;
  }, [dispatch, id, hasActiveJob, transcript]);

  // Angezeigte Fassung: geglättet, solange sie existiert und nicht bewusst auf
  // Original umgeschaltet wurde.
  const useCorrected = transcript?.hasCorrected === true && !showOriginal;
  const activeEntries = useCorrected
    ? transcript?.correctedEntries ?? []
    : transcript?.entries ?? [];
  const activeTranscriptText = useCorrected
    ? transcript?.correctedTranscript ?? ''
    : transcript?.transcript ?? '';

  // Durchgehende Tonspur: Sie entsteht serverseitig aus den Segmenten und
  // trägt den Sprung aus dem Transkript. Fehlt sie, bleibt das Transkript Text.
  const hasAudio = (detail?.segments ?? []).some((s) => s.hasAudio) && !audioBroken;

  const handleSeek = (seconds: number) => {
    const audio = audioRef.current;
    // Ohne geladene Quelle wirft das Setzen von currentTime (InvalidStateError) –
    // z.B. wenn der Server die Tonspur nicht ausliefern konnte.
    if (!audio || audio.networkState === audio.NETWORK_NO_SOURCE) return;
    try {
      audio.currentTime = seconds;
    } catch {
      setAudioBroken(true);
      return;
    }
    setActiveIndex(findActiveIndex(activeEntries, seconds));
    // Nach einem Klick soll man hören, was dort steht. Lehnt der Browser das
    // Abspielen ab (kein Nutzergesten-Kontext), bleibt es beim Springen.
    void audio.play().catch(() => {});
  };

  // Beim Wechsel der Fassung (korrigiert/original) passen die Zeilennummern
  // nicht mehr – die Hervorhebung wird aus der Abspielposition neu bestimmt.
  // Ohne Player gibt es keine laufende Zeile, sonst leuchtete immer die erste.
  useEffect(() => {
    const audio = audioRef.current;
    setActiveIndex(audio ? findActiveIndex(activeEntries, audio.currentTime) : -1);
  }, [activeEntries]);

  if (detailLoading && !detail) {
    return (
      <div className="page">
        <Spinner label={t('recordingDetail.loading')} />
      </div>
    );
  }

  if (detailError && !detail) {
    return (
      <div className="page">
        <Alert kind="error">{detailError}</Alert>
        <Link to="/recordings" className="btn btn-ghost">
          {t('recordingDetail.backToList')}
        </Link>
      </div>
    );
  }

  if (!detail || !id) {
    return null;
  }

  const rec = detail.recording;
  // Herunterladen gibt es die aktuelle Fassung - genau die kennt der Endpunkt.
  const hasDownloadableSummary = detail.summaries.some((s) => s.current);

  const handleProcess = async () => {
    setActionError(null);
    setProcessBusy(true);
    try {
      await dispatch(processRecording(id)).unwrap();
      await dispatch(fetchRecordingDetail({ id, silent: true }));
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setProcessBusy(false);
    }
  };

  const handleTranscribe = async () => {
    setActionError(null);
    setTranscribeBusy(true);
    try {
      await dispatch(transcribeRecording(id)).unwrap();
      await dispatch(fetchRecordingDetail({ id, silent: true }));
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setTranscribeBusy(false);
    }
  };

  const handleReprocess = async () => {
    setActionError(null);
    setReprocessBusy(true);
    try {
      await dispatch(reprocessRecording(id)).unwrap();
      await dispatch(fetchRecordingDetail({ id, silent: true }));
      setConfirmReprocess(false);
    } catch (e) {
      setActionError(errorMessage(e));
      setConfirmReprocess(false);
    } finally {
      setReprocessBusy(false);
    }
  };

  const handleRetranscribe = async () => {
    setActionError(null);
    setRetranscribeBusy(true);
    try {
      await dispatch(retranscribeRecording(id)).unwrap();
      await dispatch(fetchRecordingDetail({ id, silent: true }));
      setConfirmRetranscribe(false);
    } catch (e) {
      setActionError(errorMessage(e));
      setConfirmRetranscribe(false);
    } finally {
      setRetranscribeBusy(false);
    }
  };

  const handleDelete = async () => {
    setActionError(null);
    setDeleteBusy(true);
    try {
      await dispatch(deleteRecording(id)).unwrap();
      navigate('/recordings');
    } catch (e) {
      setActionError(errorMessage(e));
      setDeleteBusy(false);
      setConfirmDelete(false);
    }
  };

  return (
    <div className="page">
      <div className="breadcrumb">
        <Link to="/recordings">{t('app.nav.recordings')}</Link> <span>/</span>{' '}
        {rec.title ?? t('recordingDetail.fallbackTitle', { date: formatDateTime(rec.startedAt) })}
      </div>

      <div className="card detail-head">
        <div className="detail-head-top">
          <h1>
            {rec.title ??
              t('recordingDetail.fallbackTitle', { date: formatDateTime(rec.startedAt) })}
          </h1>
          <StatusBadge status={rec.status} />
        </div>
        <div className="detail-meta">
          <div className="meta-row">
            <span className="meta-label">{t('recordingDetail.period')}</span>
            <span className="meta-value">
              {formatDateTime(rec.startedAt)}
              {rec.endedAt ? ` – ${formatDateTime(rec.endedAt)}` : ` ${t('common.running')}`}
            </span>
          </div>
          <div className="meta-row">
            <span className="meta-label">{t('common.duration')}</span>
            <span className="meta-value">{formatDuration(rec.durationMs)}</span>
          </div>
          <div className="meta-row">
            <span className="meta-label">
              {rec.source === 'BOT' ? t('recordingDetail.meetingUrl') : t('recordingDetail.source')}
            </span>
            <span className="meta-value url-wrap" title={rec.meetingUrl ?? undefined}>
              {rec.source === 'BOT' ? rec.meetingUrl : t(SOURCE_KEYS[rec.source])}
            </span>
          </div>
          <div className="meta-row">
            <span className="meta-label">{t('common.owner')}</span>
            <span className="meta-value">
              {rec.mine ? t('common.me') : rec.owner?.displayName ?? t('common.unknown')}
            </span>
          </div>
          <div className="meta-row">
            <span className="meta-label">{t('recordingDetail.tags')}</span>
            <span className="meta-value">
              <TagEditor recordingId={rec.id} tags={rec.tags} editable={rec.mine} />
            </span>
          </div>
          {rec.discardReason && (
            <div className="meta-row">
              <span className="meta-label">{t('recordingDetail.discarded')}</span>
              <span className="meta-value">{rec.discardReason}</span>
            </div>
          )}
        </div>

        {actionError && <Alert kind="error">{actionError}</Alert>}

        <div className="detail-actions">
          {(rec.status === 'RECORDED' || rec.status === 'FAILED') && (
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleProcess}
              disabled={processBusy}
            >
              {processBusy ? t('recordingDetail.starting') : t('recordingDetail.processNow')}
            </button>
          )}
          {(rec.status === 'RECORDED' || rec.status === 'FAILED') &&
            !hasRunningJob &&
            !hasPendingFullImmediate && (
              <button
                type="button"
                className="btn"
                onClick={handleTranscribe}
                disabled={transcribeBusy}
                title={t('recordingDetail.transcribeOnlyHint')}
              >
                {transcribeBusy ? t('recordingDetail.starting') : t('recordingDetail.transcribeOnly')}
              </button>
            )}
          {rec.status === 'TRANSCRIBED' && !hasActiveJob && (
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleProcess}
              disabled={processBusy}
              title={t('recordingDetail.startAiHint')}
            >
              {processBusy ? t('recordingDetail.starting') : t('recordingDetail.startAi')}
            </button>
          )}
          {rec.mine && rec.status === 'DONE' && detail.summaries.length > 0 && !hasActiveJob && (
            <button
              type="button"
              className="btn"
              onClick={() => setConfirmReprocess(true)}
              disabled={reprocessBusy}
            >
              {t('recordingDetail.reprocess')}
            </button>
          )}
          {rec.mine &&
            (rec.status === 'DONE' || rec.status === 'FAILED' || rec.status === 'TRANSCRIBED') &&
            detail.segments.some((s) => s.hasAudio) &&
            !hasActiveJob && (
              <button
                type="button"
                className="btn"
                onClick={() => setConfirmRetranscribe(true)}
                disabled={retranscribeBusy}
              >
                {t('recordingDetail.retranscribe')}
              </button>
            )}
          {rec.mine && rec.status !== 'RECORDING' && rec.status !== 'FINALIZING' && (
            <button type="button" className="btn" onClick={() => setSummaryOptionsOpen(true)}>
              {t('recordingDetail.summaryOptions')}
              {(detail.summaryOptions.prompt !== null ||
                detail.summaryOptions.maxWords !== null ||
                detail.summaryOptions.language !== null ||
                detail.summaryOptions.sttLanguage !== null) && (
                <span className="tag">{t('recordingDetail.customized')}</span>
              )}
            </button>
          )}
          {hasAudio && (
            <a className="btn" href={fullAudioDownloadUrl(id)}>
              {t('recordingDetail.downloadAudio')}
            </a>
          )}
          {hasDownloadableSummary && (
            <>
              <a className="btn" href={summaryDownloadUrl(id)}>
                {t('recordingDetail.downloadSummary')}
              </a>
              <a
                className="btn"
                href={summaryDownloadUrl(id, 'doc')}
                title={t('recordingDetail.downloadWordHint')}
              >
                {t('recordingDetail.downloadSummaryWord')}
              </a>
            </>
          )}
          {rec.mine && (
            <button type="button" className="btn" onClick={() => setShareOpen(true)}>
              {t('recordingDetail.share')}
            </button>
          )}
          {rec.mine && (
            <button
              type="button"
              className="btn btn-danger"
              onClick={() => setConfirmDelete(true)}
            >
              {t('common.delete')}
            </button>
          )}
        </div>
      </div>

      {rec.recordVideo && (
        <section className="card">
          <h2>{t('recordingDetail.videoHeading')}</h2>
          {rec.videoStatus === 'READY' ? (
            <div className="recording-video">
              <video controls preload="metadata" src={videoUrl(id)} className="video-player" />
              <div>
                <a className="btn btn-ghost btn-sm" href={videoDownloadUrl(id)} download>
                  {t('recordingDetail.videoDownload')}
                </a>
              </div>
            </div>
          ) : rec.videoStatus === 'FAILED' ? (
            <Alert kind="error">{t('recordingDetail.videoFailed')}</Alert>
          ) : (
            <p className="muted">{t('recordingDetail.videoProcessing')}</p>
          )}
        </section>
      )}

      <section className="card">
        <h2>{t('recordingDetail.segmentsHeading')}</h2>
        {detail.segments.length === 0 && (
          <p className="muted">{t('recordingDetail.segmentsEmpty')}</p>
        )}
        <ul className="segment-list">
          {detail.segments.map((seg, idx) => (
            <li key={seg.id} className="segment-item">
              <div className="segment-info">
                <span className="segment-seq">{t('recordingDetail.segment', { index: idx + 1 })}</span>
                <StatusBadge status={seg.status} />
                <span className="muted">
                  {formatDuration(seg.durationMs)} · {formatBytes(seg.sizeBytes)}
                </span>
                {seg.hasTranscript && <span className="tag">{t('recordingDetail.tagTranscript')}</span>}
              </div>
              {seg.hasAudio ? (
                <div className="segment-audio">
                  <audio controls preload="none" src={audioUrl(id, seg.id)} />
                  <a className="btn btn-ghost btn-sm" href={audioUrl(id, seg.id)} download>
                    {t('recordingDetail.download')}
                  </a>
                </div>
              ) : (
                <span className="muted">{t('recordingDetail.noAudio')}</span>
              )}
            </li>
          ))}
        </ul>
      </section>

      <div className="tabs">
        {TABS.map((entry) => (
          <button
            key={entry.key}
            type="button"
            className={`tab${tab === entry.key ? ' active' : ''}`}
            onClick={() => setTab(entry.key)}
          >
            {t(entry.labelKey)}
          </button>
        ))}
      </div>

      <div className="card tab-panel">
        {/* Der Zusammenfassungs-Tab bleibt gemountet (nur versteckt), damit eine
            laufende Bearbeitung beim Tab-Wechsel nicht verloren geht. */}
        <div hidden={tab !== 'summary'}>
          <SummaryTab
            recordingId={id}
            onProcess={handleProcess}
            processBusy={processBusy}
            onTranscribe={handleTranscribe}
            transcribeBusy={transcribeBusy}
          />
        </div>

        {tab === 'transcript' && (
          <>
            {transcriptLoading && <Spinner label={t('recordingDetail.transcriptLoading')} />}
            {transcriptError && <Alert kind="error">{transcriptError}</Alert>}
            {/* Ein Player für die ganze Aufnahme; er bleibt beim Scrollen oben
                stehen, damit der Sprung aus einer Zeile hörbar bleibt. */}
            {hasAudio && !transcriptLoading && !transcriptError && (
              <div className="transcript-player">
                <audio
                  ref={audioRef}
                  controls
                  preload="metadata"
                  src={fullAudioUrl(id)}
                  // Alte Aufnahmen ohne (noch) vorhandene Audiodateien: Der
                  // Player verschwindet, statt einen toten Knopf zu zeigen.
                  onError={() => setAudioBroken(true)}
                  // Die Zeit kommt aus dem Element, NICHT aus dem Ereignis:
                  // React setzt currentTarget zurück, sobald der Handler
                  // zurückkehrt – in der nachgelagerten Zustandsfunktion wäre es
                  // null. Der Vergleich mit dem vorherigen Wert hält das Neu-
                  // Zeichnen bei 4 Ereignissen je Sekunde auf Zeilenwechsel.
                  onTimeUpdate={() => {
                    const audio = audioRef.current;
                    if (!audio) return;
                    const next = findActiveIndex(activeEntries, audio.currentTime);
                    setActiveIndex((prev) => (prev === next ? prev : next));
                  }}
                />
                <span className="muted">{t('recordingDetail.seekHint')}</span>
              </div>
            )}
            {audioBroken && !transcriptLoading && !transcriptError && (
              <p className="muted">{t('recordingDetail.audioUnavailable')}</p>
            )}
            {!transcriptLoading && !transcriptError && transcript?.hasCorrected && (
              <div className="transcript-variant">
                <div className="filter-tabs">
                  <button
                    type="button"
                    className={`filter-tab${showOriginal ? '' : ' active'}`}
                    onClick={() => setShowOriginal(false)}
                  >
                    {t('recordingDetail.variantCorrected')}
                  </button>
                  <button
                    type="button"
                    className={`filter-tab${showOriginal ? ' active' : ''}`}
                    onClick={() => setShowOriginal(true)}
                  >
                    {t('recordingDetail.variantOriginal')}
                  </button>
                </div>
                <p className="muted">
                  {showOriginal
                    ? t('recordingDetail.variantOriginalHint')
                    : t('recordingDetail.variantCorrectedHint')}
                </p>
              </div>
            )}
            {!transcriptLoading && !transcriptError && transcript
              && !transcript.hasCorrected && transcript.correctionStatus === 'FAILED' && (
              <Alert kind="info">{t('recordingDetail.correctionFailed')}</Alert>
            )}
            {/* Der Download folgt der angezeigten Fassung – wer Original liest,
                bekommt auch Original in die Datei. */}
            {!transcriptLoading && !transcriptError
              && (activeEntries.length > 0 || activeTranscriptText !== '') && (
              <div className="transcript-downloads">
                <a className="btn btn-sm" href={transcriptDownloadUrl(id, !useCorrected)}>
                  {t('recordingDetail.downloadTranscript')}
                </a>
                <a
                  className="btn btn-sm"
                  href={transcriptDownloadUrl(id, !useCorrected, 'doc')}
                  title={t('recordingDetail.downloadWordHint')}
                >
                  {t('recordingDetail.downloadTranscriptWord')}
                </a>
                {transcript?.hasCorrected && (
                  <span className="muted">
                    {t('recordingDetail.downloadVariantHint', {
                      variant: useCorrected
                        ? t('recordingDetail.variantCorrected')
                        : t('recordingDetail.variantOriginal'),
                    })}
                  </span>
                )}
              </div>
            )}
            {!transcriptLoading && !transcriptError && (
              activeEntries.length > 0 ? (
                <TranscriptList
                  entries={activeEntries}
                  participants={detail.participants}
                  onSeek={hasAudio ? handleSeek : undefined}
                  activeIndex={activeIndex}
                />
              ) : activeTranscriptText ? (
                <Markdown>{activeTranscriptText}</Markdown>
              ) : (
                <p className="muted">{t('recordingDetail.transcriptEmpty')}</p>
              )
            )}
          </>
        )}

        {tab === 'chat' && (
          detail.chatLog ? (
            <pre className="log-pre">{detail.chatLog}</pre>
          ) : (
            <p className="muted">{t('recordingDetail.chatEmpty')}</p>
          )
        )}

        {tab === 'participants' && (
          <ParticipantsTab
            recordingId={id}
            participants={detail.participants}
            participantsLog={detail.participantsLog}
            canEdit={rec.mine}
          />
        )}

        {tab === 'documents' && <DocumentsTab recordingId={id} canEdit={rec.mine} />}

        {tab === 'jobs' && (
          detail.jobs.length === 0 ? (
            <p className="muted">{t('recordingDetail.jobsEmpty')}</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t('recordingDetail.jobsCreated')}</th>
                    <th>{t('common.status')}</th>
                    <th>{t('recordingDetail.jobsMode')}</th>
                    <th>{t('recordingDetail.jobsAttempts')}</th>
                    <th>{t('recordingDetail.jobsFinished')}</th>
                    <th>{t('recordingDetail.jobsError')}</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.jobs.map((job) => (
                    <tr key={job.id}>
                      <td>{formatDateTime(job.createdAt)}</td>
                      <td>
                        <StatusBadge status={job.status} />
                      </td>
                      <td>
                        {job.immediate ? t('recordingDetail.modeImmediate') : t('recordingDetail.modeWindow')}
                        {job.transcribeOnly ? t('recordingDetail.modeTranscribeOnly') : ''}
                      </td>
                      <td>{job.attempts}</td>
                      <td>{formatDateTime(job.finishedAt)}</td>
                      <td className="cell-error">{job.lastError ?? '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}
      </div>

      {shareOpen && <ShareDialog recordingId={id} onClose={() => setShareOpen(false)} />}
      {summaryOptionsOpen && (
        <SummaryOptionsDialog
          recordingId={id}
          options={detail.summaryOptions}
          onClose={() => setSummaryOptionsOpen(false)}
        />
      )}
      {confirmReprocess && (
        <ConfirmDialog
          title={t('recordingDetail.confirmReprocessTitle')}
          message={t('recordingDetail.confirmReprocessMessage')}
          confirmLabel={t('recordingDetail.reprocess')}
          danger
          busy={reprocessBusy}
          onConfirm={handleReprocess}
          onCancel={() => setConfirmReprocess(false)}
        />
      )}
      {confirmRetranscribe && (
        <ConfirmDialog
          title={t('recordingDetail.confirmRetranscribeTitle')}
          message={
            rec.status === 'TRANSCRIBED'
              ? t('recordingDetail.confirmRetranscribeTranscribed')
              : t('recordingDetail.confirmRetranscribeFull')
          }
          confirmLabel={t('recordingDetail.retranscribe')}
          danger
          busy={retranscribeBusy}
          onConfirm={handleRetranscribe}
          onCancel={() => setConfirmRetranscribe(false)}
        />
      )}
      {confirmDelete && (
        <ConfirmDialog
          title={t('recordingDetail.confirmDeleteTitle')}
          message={t('recordingDetail.confirmDeleteMessage')}
          confirmLabel={t('common.delete')}
          danger
          busy={deleteBusy}
          onConfirm={handleDelete}
          onCancel={() => setConfirmDelete(false)}
        />
      )}
    </div>
  );
}

/**
 * Teilnehmer-Tab: die aus der Diarisierung erkannten Sprecher mit editierbarem
 * Namen (nur Besitzer). Umbenennungen wirken sofort auf die Transkript-Anzeige
 * und auf künftige Zusammenfassungen. Darunter das Sitzungs-Protokoll des Bots,
 * sofern vorhanden (Bot-Aufnahmen).
 */
function ParticipantsTab({
  recordingId,
  participants,
  participantsLog,
  canEdit,
}: {
  recordingId: string;
  participants: ParticipantView[];
  participantsLog: string | null;
  canEdit: boolean;
}) {
  const dispatch = useAppDispatch();
  const [editId, setEditId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const { t } = useI18n();
  const [saveBusy, setSaveBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const startEdit = (p: ParticipantView) => {
    setEditId(p.id);
    setEditName(p.displayName);
    setError(null);
  };

  const handleSave = async () => {
    if (!editId || !editName.trim()) return;
    setError(null);
    setSaveBusy(true);
    try {
      await dispatch(
        updateParticipant({ recordingId, participantId: editId, displayName: editName.trim() }),
      ).unwrap();
      setEditId(null);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSaveBusy(false);
    }
  };

  return (
    <div>
      {error && <Alert kind="error">{error}</Alert>}
      {participants.length > 0 ? (
        <>
          <p className="muted">
            {t('recordingDetail.participantsIntro')}
            {canEdit && t('recordingDetail.participantsIntroEditable')}
          </p>
          <ul className="participant-list">
            {participants.map((p, i) => (
              <li key={p.id} className="participant-item">
                {editId === p.id ? (
                  <form
                    className="participant-edit"
                    onSubmit={(e) => {
                      e.preventDefault();
                      void handleSave();
                    }}
                  >
                    <input
                      value={editName}
                      onChange={(e) => setEditName(e.target.value)}
                      maxLength={200}
                      autoFocus
                    />
                    <button
                      type="submit"
                      className="btn btn-primary btn-sm"
                      disabled={saveBusy || !editName.trim()}
                    >
                      {saveBusy ? t('recordingDetail.saving') : t('common.save')}
                    </button>
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      onClick={() => setEditId(null)}
                      disabled={saveBusy}
                    >
                      {t('common.cancel')}
                    </button>
                  </form>
                ) : (
                  <>
                    <span className={`transcript-speaker speaker-c${i % 6}`}>{p.displayName}</span>
                    {p.speakerLabel && p.speakerLabel !== p.displayName && (
                      <span className="muted participant-label">{p.speakerLabel}</span>
                    )}
                    {canEdit && (
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm"
                        onClick={() => startEdit(p)}
                      >
                        {t('recordingDetail.rename')}
                      </button>
                    )}
                  </>
                )}
              </li>
            ))}
          </ul>
        </>
      ) : (
        <p className="muted">{t('recordingDetail.participantsEmpty')}</p>
      )}
      {participantsLog && (
        <>
          <h3 className="participant-log-head">{t('recordingDetail.participantsLogHeading')}</h3>
          <pre className="log-pre">{participantsLog}</pre>
        </>
      )}
    </div>
  );
}

/**
 * Beigefügte Unterlagen einer Aufnahme: Tagesordnung, Folien, ein Papier, das
 * durchgesprochen wurde. Ihr Text geht in die nächste KI-Auswertung ein.
 *
 * Der Zustand jeder Unterlage steht dabei: Bei einem Scan mit OCR dauert die
 * Textextraktion Minuten, und ein Scan ohne OCR liefert gar nichts – das muss
 * sichtbar sein, bevor jemand die Auswertung startet und sich wundert.
 */
function DocumentsTab({ recordingId, canEdit }: { recordingId: string; canEdit: boolean }) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const documents = useAppSelector((s) => s.recordings.detail?.documents ?? []);
  const [config, setConfig] = useState<DocumentConfigView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<RecordingDocumentView | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchDocumentConfig()
      .then((loaded) => {
        if (!cancelled) setConfig(loaded);
      })
      .catch(() => {
        // Ohne Konfiguration bleiben nur die Serverfehler beim Hochladen - das
        // ist ärgerlich, aber kein Grund, den Tab leer zu lassen.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleUpload = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      await dispatch(addRecordingDocument({ recordingId, file })).unwrap();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const handleDelete = async () => {
    if (!confirmDelete) return;
    setBusy(true);
    try {
      await dispatch(
        deleteRecordingDocument({ recordingId, documentId: confirmDelete.id }),
      ).unwrap();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      setConfirmDelete(null);
    }
  };

  const handleExtract = async (documentId: string) => {
    setBusy(true);
    setError(null);
    try {
      await dispatch(extractRecordingDocument({ recordingId, documentId })).unwrap();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const accept = config ? config.extensions.map((e) => `.${e}`).join(',') : undefined;
  const disabledByAdmin = config !== null && !config.enabled;

  return (
    <div className="documents-tab">
      <p className="muted">{t('recordingDetail.documentsIntro')}</p>

      {disabledByAdmin && <Alert kind="info">{t('recordingDetail.documentsDisabled')}</Alert>}
      {config !== null && config.enabled && !config.tikaConfigured && (
        <Alert kind="info">{t('recordingDetail.documentsNoTika')}</Alert>
      )}
      {error && <Alert kind="error">{error}</Alert>}

      {canEdit && !disabledByAdmin && (
        <div className="form-field documents-add">
          <label htmlFor="document-file">{t('recordingDetail.documentsAddLabel')}</label>
          <input
            id="document-file"
            type="file"
            ref={fileInput}
            accept={accept}
            disabled={busy}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void handleUpload(file);
            }}
          />
          <span className="muted">
            {config
              ? t('recordingDetail.documentsAddHint', {
                  size: formatBytes(config.maxFileSizeBytes),
                  types: config.extensions.join(', '),
                })
              : ''}
          </span>
        </div>
      )}

      {busy && <Spinner label={t('recordingDetail.documentsBusy')} />}

      {documents.length === 0 ? (
        <p className="muted">{t('recordingDetail.documentsEmpty')}</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t('recordingDetail.documentsColumnName')}</th>
                <th>{t('recordingDetail.documentsColumnSize')}</th>
                <th>{t('common.status')}</th>
                <th>{t('recordingDetail.documentsColumnText')}</th>
                {canEdit && <th></th>}
              </tr>
            </thead>
            <tbody>
              {documents.map((document) => (
                <tr key={document.id}>
                  <td>
                    <a href={documentDownloadUrl(recordingId, document.id)} download>
                      {document.filename}
                    </a>
                  </td>
                  <td>{formatBytes(document.sizeBytes)}</td>
                  <td>
                    <StatusBadge status={document.status} />
                  </td>
                  <td>
                    {document.status === 'READY' && document.textChars !== null ? (
                      <a
                        href={documentTextUrl(recordingId, document.id)}
                        target="_blank"
                        rel="noreferrer"
                      >
                        {t('recordingDetail.documentsChars', { count: document.textChars })}
                      </a>
                    ) : document.status === 'PENDING' ? (
                      <span className="muted">{t('recordingDetail.documentsExtracting')}</span>
                    ) : (
                      <span className="cell-error">
                        {document.error ?? t('recordingDetail.documentsNoText')}
                      </span>
                    )}
                  </td>
                  {canEdit && (
                    <td className="row-actions">
                      {document.status === 'FAILED' && (
                        <button
                          type="button"
                          className="btn btn-ghost btn-sm"
                          disabled={busy}
                          title={t('recordingDetail.documentsRetryHint')}
                          onClick={() => void handleExtract(document.id)}
                        >
                          {t('recordingDetail.documentsRetry')}
                        </button>
                      )}
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm btn-danger-text"
                        disabled={busy}
                        onClick={() => setConfirmDelete(document)}
                      >
                        {t('common.delete')}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {documents.some((d) => d.status === 'READY') && (
        <p className="muted documents-note">{t('recordingDetail.documentsEffectHint')}</p>
      )}

      {confirmDelete && (
        <ConfirmDialog
          title={t('recordingDetail.documentsConfirmDeleteTitle')}
          message={t('recordingDetail.documentsConfirmDeleteMessage', {
            name: confirmDelete.filename,
          })}
          confirmLabel={t('common.delete')}
          danger
          busy={busy}
          onConfirm={handleDelete}
          onCancel={() => setConfirmDelete(null)}
        />
      )}
    </div>
  );
}

function SummaryTab({
  recordingId,
  onProcess,
  processBusy,
  onTranscribe,
  transcribeBusy,
}: {
  recordingId: string;
  onProcess: () => void;
  processBusy: boolean;
  onTranscribe: () => void;
  transcribeBusy: boolean;
}) {
  const dispatch = useAppDispatch();
  const detail = useAppSelector((s) => s.recordings.detail);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  /** Vom Nutzer gewählte Fassung; null = die aktuelle anzeigen. */
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editId, setEditId] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  const [editBusy, setEditBusy] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);
  const { t } = useI18n();

  if (!detail) return null;
  const rec = detail.recording;
  const summaries = detail.summaries;
  const activeJob = detail.jobs.find(
    (j) => j.status === 'PENDING' || j.status === 'RUNNING',
  );
  const jobKind = activeJob?.transcribeOnly
    ? t('recordingDetail.jobKindTranscription')
    : t('recordingDetail.jobKindAnalysis');
  const jobProgressLabel = activeJob
    ? activeJob.status === 'PENDING'
      ? t('recordingDetail.jobQueued', { kind: jobKind })
      : t('recordingDetail.jobRunning', { kind: jobKind }) +
        (activeJob.attempts > 1
          ? t('recordingDetail.jobAttempt', { attempt: activeJob.attempts })
          : '')
    : null;

  const handleDeleteSummary = async (summaryId: string) => {
    setDeleteError(null);
    try {
      await dispatch(deleteSummary({ recordingId, summaryId })).unwrap();
      // Die gelöschte Fassung war womöglich die angezeigte
      if (selectedId === summaryId) setSelectedId(null);
    } catch (e) {
      setDeleteError(errorMessage(e));
    }
  };

  const handleSetCurrent = async (summaryId: string) => {
    setDeleteError(null);
    try {
      await dispatch(setCurrentSummary({ recordingId, summaryId })).unwrap();
    } catch (e) {
      setDeleteError(errorMessage(e));
    }
  };

  const startEdit = (summaryId: string, markdown: string) => {
    setEditId(summaryId);
    setEditText(markdown);
    setEditError(null);
  };

  const handleSaveEdit = async () => {
    if (!editId || !editText.trim()) return;
    setEditError(null);
    setEditBusy(true);
    try {
      await dispatch(
        updateSummary({ recordingId, summaryId: editId, markdown: editText }),
      ).unwrap();
      setEditId(null);
    } catch (e) {
      setEditError(errorMessage(e));
    } finally {
      setEditBusy(false);
    }
  };

  // Die bearbeitete Zusammenfassung kann waehrend des Tippens verschwinden
  // (z.B. weil "Erneut auswerten" sie ersetzt hat). Der Entwurf bleibt dann
  // erhalten und kann in die neue Zusammenfassung uebernommen werden.
  const editOrphaned = editId !== null && !summaries.some((s) => s.id === editId);
  const currentSummary = summaries.find((s) => s.current);

  const handleAdoptEdit = async () => {
    if (!currentSummary || !editText.trim()) return;
    setEditError(null);
    setEditBusy(true);
    try {
      await dispatch(
        updateSummary({ recordingId, summaryId: currentSummary.id, markdown: editText }),
      ).unwrap();
      setEditId(null);
    } catch (e) {
      setEditError(errorMessage(e));
    } finally {
      setEditBusy(false);
    }
  };

  if (editOrphaned) {
    return (
      <div className="summary-edit">
        <Alert kind="info">{t('recordingDetail.editOrphaned')}</Alert>
        {editError && <Alert kind="error">{editError}</Alert>}
        <textarea value={editText} onChange={(e) => setEditText(e.target.value)} />
        <div className="summary-edit-actions">
          {currentSummary && (
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleAdoptEdit}
              disabled={editBusy || !editText.trim()}
            >
              {editBusy ? t('recordingDetail.saving') : t('recordingDetail.adoptEdit')}
            </button>
          )}
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => setEditId(null)}
            disabled={editBusy}
          >
            {t('recordingDetail.discardDraft')}
          </button>
        </div>
      </div>
    );
  }

  // Ein wartender (nicht-sofortiger) Job laeuft erst im naechtlichen Zeitfenster –
  // bis dahin stehen die manuellen Aktionen weiterhin zur Verfuegung.
  const waitingForWindow = !!activeJob && activeJob.status === 'PENDING' && !activeJob.immediate;

  if (summaries.length === 0) {
    if (rec.status === 'PROCESSING' || (activeJob && !waitingForWindow)) {
      return <Spinner label={jobProgressLabel ?? t('recordingDetail.analysisRunning')} />;
    }
    if (waitingForWindow) {
      return (
        <div className="summary-hint">
          <p className="muted">{t('recordingDetail.waitingWindowHint')}</p>
          <div className="summary-hint-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={onProcess}
              disabled={processBusy || transcribeBusy}
            >
              {processBusy ? t('recordingDetail.starting') : t('recordingDetail.processNow')}
            </button>
            <button
              type="button"
              className="btn"
              onClick={onTranscribe}
              disabled={processBusy || transcribeBusy}
              title={t('recordingDetail.transcribeOnlyHint')}
            >
              {transcribeBusy ? t('recordingDetail.starting') : t('recordingDetail.transcribeOnly')}
            </button>
          </div>
        </div>
      );
    }
    if (rec.status === 'TRANSCRIBED') {
      return (
        <div className="summary-hint">
          <p className="muted">{t('recordingDetail.transcribedHint')}</p>
          {detail.participants.length > 0 && (
            <p className="muted">{t('recordingDetail.transcribedTip')}</p>
          )}
          <button
            type="button"
            className="btn btn-primary"
            onClick={onProcess}
            disabled={processBusy}
          >
            {processBusy ? t('recordingDetail.starting') : t('recordingDetail.startAi')}
          </button>
        </div>
      );
    }
    if (rec.status === 'RECORDED' || rec.status === 'FAILED') {
      return (
        <div className="summary-hint">
          <p className="muted">{t('recordingDetail.noSummaryHint')}</p>
          <div className="summary-hint-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={onProcess}
              disabled={processBusy || transcribeBusy}
            >
              {processBusy ? t('recordingDetail.starting') : t('recordingDetail.processNow')}
            </button>
            <button
              type="button"
              className="btn"
              onClick={onTranscribe}
              disabled={processBusy || transcribeBusy}
              title={t('recordingDetail.transcribeOnlyHint')}
            >
              {transcribeBusy ? t('recordingDetail.starting') : t('recordingDetail.transcribeOnly')}
            </button>
          </div>
        </div>
      );
    }
    if (rec.status === 'RECORDING' || rec.status === 'FINALIZING') {
      return <p className="muted">{t('recordingDetail.stillRecording')}</p>;
    }
    return <p className="muted">{t('recordingDetail.noSummary')}</p>;
  }

  // Angezeigt wird die aktuelle Fassung, solange keine andere gewählt ist. Eine
  // gewählte Fassung kann verschwinden (Löschen in einem anderen Tab) – dann
  // fällt die Anzeige auf die aktuelle zurück.
  const shown = summaries.find((s) => s.id === selectedId) ?? currentSummary ?? summaries[0];

  /**
   * Modell mit Temperatur: Zwei Fassungen desselben Modells unterscheiden sich
   * sonst nicht sichtbar.
   */
  const modelLabel = (summary: SummaryView) =>
    summary.model === null
      ? null
      : summary.temperature === null
        ? summary.model
        : `${summary.model} · T ${summary.temperature}`;

  /** Fassung in der Auswahlliste: Vorlage, Modell und Zeitpunkt machen sie unterscheidbar. */
  const versionLabel = (summary: SummaryView) =>
    [
      summary.current ? t('recordingDetail.versionCurrent') : null,
      summary.templateName ?? t('recordingDetail.versionNoTemplate'),
      modelLabel(summary),
      formatDateTime(summary.createdAt),
      summary.editedAt ? t('recordingDetail.versionEdited') : null,
      summary.status !== 'DONE' ? t(`status.${summary.status}`) : null,
    ]
      .filter(Boolean)
      .join(' · ');

  return (
    <div>
      {deleteError && <Alert kind="error">{deleteError}</Alert>}
      {jobProgressLabel && <Spinner label={jobProgressLabel} />}

      {summaries.length > 1 && (
        <div className="summary-versions">
          <label htmlFor="summary-version">
            {t('recordingDetail.versionsLabel', { count: summaries.length })}
          </label>
          <select
            id="summary-version"
            value={shown.id}
            onChange={(e) => {
              setSelectedId(e.target.value);
              setEditId(null);
            }}
          >
            {summaries.map((summary) => (
              <option key={summary.id} value={summary.id}>
                {versionLabel(summary)}
              </option>
            ))}
          </select>
          <p className="muted summary-versions-hint">{t('recordingDetail.versionsHint')}</p>
        </div>
      )}

      <div className="summary-block">
        <div className="summary-block-head">
          <StatusBadge status={shown.status} />
          {shown.current && <span className="tag">{t('recordingDetail.versionCurrent')}</span>}
          <span className="muted">
            {shown.templateName ?? t('recordingDetail.versionNoTemplate')}
            {modelLabel(shown) ? ` · ${modelLabel(shown)}` : ''}
            {` · ${formatDateTime(shown.createdAt)}`}
            {shown.editedAt
              ? ` · ${t('recordingDetail.versionEditedAt', {
                  date: formatDateTime(shown.editedAt),
                })}`
              : ''}
          </span>
          {rec.mine && editId !== shown.id && (
            <div className="summary-actions">
              {shown.status === 'DONE' && shown.markdown && (
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => startEdit(shown.id, shown.markdown ?? '')}
                >
                  {t('common.edit')}
                </button>
              )}
              {!shown.current && shown.status === 'DONE' && shown.markdown && (
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => void handleSetCurrent(shown.id)}
                  title={t('recordingDetail.makeCurrentHint')}
                >
                  {t('recordingDetail.makeCurrent')}
                </button>
              )}
              <button
                type="button"
                className="btn btn-ghost btn-sm btn-danger-text"
                onClick={() => void handleDeleteSummary(shown.id)}
              >
                {t('common.delete')}
              </button>
            </div>
          )}
        </div>

        {shown.systemPrompt && (
          <details className="summary-prompt">
            <summary>{t('recordingDetail.versionPrompt')}</summary>
            <pre className="log-pre">{shown.systemPrompt}</pre>
          </details>
        )}

        {editId === shown.id ? (
          <div className="summary-edit">
            {editError && <Alert kind="error">{editError}</Alert>}
            <textarea value={editText} onChange={(e) => setEditText(e.target.value)} autoFocus />
            <p className="muted summary-edit-hint">{t('recordingDetail.summaryEditHint')}</p>
            <div className="summary-edit-actions">
              <button
                type="button"
                className="btn btn-primary"
                onClick={handleSaveEdit}
                disabled={editBusy || !editText.trim()}
              >
                {editBusy ? t('recordingDetail.saving') : t('common.save')}
              </button>
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setEditId(null)}
                disabled={editBusy}
              >
                {t('common.cancel')}
              </button>
            </div>
          </div>
        ) : shown.status === 'RUNNING' || shown.status === 'PENDING' ? (
          <Spinner label={t('recordingDetail.analysisRunning')} />
        ) : shown.status === 'FAILED' ? (
          <Alert kind="error">{shown.error ?? t('recordingDetail.summaryFailed')}</Alert>
        ) : shown.markdown ? (
          <Markdown>{shown.markdown}</Markdown>
        ) : (
          <p className="muted">{t('recordingDetail.noContent')}</p>
        )}
      </div>
    </div>
  );
}
