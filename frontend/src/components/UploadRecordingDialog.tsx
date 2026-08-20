import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import Modal from './Modal';
import Alert from './Alert';
import HelpTip from './HelpTip';
import PromptPresetSelect, {
  findOwnTemplate,
  presetLabel,
  resolvePresetPrompt,
} from './PromptPresetSelect';
import SttLanguageSelect from './SttLanguageSelect';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { fetchPromptTemplates } from '../store/promptTemplatesSlice';
import { errorMessage, fetchUploadConfig, uploadRecording } from '../api/client';
import { formatBytes } from '../utils/format';
import { useI18n } from '../i18n';

const ACCEPT =
  '.mp3,.wav,.m4a,.aac,.ogg,.opus,.flac,.wma,.amr,.webm,.mka,.mp4,.mkv,.mov,.avi,.3gp,.ts,audio/*,video/*';

interface UploadRecordingDialogProps {
  onClose: () => void;
  /** Wird nach erfolgreichem Upload aufgerufen (z.B. Liste neu laden). */
  onUploaded: () => void;
}

/**
 * Dialog zum Hochladen einer bestehenden Audio-/Videodatei als Aufnahme.
 * Die Datei wird serverseitig zu MP3 transkodiert und kann anschließend wie
 * eine Bot-Aufnahme ausgewertet werden (Whisper + Zusammenfassung).
 */
