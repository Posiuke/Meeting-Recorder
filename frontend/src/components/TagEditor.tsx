import { useEffect, useState } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  addRecordingTag,
  fetchTagCounts,
  removeRecordingTag,
} from '../store/recordingsSlice';
import { errorMessage } from '../api/client';
import Alert from './Alert';
import { useI18n } from '../i18n';

interface TagEditorProps {
  recordingId: string;
  tags: string[];
  /** Nur der Besitzer darf Schlagworte ändern; alle anderen sehen sie nur. */
  editable: boolean;
}

/**
 * Schlagworte einer Aufnahme anzeigen und (als Besitzer) pflegen. Die bereits
 * vergebenen Schlagworte aller sichtbaren Aufnahmen dienen als Vorschlagsliste,
 * damit nicht für dieselbe Sache drei Schreibweisen entstehen.
 */
export default function TagEditor({ recordingId, tags, editable }: TagEditorProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const known = useAppSelector((s) => s.recordings.tags);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (editable && known.length === 0) void dispatch(fetchTagCounts());
  }, [dispatch, editable, known.length]);

  const submit = async () => {
    const name = input.trim();
    if (!name || busy) return;
    setBusy(true);
    setError(null);
    try {
      await dispatch(addRecordingTag({ recordingId, name })).unwrap();
      setInput('');
      // Vorschlagsliste und Häufigkeiten nachziehen
      void dispatch(fetchTagCounts());
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (name: string) => {
    setBusy(true);
    setError(null);
    try {
      await dispatch(removeRecordingTag({ recordingId, name })).unwrap();
      void dispatch(fetchTagCounts());
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  if (!editable && tags.length === 0) return null;

  return (
    <div className="tag-editor">
      {tags.length === 0 && <span className="muted">{t('tags.none')}</span>}
      {tags.map((tag) => (
        <span key={tag} className="tag-pill tag-pill-static">
          {tag}
          {editable && (
            <button
              type="button"
              className="tag-remove"
              aria-label={t('tags.removeLabel', { tag })}
              title={t('tags.remove')}
              disabled={busy}
              onClick={() => void remove(tag)}
            >
              ×
            </button>
          )}
        </span>
      ))}

      {editable && (
        <>
          <input
            type="text"
            className="tag-input"
            list="known-tags"
            placeholder={t('tags.addPlaceholder')}
            value={input}
            disabled={busy}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void submit();
              }
            }}
          />
          <datalist id="known-tags">
            {known.map((tag) => (
              <option key={tag.name} value={tag.name} />
            ))}
          </datalist>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            disabled={busy || input.trim().length === 0}
            onClick={() => void submit()}
          >
            {t('common.add')}
          </button>
        </>
      )}

      {error && <Alert kind="error">{error}</Alert>}
    </div>
  );
}
