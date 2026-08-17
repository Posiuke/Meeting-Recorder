import { useCallback, useEffect, useRef, useState } from 'react';
import Modal from './Modal';
import Alert from './Alert';
import Spinner from './Spinner';
import HelpTip from './HelpTip';
import SttLanguageSelect from './SttLanguageSelect';
import { errorMessage } from '../api/client';
import { fetchCaptureConfig } from '../api/capture';
import type { CaptureConfig } from '../api/capture';
import {
  captureSupport,
  useScreenRecorder,
  type CaptureQuality,
} from '../hooks/useScreenRecorder';
import type { RecordingView } from '../types';
import { formatBytes, formatTimestamp } from '../utils/format';
import { useI18n } from '../i18n';

interface ScreenRecordDialogProps {
  onClose: () => void;
  /** Wird aufgerufen, sobald die Aufnahme beim Server abgeschlossen ist. */
  onFinished: (recording: RecordingView) => void;
}

/**
 * Nimmt Bildschirm und Ton direkt im Browser auf und legt daraus eine Aufnahme
 * an. Die Auswahl des Bildschirms übernimmt der Dialog des Browsers – eine
 * Webseite darf die vorhandenen Bildschirme nicht selbst auflisten.
 */
export default function ScreenRecordDialog({ onClose, onFinished }: ScreenRecordDialogProps) {
  const { t } = useI18n();
  const support = captureSupport();
  const recorder = useScreenRecorder();
  const notifiedRef = useRef(false);

  const [config, setConfig] = useState<CaptureConfig | null>(null);
  const [configError, setConfigError] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [wantVideo, setWantVideo] = useState(true);
  const [quality, setQuality] = useState<CaptureQuality>('standard');
  const [useMic, setUseMic] = useState(false);
  const [aiAnalysis, setAiAnalysis] = useState(true);
  const [processNow, setProcessNow] = useState(false);
  const [diarize, setDiarize] = useState(false);
  // '' = Sprachvorgabe des Administrators
  const [sttLanguage, setSttLanguage] = useState('');

  const { phase, previewStream, result } = recorder;
  const busy = phase === 'recording' || phase === 'paused' || phase === 'finishing';

  useEffect(() => {
    if (!support.supported) return;
    fetchCaptureConfig()
      .then(setConfig)
      .catch((e) => setConfigError(errorMessage(e)));
  }, [support.supported]);

  // Callback-Ref statt Effekt: Beim Wechsel von „bereit" zu „Aufnahme läuft"
  // wird das video-Element neu erzeugt und muss den Stream wieder bekommen.
  const attachPreview = useCallback(
    (node: HTMLVideoElement | null) => {
      if (node) node.srcObject = previewStream;
    },
    [previewStream],
  );

  // Genau einmal melden: Der Aufrufer lädt daraufhin die Liste neu, was ein
  // erneutes Rendern auslöst – ohne die Sperre liefe das im Kreis.
  useEffect(() => {
    if (result && !notifiedRef.current) {
      notifiedRef.current = true;
      onFinished(result);
    }
  }, [result, onFinished]);

  const handleMicToggle = async (checked: boolean) => {
    setUseMic(checked);
    if (checked) {
      const ok = await recorder.enableMic(recorder.micDeviceId || undefined);
      if (!ok) setUseMic(false);
    } else {
      recorder.disableMic();
    }
  };

  const handleClose = () => {
    if (busy) return;
    recorder.discardSource();
    onClose();
  };

  if (!support.supported) {
    return (
      <Modal title={t('capture.title')} onClose={onClose} footer={
        <button type="button" className="btn btn-ghost" onClick={onClose}>{t('common.close')}</button>
      }>
        <Alert kind="error">{t(`capture.notSupported.${support.reason ?? 'unsupported'}`)}</Alert>
      </Modal>
    );
  }

  if (config && !config.enabled) {
    return (
      <Modal title={t('capture.title')} onClose={onClose} footer={
        <button type="button" className="btn btn-ghost" onClick={onClose}>{t('common.close')}</button>
      }>
        <Alert kind="info">{t('capture.disabled')}</Alert>
      </Modal>
    );
  }

  return (
    <Modal
      title={t('capture.title')}
      wide
      onClose={handleClose}
      footer={renderFooter()}
    >
      {configError && <Alert kind="error">{configError}</Alert>}
      {recorder.error && <Alert kind="error">{recorder.error}</Alert>}
      {recorder.micError && <Alert kind="error">{recorder.micError}</Alert>}

      {phase === 'idle' && renderSetup()}
      {(phase === 'ready' || phase === 'starting') && renderReady()}
      {(phase === 'recording' || phase === 'paused') && renderRecording()}
      {phase === 'finishing' && <Spinner label={t('capture.finishing')} />}
      {phase === 'done' && <Alert kind="success">{t('capture.done')}</Alert>}
    </Modal>
  );

  function renderSetup() {
    return (
      <>
        <p className="muted">{t('capture.intro')}</p>

        <div className="form-field">
          <label htmlFor="capture-title">{t('capture.titleLabel')}</label>
          <input
            id="capture-title"
            type="text"
            placeholder={t('capture.titlePlaceholder')}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </div>

        <label className="checkbox-field">
          <input type="checkbox" checked={wantVideo} onChange={(e) => setWantVideo(e.target.checked)} />
          {t('capture.withVideo')}
        </label>

        {wantVideo && (
          <div className="form-field">
            <label htmlFor="capture-quality">{t('capture.qualityLabel')}</label>
            <select
              id="capture-quality"
              value={quality}
              onChange={(e) => setQuality(e.target.value as CaptureQuality)}
            >
              <option value="standard">{t('capture.qualityStandard')}</option>
              <option value="high">{t('capture.qualityHigh')}</option>
            </select>
          </div>
        )}

        {renderMicControls()}
        {renderAnalysisOptions()}
      </>
    );
  }

  /**
   * Mikrofon-Auswahl. Auch nach der Quellenauswahl noch bedienbar: Wer dort
   * merkt, dass kein Systemton ankommt, greift oft aufs Mikrofon zurück.
   */
  function renderMicControls() {
    return (
      <>
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={useMic}
            disabled={phase === 'starting'}
            onChange={(e) => void handleMicToggle(e.target.checked)}
          />
          {t('capture.useMic')}
        </label>

        {useMic && recorder.micDevices.length > 0 && (
          <div className="form-field">
            <label htmlFor="capture-mic">{t('capture.micLabel')}</label>
            <select
              id="capture-mic"
              value={recorder.micDeviceId}
              disabled={phase === 'starting'}
              onChange={(e) => void recorder.enableMic(e.target.value)}
            >
              {recorder.micDevices.map((device, index) => (
                <option key={device.deviceId} value={device.deviceId}>
                  {device.label || t('capture.micFallback', { index: index + 1 })}
                </option>
              ))}
            </select>
          </div>
        )}
      </>
    );
  }

  function renderAnalysisOptions() {
    return (
      <>
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={aiAnalysis}
            onChange={(e) => {
              setAiAnalysis(e.target.checked);
              if (!e.target.checked) {
                setProcessNow(false);
                setDiarize(false);
              }
            }}
          />
          {t('analysis.aiAnalysis')}
        </label>

        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={processNow}
            disabled={!aiAnalysis}
            onChange={(e) => setProcessNow(e.target.checked)}
          />
          {t('analysis.processNow')}
        </label>

        {config?.diarizeAllowed && (
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={diarize}
              disabled={!aiAnalysis}
              onChange={(e) => setDiarize(e.target.checked)}
            />
            {t('analysis.diarize')}
          </label>
        )}

        {/* Sprache der Aufnahme: Sie muss vor der Transkription feststehen –
            ein falscher Sprach-Hinweis beschädigt das Transkript von Anfang an. */}
        <div className="form-field">
          <label htmlFor="capture-stt-language">
            {t('sttLanguage.label')}
            <HelpTip text={t('sttLanguage.help')} />
          </label>
          <SttLanguageSelect
            id="capture-stt-language"
            value={sttLanguage}
            defaultLanguage={config?.sttLanguage}
            disabled={!aiAnalysis}
            onChange={setSttLanguage}
          />
        </div>
      </>
    );
  }

  function renderReady() {
    return (
      <>
        {recorder.withVideo && (
          <video className="capture-preview" ref={attachPreview} autoPlay muted playsInline />
        )}
        {!recorder.withVideo && (
          <Alert kind="info">{t('capture.audioOnlyNote')}</Alert>
        )}

        {recorder.systemAudio ? (
          <Alert kind="success">{t('capture.systemAudioOk')}</Alert>
        ) : (
          <Alert kind="error">
            <strong>{t('capture.systemAudioMissingTitle')}</strong>{' '}
            {t('capture.systemAudioMissing')}
            {recorder.micActive && t('capture.systemAudioMissingMic')}
          </Alert>
        )}

        {renderMicControls()}

        <p className="muted">{t('capture.readyHint')}</p>
      </>
    );
  }

  function renderRecording() {
    const limitReached =
      config != null && config.maxBytes > 0 && recorder.uploadedBytes >= config.maxBytes;
    return (
      <>
        <div className="capture-status">
          <span className={`capture-dot${phase === 'paused' ? ' paused' : ''}`} />
          <span className="capture-time">{formatTimestamp(recorder.elapsedMs / 1000)}</span>
          <span className="muted">
            {phase === 'paused' ? t('capture.statusPaused') : t('capture.statusRecording')} ·{' '}
            {t('capture.transferred', { size: formatBytes(recorder.uploadedBytes) })}
            {recorder.pendingChunks > 0 &&
              ` · ${t('capture.queued', { count: recorder.pendingChunks })}`}
          </span>
        </div>

        {recorder.withVideo && (
          <video className="capture-preview" ref={attachPreview} autoPlay muted playsInline />
        )}

        <div className="capture-levels">
          {renderLevel(t('capture.levelSystem'), recorder.levels.system, recorder.systemAudio)}
          {renderLevel(t('capture.levelMic'), recorder.levels.mic, recorder.micActive)}
        </div>

        {limitReached && (
          <Alert kind="error">
            {t('capture.limitReached', { max: formatBytes(config?.maxBytes) })}
          </Alert>
        )}

        <p className="muted">{t('capture.runningHint')}</p>
      </>
    );
  }

  function renderLevel(label: string, level: number, active: boolean) {
    return (
      <div className="capture-level">
        <span className="capture-level-label">{label}</span>
        <div className="capture-level-bar">
          <div
            className="capture-level-fill"
            style={{ width: active ? `${Math.round(level * 100)}%` : '0%' }}
          />
        </div>
        <span className="capture-level-state muted">{active ? '' : t('capture.levelOff')}</span>
      </div>
    );
  }

  function renderFooter() {
    if (phase === 'idle') {
      return (
        <>
          <button type="button" className="btn btn-ghost" onClick={handleClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => void recorder.chooseSource(wantVideo, quality)}
          >
            {t('capture.chooseSource')}
          </button>
        </>
      );
    }
    if (phase === 'ready' || phase === 'starting') {
      return (
        <>
          <button
            type="button"
            className="btn btn-ghost"
            disabled={phase === 'starting'}
            onClick={() => recorder.discardSource()}
          >
            {t('capture.otherSource')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={phase === 'starting'}
            onClick={() =>
              void recorder.beginRecording({
                title,
                aiAnalysis,
                processNow,
                diarize,
                sttLanguage,
              })
            }
          >
            {phase === 'starting' ? t('capture.starting') : t('capture.start')}
          </button>
        </>
      );
    }
    if (phase === 'recording' || phase === 'paused') {
      return (
        <>
          <button type="button" className="btn btn-danger" onClick={() => void recorder.cancel()}>
            {t('capture.discard')}
          </button>
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => (phase === 'paused' ? recorder.resume() : recorder.pause())}
          >
            {phase === 'paused' ? t('capture.resume') : t('capture.pause')}
          </button>
          <button type="button" className="btn btn-primary" onClick={() => void recorder.finish()}>
            {t('capture.stop')}
          </button>
        </>
      );
    }
    if (phase === 'finishing') {
      return (
        <button type="button" className="btn btn-ghost" disabled>
          {t('capture.finishingButton')}
        </button>
      );
    }
    return (
      <button type="button" className="btn btn-primary" onClick={handleClose}>
        {t('common.close')}
      </button>
    );
  }
}
