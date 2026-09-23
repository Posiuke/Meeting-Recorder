import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import Alert from '../components/Alert';
import Spinner from '../components/Spinner';
import { ApiError, errorMessage, stopLinkInfo, stopViaLink } from '../api/client';
import { isLanguage, LANGUAGES, setLanguage, useI18n } from '../i18n';

type Mode =
  | { kind: 'loading' }
  | { kind: 'ready'; roomName: string | null; confirming: boolean; busy: boolean; error: string | null }
  | { kind: 'done'; roomName: string | null }
  | { kind: 'invalid' }
  | { kind: 'error'; message: string };

/**
 * Anonymer Stopp-Link aus dem Aufnahme-Hinweis des Bots: Ein Teilnehmer kann
 * die Aufnahme verwerfen und den Bot aus dem Raum schicken, ohne sich im Chat
 * zu erkennen zu geben. Ohne Anmeldung, ohne Navigation - die Seite kann genau
 * das und nichts anderes.
 *
 * Das Öffnen allein beendet nichts (Link-Vorschauen rufen Adressen vorab auf);
 * erst der bestätigte Klick schickt die Anfrage.
 */
export default function StopRecordingPage() {
  const { t, language } = useI18n();
  const { token } = useParams<{ token: string }>();
  const [mode, setMode] = useState<Mode>({ kind: 'loading' });

  useEffect(() => {
    if (!token) {
      setMode({ kind: 'invalid' });
      return;
    }
    let active = true;
    stopLinkInfo(token)
      .then((info) => {
        if (active) {
          setMode({ kind: 'ready', roomName: info.roomName, confirming: false, busy: false, error: null });
        }
      })
      .catch((e) => {
        if (!active) return;
        setMode(e instanceof ApiError && e.status === 404
          ? { kind: 'invalid' }
          : { kind: 'error', message: errorMessage(e) });
      });
    return () => {
      active = false;
    };
  }, [token]);

  const stop = async () => {
    if (mode.kind !== 'ready' || !token) return;
    setMode({ ...mode, busy: true, error: null });
    try {
      const result = await stopViaLink(token);
      setMode({ kind: 'done', roomName: result.roomName ?? mode.roomName });
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setMode({ kind: 'invalid' });
      } else {
        setMode({ ...mode, busy: false, error: errorMessage(e) });
      }
    }
  };

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
        onChange={(e) => {
          if (isLanguage(e.target.value)) setLanguage(e.target.value);
        }}
      >
        {LANGUAGES.map((l) => (
          <option key={l.code} value={l.code}>
            {l.label}
          </option>
        ))}
      </select>
    </div>
  );

  const room = (roomName: string | null) =>
    roomName ? (
      <p className="stop-page-room">
        <span className="muted">{t('stopPage.room')}</span> <strong>{roomName}</strong>
      </p>
    ) : null;

  return (
    <div className="share-page stop-page">
      {head}
      <div className="card">
        {mode.kind === 'loading' && <Spinner label={t('stopPage.loading')} />}

        {mode.kind === 'ready' && (
          <>
            <h1>{t('stopPage.title')}</h1>
            {room(mode.roomName)}
            <p>{t('stopPage.intro')}</p>
            <p className="muted">{t('stopPage.anonymous')}</p>
            {mode.error && <Alert kind="error">{mode.error}</Alert>}
            {!mode.confirming ? (
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => setMode({ ...mode, confirming: true })}
              >
                {t('stopPage.submit')}
              </button>
            ) : (
              <div className="stop-page-confirm">
                <p>
                  <strong>{t('stopPage.confirm')}</strong>
                </p>
                <div className="stop-page-actions">
                  <button
                    type="button"
                    className="btn btn-danger"
                    disabled={mode.busy}
                    onClick={() => void stop()}
                  >
                    {mode.busy ? t('stopPage.stopping') : t('stopPage.confirmYes')}
                  </button>
                  <button
                    type="button"
                    className="btn"
                    disabled={mode.busy}
                    onClick={() => setMode({ ...mode, confirming: false })}
                  >
                    {t('common.cancel')}
                  </button>
                </div>
              </div>
            )}
          </>
        )}

        {mode.kind === 'done' && (
          <>
            <h1>{t('stopPage.doneTitle')}</h1>
            {room(mode.roomName)}
            <Alert kind="success">{t('stopPage.done')}</Alert>
            <p className="muted">{t('stopPage.doneHint')}</p>
          </>
        )}

        {mode.kind === 'invalid' && (
          <>
            <h1>{t('stopPage.invalidTitle')}</h1>
            <p className="muted">{t('stopPage.invalid')}</p>
          </>
        )}

        {mode.kind === 'error' && (
          <>
            <h1>{t('stopPage.title')}</h1>
            <Alert kind="error">{mode.message}</Alert>
          </>
        )}
      </div>
    </div>
  );
}
