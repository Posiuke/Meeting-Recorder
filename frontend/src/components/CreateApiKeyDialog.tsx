import { useState } from 'react';
import Modal from './Modal';
import Alert from './Alert';
import CopyButton from './CopyButton';
import { useAppDispatch } from '../store/hooks';
import { createApiKey } from '../store/apiKeysSlice';
import { errorMessage } from '../api/client';
import { useI18n } from '../i18n';
import type { ApiKeyCreated } from '../types';

interface CreateApiKeyDialogProps {
  onClose: () => void;
}

/**
 * Zwei Schritte in einem Dialog: Schlüssel beschreiben, dann den erzeugten
 * Schlüssel einmalig anzeigen. Gespeichert ist serverseitig nur sein Abdruck –
 * wer ihn hier nicht kopiert, muss einen neuen anlegen.
 */
export default function CreateApiKeyDialog({ onClose }: CreateApiKeyDialogProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const [name, setName] = useState('');
  const [readOnly, setReadOnly] = useState(true);
  const [expiryDate, setExpiryDate] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<ApiKeyCreated | null>(null);

  const handleCreate = async () => {
    if (!name.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await dispatch(
        createApiKey({
          name: name.trim(),
          readOnly,
          // Ein gewähltes Datum gilt bis zu seinem Ende (UTC)
          expiresAt: expiryDate ? `${expiryDate}T23:59:59Z` : null,
        }),
      ).unwrap();
      setCreated(result);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  if (created) {
    return (
      <Modal
        title={t('api.createdTitle')}
        onClose={onClose}
        footer={
          <button type="button" className="btn btn-primary" onClick={onClose}>
            {t('api.createdDone')}
          </button>
        }
      >
        <Alert kind="info">{t('api.createdHint')}</Alert>
        <div className="token-reveal">
          <code>{created.token}</code>
          <CopyButton value={created.token} className="btn btn-primary btn-sm" />
        </div>
        <p className="muted">{t('api.createdUsageHint')}</p>
        <pre className="log-pre">{`curl -H "X-API-Key: ${created.token}" \\\n  ${window.location.origin}/api/recordings`}</pre>
      </Modal>
    );
  }

  return (
    <Modal
      title={t('api.createTitle')}
      onClose={busy ? () => {} : onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy || name.trim().length === 0}
            onClick={() => void handleCreate()}
          >
            {busy ? t('api.creating') : t('api.createSubmit')}
          </button>
        </>
      }
    >
      {error && <Alert kind="error">{error}</Alert>}

      <div className="form-field">
        <label htmlFor="api-key-name">{t('api.createNameLabel')}</label>
        <input
          id="api-key-name"
          type="text"
          placeholder={t('api.createNamePlaceholder')}
          value={name}
          disabled={busy}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              void handleCreate();
            }
          }}
        />
      </div>

      <div className="form-field">
        <label>{t('api.createRightsLabel')}</label>
        <label className="checkbox-field">
          <input
            type="radio"
            name="api-key-rights"
            checked={readOnly}
            disabled={busy}
            onChange={() => setReadOnly(true)}
          />
          {t('api.rightsReadOnly')}
        </label>
        <span className="field-default">{t('api.createReadOnlyHint')}</span>
        <label className="checkbox-field">
          <input
            type="radio"
            name="api-key-rights"
            checked={!readOnly}
            disabled={busy}
            onChange={() => setReadOnly(false)}
          />
          {t('api.rightsFull')}
        </label>
        <span className="field-default">{t('api.createFullHint')}</span>
      </div>

      <div className="form-field">
        <label htmlFor="api-key-expiry">{t('api.createExpiryLabel')}</label>
        <input
          id="api-key-expiry"
          type="date"
          value={expiryDate}
          disabled={busy}
          onChange={(e) => setExpiryDate(e.target.value)}
        />
        <span className="field-default">{t('api.createExpiryHint')}</span>
      </div>
    </Modal>
  );
}
