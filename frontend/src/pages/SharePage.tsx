import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import Alert from '../components/Alert';
import Markdown from '../components/Markdown';
import Spinner from '../components/Spinner';
import TranscriptList from '../components/TranscriptList';
import { useAppSelector } from '../store/hooks';
import {
  ApiError,
  claimShareLink,
  errorMessage,
  publicAudioUrl,
  publicShare,
  publicSummaryDownloadUrl,
  publicVideoDownloadUrl,
  publicVideoUrl,
} from '../api/client';
import { formatBytes, formatDateTime, formatDuration } from '../utils/format';
import { FALLBACK_LANGUAGE, isLanguage, LANGUAGES, setLanguage as applyLanguage, useI18n } from '../i18n';
import type { TranslationKey } from '../i18n';
import type { PublicShareView, RecordingSource } from '../types';

/** Übersetzungsschlüssel für die Herkunft der Aufnahme. */
const SOURCE_KEYS: Record<RecordingSource, TranslationKey> = {
  BOT: 'recordingDetail.sourceBot',
  UPLOAD: 'recordingDetail.sourceUpload',
  CAPTURE: 'recordingDetail.sourceCapture',
};

type TabKey = 'summary' | 'transcript';

/** Was die Seite gerade zeigt. */
type Mode =
  | { kind: 'loading' }
  | { kind: 'view'; share: PublicShareView }
  /** Kontogebundener Link, Betrachter ist nicht angemeldet. */
  | { kind: 'login' }
  /** Kontogebundener Link wird eingelöst (Freigabe wird erteilt). */
  | { kind: 'claiming' }
  | { kind: 'error'; message: string };

/**
 * Öffentliche Freigabe-Ansicht: erreichbar über einen Freigabe-Link, ohne
 * Anmeldung. Zeigt Video, Audio, Transkript und Zusammenfassung der Aufnahme –
 * bewusst ohne Navigation und ohne jede Bearbeitungsmöglichkeit.
 *
 * Die Seite liegt außerhalb von `RequireAuth`; ein (auch abgelaufenes) Login im
 * Browser spielt hier keine Rolle, weil sie nur den öffentlichen Endpunkt nutzt.
 */