export default function UploadRecordingDialog({ onClose, onUploaded }: UploadRecordingDialogProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const {
    items: templates,
    loaded: templatesLoaded,
    loading: templatesLoading,
  } = useAppSelector((s) => s.promptTemplates);
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [aiAnalysis, setAiAnalysis] = useState(true);
  const [processNow, setProcessNow] = useState(false);
  const [preset, setPreset] = useState('');
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [maxFileSize, setMaxFileSize] = useState<number | null>(null);
  const [diarizeAllowed, setDiarizeAllowed] = useState(false);
  const [diarize, setDiarize] = useState(false);
  // '' = Sprachvorgabe des Administrators
  const [sttLanguage, setSttLanguage] = useState('');
  const [defaultSttLanguage, setDefaultSttLanguage] = useState('');

  useEffect(() => {
    fetchUploadConfig()
      .then((cfg) => {
        setMaxFileSize(cfg.maxFileSizeBytes);
        setDiarizeAllowed(cfg.diarizeAllowed);
        setDefaultSttLanguage(cfg.sttLanguage);
      })
      .catch(() => setMaxFileSize(null)); // ohne Limit-Info entscheidet der Server
  }, []);

  // Eigene Vorlagen nur laden, wenn sie noch nicht im Store sind
  useEffect(() => {
    if (!templatesLoaded && !templatesLoading) {
      dispatch(fetchPromptTemplates());
    }
  }, [dispatch, templatesLoaded, templatesLoading]);

  const uploading = progress !== null;
  const tooLarge = file !== null && maxFileSize !== null && file.size > maxFileSize;
  // Leer = Standardvorgabe des Administrators (Meeting)
  const summaryPrompt = resolvePresetPrompt(preset, templates);
  // Der Name benennt später die erzeugte Fassung in der Fassungsliste
  const summaryTemplateName = presetLabel(preset, templates);
  // Eine eigene Vorlage bringt ihr Modell und ihre Temperatur mit; sonst gilt
  // die Vorgabe des Administrators.
  const ownTemplate = findOwnTemplate(preset, templates);
  const summaryModel = ownTemplate?.model ?? null;
  const summaryTemperature = ownTemplate?.temperature ?? null;

  const handleUpload = async () => {
    if (!file || uploading || tooLarge) return;
    setError(null);
    setProgress(0);
    try {
      await uploadRecording(
        file,
        {
          title,
          aiAnalysis,
          processNow,
          diarize,
          summaryPrompt,
          summaryTemplateName,
          summaryModel,
          summaryTemperature,
          sttLanguage,
        },
        setProgress,
      );
      onUploaded();
    } catch (e) {
      setError(errorMessage(e));
      setProgress(null);
    }
  };

  return (
    <Modal
      title={t('upload.title')}
      onClose={uploading ? () => {} : onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" disabled={uploading} onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!file || uploading || tooLarge}
            onClick={handleUpload}
          >
            {uploading ? t('upload.uploading', { percent: progress ?? 0 }) : t('upload.submit')}
          </button>
        </>
      }
    >
      <p className="muted">{t('upload.intro')}</p>

      {error && <Alert kind="error">{error}</Alert>}
      {tooLarge && (
        <Alert kind="error">
          {t('upload.tooLarge', {
            size: formatBytes(file.size),
            max: formatBytes(maxFileSize),
          })}
        </Alert>
      )}

      <div className="form-field">
        <label htmlFor="upload-file">{t('upload.fileLabel')}</label>
        <input
          id="upload-file"
          type="file"
          accept={ACCEPT}
          disabled={uploading}
          onChange={(e) => {
            const selected = e.target.files?.[0] ?? null;
            setFile(selected);
            setError(null);
          }}
        />
      </div>

      <div className="form-field">
        <label htmlFor="upload-title">{t('upload.titleLabel')}</label>
        <input
          id="upload-title"
          type="text"
          placeholder={file?.name ?? t('upload.titlePlaceholder')}
          value={title}
          disabled={uploading}
          onChange={(e) => setTitle(e.target.value)}
        />
      </div>

      <label className="checkbox-field">
        <input
          type="checkbox"
          checked={aiAnalysis}
          disabled={uploading}
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
          disabled={uploading || !aiAnalysis}
          onChange={(e) => setProcessNow(e.target.checked)}
        />
        {t('analysis.processNow')}
      </label>

      {diarizeAllowed && (
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={diarize}
            disabled={uploading || !aiAnalysis}
            onChange={(e) => setDiarize(e.target.checked)}
          />
          {t('analysis.diarize')}
        </label>
      )}

      {/* Sprache der Spracherkennung: Ein falscher Sprach-Hinweis beschädigt das
          Transkript an der Wurzel – Glättung und Zusammenfassung retten das nicht. */}
      <div className="form-field">
        <label htmlFor="upload-stt-language">
          {t('sttLanguage.label')}
          <HelpTip text={t('sttLanguage.help')} />
        </label>
        <SttLanguageSelect
          id="upload-stt-language"
          value={sttLanguage}
          defaultLanguage={defaultSttLanguage}
          disabled={uploading || !aiAnalysis}
          onChange={setSttLanguage}
        />
      </div>

      {/* Vorlage schon hier wählbar: Eine sofortige Auswertung läuft sonst mit
          der Meeting-Vorgabe, bevor man sie nachträglich ändern könnte. */}
      <div className="form-field">
        <label htmlFor="upload-preset">
          {t('upload.presetLabel')}
          <HelpTip text={t('upload.presetHelp')} />
        </label>
        <PromptPresetSelect
          id="upload-preset"
          value={preset}
          templates={templates}
          disabled={uploading || !aiAnalysis}
          onChange={setPreset}
        />
        {aiAnalysis && preset !== '' && (
          <span className="muted upload-preset-hint">{t('upload.presetChosenHint')}</span>
        )}
        {/* Zum Anlegen und Ueberarbeiten der Vorlagen: eigener Tab, in einem
            neuen Browser-Tab, damit der Upload-Dialog erhalten bleibt. */}
        <span className="muted upload-preset-hint">
          <Link to="/templates" target="_blank" rel="noreferrer">
            {t('summaryOptions.manageTemplates')}
          </Link>{' '}
          {t('summaryOptions.manageTemplatesHint')}
        </span>
      </div>

      {uploading && (
        <div className="upload-progress">
          <div className="upload-progress-bar" style={{ width: `${progress}%` }} />
        </div>
      )}
    </Modal>
  );
}
