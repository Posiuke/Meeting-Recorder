import { useEffect, useState } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  createGlossaryEntry,
  deleteGlossaryEntry,
  fetchGlossary,
  updateGlossaryEntry,
} from '../store/glossarySlice';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import ImportGlossaryDialog from '../components/ImportGlossaryDialog';
import { errorMessage, glossaryExportUrl } from '../api/client';
import { useI18n } from '../i18n';
import type { GlossaryEntryView, GlossaryImportResult, GlossaryScope } from '../types';

/**
 * Glossar: Abkürzungen und Fachbegriffe aus den Besprechungen. Die Einträge gehen
 * in die KI-Glättung des Transkripts ein, damit haus- und fachinterne Begriffe
 * richtig geschrieben werden.
 *
 * Zwei Listen in einer Oberfläche: das eigene Glossar und das gemeinsame der
 * Installation. Das gemeinsame darf jeder lesen – Abteilungskürzel und
 * Projektnamen sind kein Geheimnis, und wer sieht, dass ein Begriff schon
 * gepflegt wird, legt ihn nicht noch einmal selbst an. Ändern dürfen es nur
 * Admins; für alle anderen ist der Reiter eine Nachschlageliste.
 */
export default function GlossaryPage() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const isAdmin = useAppSelector((s) => s.auth.user?.admin ?? false);
  const [scope, setScope] = useState<GlossaryScope>('personal');
  const { items, loading, error } = useAppSelector((s) => s.glossary[scope]);

  /** Das gemeinsame Glossar pflegen Admins; sonst ist der Reiter nur lesbar. */
  const canEdit = scope === 'personal' || isAdmin;
  const emptyText =
    scope === 'personal'
      ? t('glossary.empty')
      : canEdit
        ? t('glossary.emptyShared')
        : t('glossary.emptySharedReadOnly');

  const [term, setTerm] = useState('');
  const [meaning, setMeaning] = useState('');
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [editing, setEditing] = useState<GlossaryEntryView | null>(null);
  const [editTerm, setEditTerm] = useState('');
  const [editMeaning, setEditMeaning] = useState('');
  const [confirmDelete, setConfirmDelete] = useState<GlossaryEntryView | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [importResult, setImportResult] = useState<GlossaryImportResult | null>(null);

  useEffect(() => {
    void dispatch(fetchGlossary(scope));
  }, [dispatch, scope]);

  /** Beim Wechsel des Reiters nichts aus der anderen Liste stehen lassen. */
  const switchScope = (next: GlossaryScope) => {
    if (next === scope) return;
    setScope(next);
    setEditing(null);
    setConfirmDelete(null);
    setFormError(null);
    setImportResult(null);
    setTerm('');
    setMeaning('');
  };

  const handleCreate = async () => {
    if (!term.trim() || busy) return;
    setBusy(true);
    setFormError(null);
    try {
      await dispatch(
        createGlossaryEntry({ scope, term: term.trim(), meaning: meaning.trim() || null }),
      ).unwrap();
      setTerm('');
      setMeaning('');
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const startEdit = (entry: GlossaryEntryView) => {
    setEditing(entry);
    setEditTerm(entry.term);
    setEditMeaning(entry.meaning ?? '');
    setFormError(null);
  };

  const handleUpdate = async () => {
    if (!editing || !editTerm.trim() || busy) return;
    setBusy(true);
    setFormError(null);
    try {
      await dispatch(
        updateGlossaryEntry({
          scope,
          id: editing.id,
          term: editTerm.trim(),
          meaning: editMeaning.trim() || null,
        }),
      ).unwrap();
      setEditing(null);
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!confirmDelete) return;
    setBusy(true);
    try {
      await dispatch(deleteGlossaryEntry({ scope, id: confirmDelete.id })).unwrap();
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
      setConfirmDelete(null);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <h1>{t('glossary.heading')}</h1>
        <div className="row-actions">
          <a className="btn btn-ghost" href={glossaryExportUrl(scope)} download>
            {t('glossary.exportLabel')}
          </a>
          {canEdit && (
            <button type="button" className="btn btn-ghost" onClick={() => setImportOpen(true)}>
              {t('glossary.importLabel')}
            </button>
          )}
        </div>
      </div>

      <div className="tabs">
        <button
          type="button"
          className={`tab${scope === 'personal' ? ' active' : ''}`}
          onClick={() => switchScope('personal')}
        >
          {t('glossary.tabPersonal')}
        </button>
        <button
          type="button"
          className={`tab${scope === 'shared' ? ' active' : ''}`}
          onClick={() => switchScope('shared')}
        >
          {t('glossary.tabShared')}
        </button>
      </div>

      <p className="muted">
        {scope === 'shared' ? t('glossary.introShared') : t('glossary.intro')}
      </p>
      {scope === 'shared' && !isAdmin && <Alert kind="info">{t('glossary.sharedReadOnly')}</Alert>}

      {error && <Alert kind="error">{error}</Alert>}
      {formError && <Alert kind="error">{formError}</Alert>}
      {importResult && (
        <Alert kind={importResult.skipped > 0 ? 'info' : 'success'}>
          {t('glossary.importResult', {
            created: importResult.created,
            updated: importResult.updated,
            unchanged: importResult.unchanged,
            skipped: importResult.skipped,
          })}
          {importResult.warnings.length > 0 && (
            <>
              <div className="import-warnings-title">{t('glossary.importWarnings')}</div>
              <ul className="import-warnings">
                {importResult.warnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            </>
          )}
        </Alert>
      )}

      {canEdit && (
        <div className="card glossary-form">
          <div className="form-row">
            <div className="form-field grow">
              <label htmlFor="glossary-term">{t('glossary.termLabel')}</label>
              <input
                id="glossary-term"
                type="text"
                placeholder={t('glossary.termPlaceholder')}
                value={term}
                disabled={busy}
                onChange={(e) => setTerm(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    void handleCreate();
                  }
                }}
              />
            </div>
            <div className="form-field grow">
              <label htmlFor="glossary-meaning">{t('glossary.meaningLabel')}</label>
              <input
                id="glossary-meaning"
                type="text"
                placeholder={t('glossary.meaningPlaceholder')}
                value={meaning}
                disabled={busy}
                onChange={(e) => setMeaning(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    void handleCreate();
                  }
                }}
              />
            </div>
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy || term.trim().length === 0}
              onClick={() => void handleCreate()}
            >
              {t('common.add')}
            </button>
          </div>
        </div>
      )}

      {loading && <Spinner label={t('glossary.loading')} />}
      {!loading && items.length === 0 && <p className="muted">{emptyText}</p>}

      {!loading && items.length > 0 && (
        <div className="card table-card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('glossary.columnTerm')}</th>
                  <th>{t('glossary.columnMeaning')}</th>
                  {canEdit && <th></th>}
                </tr>
              </thead>
              <tbody>
                {items.map((entry) => (
                  <tr key={entry.id}>
                    <td>
                      {editing?.id === entry.id ? (
                        <input
                          type="text"
                          value={editTerm}
                          disabled={busy}
                          onChange={(e) => setEditTerm(e.target.value)}
                        />
                      ) : (
                        <strong>{entry.term}</strong>
                      )}
                    </td>
                    <td>
                      {editing?.id === entry.id ? (
                        <input
                          type="text"
                          value={editMeaning}
                          disabled={busy}
                          onChange={(e) => setEditMeaning(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                              e.preventDefault();
                              void handleUpdate();
                            }
                          }}
                        />
                      ) : (
                        entry.meaning ?? <span className="muted">–</span>
                      )}
                    </td>
                    {canEdit && (
                      <td className="row-actions">
                        {editing?.id === entry.id ? (
                          <>
                            <button
                              type="button"
                              className="btn btn-primary btn-sm"
                              disabled={busy || editTerm.trim().length === 0}
                              onClick={() => void handleUpdate()}
                            >
                              {t('common.save')}
                            </button>
                            <button
                              type="button"
                              className="btn btn-ghost btn-sm"
                              disabled={busy}
                              onClick={() => setEditing(null)}
                            >
                              {t('common.cancel')}
                            </button>
                          </>
                        ) : (
                          <>
                            <button
                              type="button"
                              className="btn btn-ghost btn-sm"
                              onClick={() => startEdit(entry)}
                            >
                              {t('common.edit')}
                            </button>
                            <button
                              type="button"
                              className="btn btn-danger btn-sm"
                              onClick={() => setConfirmDelete(entry)}
                            >
                              {t('common.delete')}
                            </button>
                          </>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {importOpen && (
        <ImportGlossaryDialog
          scope={scope}
          onClose={() => setImportOpen(false)}
          onImported={(result) => {
            setImportOpen(false);
            setImportResult(result);
            void dispatch(fetchGlossary(scope));
          }}
        />
      )}

      {confirmDelete && (
        <ConfirmDialog
          title={t('glossary.confirmDeleteTitle')}
          message={
            scope === 'shared'
              ? t('glossary.confirmDeleteMessageShared', { term: confirmDelete.term })
              : t('glossary.confirmDeleteMessage', { term: confirmDelete.term })
          }
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