export default function SharePage() {
  const { t, language } = useI18n();
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const authStatus = useAppSelector((s) => s.auth.status);
  const [mode, setMode] = useState<Mode>({ kind: 'loading' });
  const [tab, setTab] = useState<TabKey>('summary');
  /** Hat der Betrachter die Sprache hier selbst umgestellt? Dann nicht überschreiben. */
  const languageChosen = useRef(false);

  useEffect(() => {
    // Solange ein gespeichertes Login noch geprüft wird, nicht vorschnell die
    // Anmelde-Aufforderung zeigen - der Nutzer ist womöglich angemeldet.
    if (!token || authStatus === 'idle' || authStatus === 'loading') return;
    let active = true;
    setMode({ kind: 'loading' });
    publicShare(token)
      .then((data) => {
        if (!active) return;
        setMode({ kind: 'view', share: data });
        // Ohne Zusammenfassung ist das Transkript das Interessante
        if (!data.summary) setTab('transcript');
        // In der Sprache des Freigebenden starten: Beschriftung und Inhalt passen
        // so eher zusammen als mit der Browsersprache des Empfängers. Bewusst
        // ohne zu speichern - die eigene Wahl des Betrachters bleibt unberührt.
        if (!languageChosen.current) {
          applyLanguage(isLanguage(data.language) ? data.language : FALLBACK_LANGUAGE, false);
        }
      })
      .catch((e: unknown) => {
        if (!active) return;
        // 403 = kontogebundener Link: über die Anmeldung einlösen
        if (e instanceof ApiError && e.status === 403) {
          if (authStatus !== 'authenticated') {
            setMode({ kind: 'login' });
            return;
          }
          setMode({ kind: 'claiming' });
          claimShareLink(token)
            .then((claim) => {
              if (active) navigate(`/recordings/${claim.recordingId}`, { replace: true });
            })
            .catch((claimError: unknown) => {
              if (active) setMode({ kind: 'error', message: errorMessage(claimError) });
            });
          return;
        }
        setMode({ kind: 'error', message: errorMessage(e) });
      });
    return () => {
      active = false;
    };
  }, [token, authStatus, navigate]);

  const chooseLanguage = (value: string) => {
    if (!isLanguage(value)) return;
    languageChosen.current = true;
    applyLanguage(value);
  };

  /** Kopfzeile mit Marke und Sprachwahl - die Seite hat kein Layout drumherum. */
  const head = (
    <div className="share-page-head">
      <span className="app-brand">
        <span className="app-brand-dot" />
        {t('app.brand')}
      </span>
      <select
        className="language-select"
        value={language}
        aria-label={t('app.languageLabel')}
        onChange={(e) => chooseLanguage(e.target.value)}
      >
        {LANGUAGES.map((l) => (
          <option key={l.code} value={l.code}>
            {l.label}
          </option>
        ))}
      </select>
    </div>
  );

  if (mode.kind === 'loading' || mode.kind === 'claiming' || authStatus === 'idle'
      || authStatus === 'loading') {
    return (
      <div className="share-page">
        {head}
        <Spinner
          label={mode.kind === 'claiming' ? t('sharePage.claiming') : t('sharePage.loading')}
        />
      </div>
    );
  }

  // Kontogebundener Link: erst anmelden, danach wird die Aufnahme automatisch
  // freigegeben (die Anmeldeseite kehrt hierher zurück).
  if (mode.kind === 'login') {
    return (
      <div className="share-page">
        {head}
        <div className="card">
          <h1>{t('sharePage.loginRequiredTitle')}</h1>
          <p className="muted">{t('sharePage.loginRequired')}</p>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => navigate('/login', { state: { from: location } })}
          >
            {t('login.submit')}
          </button>
        </div>
      </div>
    );
  }

  if (mode.kind === 'error' || !token) {
    return (
      <div className="share-page">
        {head}
        <div className="card">
          <h1>{t('sharePage.unavailableTitle')}</h1>
          <Alert kind="error">
            {mode.kind === 'error' ? mode.message : t('sharePage.unavailable')}
          </Alert>
          <p className="muted">{t('sharePage.unavailableHint')}</p>
        </div>
      </div>
    );
  }

  const share = mode.share;
  const title =
    share.title ?? t('recordingDetail.fallbackTitle', { date: formatDateTime(share.startedAt) });

  return (
    <div className="share-page">
      {head}

      <div className="card">
        <h1>{title}</h1>
        <p className="muted">{t('sharePage.intro')}</p>
        <div className="detail-meta">
          <div className="meta-row">
            <span className="meta-label">{t('recordingDetail.period')}</span>
            <span className="meta-value">
              {formatDateTime(share.startedAt)}
              {share.endedAt ? ` – ${formatDateTime(share.endedAt)}` : ''}
            </span>
          </div>
          <div className="meta-row">
            <span className="meta-label">{t('common.duration')}</span>
            <span className="meta-value">{formatDuration(share.durationMs)}</span>
          </div>
          <div className="meta-row">
            <span className="meta-label">{t('recordingDetail.source')}</span>
            <span className="meta-value">{t(SOURCE_KEYS[share.source])}</span>
          </div>
          {share.sharedBy && (
            <div className="meta-row">
              <span className="meta-label">{t('sharePage.sharedBy')}</span>
              <span className="meta-value">{share.sharedBy}</span>
            </div>
          )}
          {share.expiresAt && (
            <div className="meta-row">
              <span className="meta-label">{t('sharePage.validUntil')}</span>
              <span className="meta-value">{formatDateTime(share.expiresAt)}</span>
            </div>
          )}
        </div>
      </div>

      {share.hasVideo && (
        <section className="card">
          <h2>{t('recordingDetail.videoHeading')}</h2>
          <div className="recording-video">
            <video controls preload="metadata" src={publicVideoUrl(token)} className="video-player" />
            <div>
              <a className="btn btn-ghost btn-sm" href={publicVideoDownloadUrl(token)} download>
                {t('recordingDetail.videoDownload')}
              </a>
            </div>
          </div>
        </section>
      )}

      {share.segments.length > 0 && (
        <section className="card">
          <h2>{t('sharePage.audioHeading')}</h2>
          <ul className="segment-list">
            {share.segments.map((seg, idx) => (
              <li key={seg.id} className="segment-item">
                <div className="segment-info">
                  <span className="segment-seq">
                    {t('recordingDetail.segment', { index: idx + 1 })}
                  </span>
                  <span className="muted">
                    {formatDuration(seg.durationMs)} · {formatBytes(seg.sizeBytes)}
                  </span>
                </div>
                <div className="segment-audio">
                  <audio controls preload="none" src={publicAudioUrl(token, seg.id)} />
                  <a className="btn btn-ghost btn-sm" href={publicAudioUrl(token, seg.id)} download>
                    {t('recordingDetail.download')}
                  </a>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="tabs">
        <button
          type="button"
          className={`tab${tab === 'summary' ? ' active' : ''}`}
          onClick={() => setTab('summary')}
        >
          {t('recordingDetail.tabSummary')}
        </button>
        <button
          type="button"
          className={`tab${tab === 'transcript' ? ' active' : ''}`}
          onClick={() => setTab('transcript')}
        >
          {t('recordingDetail.tabTranscript')}
        </button>
      </div>

      <div className="card tab-panel">
        {tab === 'summary' &&
          (share.summary ? (
            <>
              <div className="summary-block-head">
                <span className="muted">
                  {share.summaryCreatedAt ? formatDateTime(share.summaryCreatedAt) : ''}
                </span>
                <a className="btn btn-ghost btn-sm" href={publicSummaryDownloadUrl(token)}>
                  {t('recordingDetail.downloadSummary')}
                </a>
              </div>
              <Markdown>{share.summary}</Markdown>
            </>
          ) : (
            <p className="muted">{t('recordingDetail.noSummary')}</p>
          ))}

        {tab === 'transcript' &&
          (share.entries.length > 0 ? (
            <TranscriptList entries={share.entries} participants={share.participants} />
          ) : share.transcript ? (
            <Markdown>{share.transcript}</Markdown>
          ) : (
            <p className="muted">{t('recordingDetail.transcriptEmpty')}</p>
          ))}
      </div>
    </div>
  );
}
