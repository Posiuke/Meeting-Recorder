import { useState } from 'react';
import Modal from './Modal';
import Alert from './Alert';
import { errorMessage, importGlossary } from '../api/client';
import { useI18n } from '../i18n';
import type { GlossaryImportResult, GlossaryScope } from '../types';

const ACCEPT = '.csv,.txt,text/csv,text/plain';

interface ImportGlossaryDialogProps {
  /** In welche Liste eingelesen wird: eigenes oder gemeinsames Glossar. */
  scope: GlossaryScope;
  onClose: () => void;
  /** Wird nach erfolgreichem Import mit dem Ergebnis aufgerufen. */
  onImported: (result: GlossaryImportResult) => void;
}

/**
 * Dialog zum Einlesen einer CSV-Datei ins Glossar. Der Import führt zusammen:
 * vorhandene Begriffe werden aktualisiert, neue angelegt, nicht genannte bleiben
 * stehen – gelöscht wird nie.
 */
export default function ImportGlossaryDialog({
  scope,
  onClose,
  onImported,
}: ImportGlossaryDialogProps) {
  const { t } = useI18n();
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleImport = async () => {
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    try {
      onImported(await importGlossary(file, scope));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={scope === 'shared' ? t('glossary.importTitleShared') : t('glossary.importTitle')}
      onClose={busy ? () => {} : onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!file || busy}
            onClick={() => void handleImport()}
          >
            {busy ? t('glossary.importing') : t('glossary.importSubmit')}
          </button>
        </>
      }
    >
      <p className="muted">{t('glossary.importIntro')}</p>

      {error && <Alert kind="error">{error}</Alert>}

      <pre className="log-pre">{t('glossary.importExample')}</pre>

      <div className="form-field">
        <label htmlFor="glossary-import-file">{t('glossary.importFileLabel')}</label>
        <input
          id="glossary-import-file"
          type="file"
          accept={ACCEPT}
          disabled={busy}
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null);
            setError(null);
          }}
        />
      </div>

      <p className="muted">{t('glossary.importTemplateHint')}</p>
    </Modal>
  );
}
