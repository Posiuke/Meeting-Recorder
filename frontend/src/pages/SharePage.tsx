import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import Alert from '../components/Alert';
import Markdown from '../components/Markdown';
import Spinner from '../components/Spinner';
import TranscriptList from '../components/TranscriptList';
import {
  errorMessage,
  publicAudioUrl,
  publicShare,
  publicSummaryDownloadUrl,
  publicVideoDownloadUrl,
  publicVideoUrl,
} from '../api/client';
import { formatBytes, formatDateTime, formatDuration } from '../utils/format';
import { LANGUAGES, useI18n } from '../i18n';
import type { TranslationKey } from '../i18n';
import type { PublicShareView, RecordingSource } from '../types';

/** Übersetzungsschlüssel für die Herkunft der Aufnahme. */
const SOURCE_KEYS: Record<RecordingSource, TranslationKey> = {
  BOT: 'recordingDetail.sourceBot',
  UPLOAD: 'recordingDetail.sourceUpload',
  CAPTURE: 'recordingDetail.sourceCapture',
};

type TabKey = 'summary' | 'transcript';

/**
 * Öffentliche Freigabe-Ansicht: erreichbar über einen Freigabe-Link, ohne
 * Anmeldung. Zeigt Video, Audio, Transkript und Zusammenfassung der Aufnahme –
 * bewusst ohne Navigation und ohne jede Bearbeitungsmöglichkeit.
 *
 * Die Seite liegt außerhalb von `RequireAuth`; ein (auch abgelaufenes) Login im
 * Browser spielt hier keine Rolle, weil sie nur den öffentlichen Endpunkt nutzt.
 */
export default function SharePage() {
  const { t, language, setLanguage } = useI18n();
  const { token } = useParams<{ token: string }>();
  const [share, setShare] = useState<PublicShareView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<TabKey>('summary');

  useEffect(() => {
    if (!token) return;
    let active = true;
    setLoading(true);
    publicShare(token)
      .then((data) => {
        if (!active) return;
        setShare(data);
        setError(null);
        // Ohne Zusammenfassung ist das Transkript das Interessante
        if (!data.summary) setTab('transcript');
      })
      .catch((e: unknown) => {
        if (active) setError(errorMessage(e));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [token]);

  if (loading) {
    return (
      <div className="share-page">
        <Spinner label={t('sharePage.loading')} />
      </div>
    );
  }

  if (error || !share || !token) {
    return (
      <div className="share-page">
        <div className="card">
          <h1>{t('sharePage.unavailableTitle')}</h1>
          <Alert kind="error">{error ?? t('sharePage.unavailable')}</Alert>
          <p className="muted">{t('sharePage.unavailableHint')}</p>
        </div>
      </div>
    );
  }

  const title =
    share.title ?? t('recordingDetail.fallbackTitle', { date: formatDateTime(share.startedAt) });

  return (
    <div className="share-page">
      <div className="share-page-head">
        <span className="app-brand">
          <span className="app-brand-dot" />
          {t('app.brand')}
        </span>
        <select
          className="language-select"
          value={language}
          aria-label={t('app.languageLabel')}
          onChange={(e) => setLanguage(e.target.value as typeof language)}
        >
          {LANGUAGES.map((l) => (
            <option key={l.code} value={l.code}>
              {l.label}
            </option>
          ))}
        </select>
      </div>

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
