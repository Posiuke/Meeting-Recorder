import { useEffect, useState } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { deleteApiKey, fetchApiKeys } from '../store/apiKeysSlice';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import CopyButton from '../components/CopyButton';
import CreateApiKeyDialog from '../components/CreateApiKeyDialog';
import { errorMessage } from '../api/client';
import { formatDateTime } from '../utils/format';
import { useI18n } from '../i18n';
import { getApiDocs } from './apiDocs';
import type { ApiEndpointDoc } from './apiDocs';
import type { ApiKeyView } from '../types';

/**
 * API-Bereich: eigene Schlüssel verwalten und nachlesen, wie die Schnittstelle
 * bedient wird. Beides auf einer Seite, weil das eine ohne das andere nichts
 * hilft – wer einen Schlüssel anlegt, will sofort den ersten Aufruf sehen.
 */
export default function ApiPage() {
  const { t, language } = useI18n();
  const dispatch = useAppDispatch();
  const { items, loading, error } = useAppSelector((s) => s.apiKeys);

  const [createOpen, setCreateOpen] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<ApiKeyView | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [openSections, setOpenSections] = useState<string[]>([]);

  const docs = getApiDocs(language);

  useEffect(() => {
    void dispatch(fetchApiKeys());
  }, [dispatch]);

  const handleDelete = async () => {
    if (!confirmDelete) return;
    setBusy(true);
    try {
      await dispatch(deleteApiKey(confirmDelete.id)).unwrap();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
      setConfirmDelete(null);
    }
  };

  const toggleSection = (id: string) =>
    setOpenSections((open) => (open.includes(id) ? open.filter((s) => s !== id) : [...open, id]));

  return (
    <div className="page">
      <div className="page-head">
        <h1>{t('api.heading')}</h1>
        <button type="button" className="btn btn-primary" onClick={() => setCreateOpen(true)}>
          {t('api.newKey')}
        </button>
      </div>

      <p className="muted">{docs.quickstart.intro}</p>

      {error && <Alert kind="error">{error}</Alert>}
      {actionError && <Alert kind="error">{actionError}</Alert>}

      <section>
        <h2>{t('api.keysHeading')}</h2>
        {loading && <Spinner label={t('api.loading')} />}
        {!loading && items.length === 0 && <p className="muted">{t('api.empty')}</p>}
        {!loading && items.length > 0 && (
          <div className="card table-card">
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t('common.name')}</th>
                    <th>{t('api.columnPrefix')}</th>
                    <th>{t('api.columnRights')}</th>
                    <th>{t('api.columnCreated')}</th>
                    <th>{t('api.columnExpires')}</th>
                    <th>{t('api.columnLastUsed')}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((key) => (
                    <tr key={key.id}>
                      <td>
                        <strong>{key.name}</strong>
                      </td>
                      <td>
                        <code>{key.prefix}…</code>
                      </td>
                      <td>
                        <span className={`tag${key.readOnly ? ' tag-muted' : ''}`}>
                          {key.readOnly ? t('api.rightsReadOnly') : t('api.rightsFull')}
                        </span>
                      </td>
                      <td>{formatDateTime(key.createdAt)}</td>
                      <td>
                        {key.expiresAt ? (
                          key.expired ? (
                            <span className="badge badge-red">{t('api.expired')}</span>
                          ) : (
                            formatDateTime(key.expiresAt)
                          )
                        ) : (
                          <span className="muted">{t('api.noExpiry')}</span>
                        )}
                      </td>
                      <td>
                        {key.lastUsedAt ? (
                          formatDateTime(key.lastUsedAt)
                        ) : (
                          <span className="muted">{t('api.neverUsed')}</span>
                        )}
                      </td>
                      <td className="row-actions">
                        <button
                          type="button"
                          className="btn btn-danger btn-sm"
                          onClick={() => setConfirmDelete(key)}
                        >
                          {t('api.revoke')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>

      <section>
        <h2>{t('api.quickstartHeading')}</h2>
        <div className="card">
          <div className="meta-row">
            <span className="meta-label">{docs.quickstart.baseUrlLabel}</span>
            <span className="meta-value url-wrap">
              <code>{window.location.origin}</code>
            </span>
          </div>
          <CodeBlock code={docs.quickstart.example} />
        </div>
      </section>

      <section>
        <h2>{docs.auth.title}</h2>
        <div className="card">
          <p>{docs.auth.text}</p>
          <ul className="api-notes">
            {docs.auth.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </div>
      </section>

      <section>
        <h2>{t('api.docsHeading')}</h2>
        {docs.sections.map((section) => {
          const open = openSections.includes(section.id);
          return (
            <div className="card api-section" key={section.id}>
              <button type="button" className="collapse-toggle" onClick={() => toggleSection(section.id)}>
                <span className={`chevron${open ? ' open' : ''}`}>▶</span>
                {section.title}
                <span className="tag tag-muted">{section.endpoints.length}</span>
              </button>
              {open && (
                <div className="api-section-body">
                  {section.intro && <p className="muted">{section.intro}</p>}
                  {section.endpoints.map((endpoint) => (
                    <Endpoint key={endpoint.method + endpoint.path} endpoint={endpoint} />
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </section>

      <section>
        <h2>{docs.errors.title}</h2>
        <div className="card">
          <p className="muted">{docs.errors.intro}</p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('api.columnCode')}</th>
                  <th>{t('api.columnMeaning')}</th>
                </tr>
              </thead>
              <tbody>
                {docs.errors.rows.map((row) => (
                  <tr key={row.code}>
                    <td>
                      <code>{row.code}</code>
                    </td>
                    <td>{row.meaning}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <CodeBlock code={docs.errors.example} />
        </div>
      </section>

      {createOpen && <CreateApiKeyDialog onClose={() => setCreateOpen(false)} />}

      {confirmDelete && (
        <ConfirmDialog
          title={t('api.confirmRevokeTitle')}
          message={t('api.confirmRevokeMessage', { name: confirmDelete.name })}
          confirmLabel={t('api.revoke')}
          danger
          busy={busy}
          onConfirm={handleDelete}
          onCancel={() => setConfirmDelete(null)}
        />
      )}
    </div>
  );
}

/** Ein Endpunkt mit Parametern, Beispielaufruf und Beispielantwort. */
function Endpoint({ endpoint }: { endpoint: ApiEndpointDoc }) {
  const { t } = useI18n();
  return (
    <div className="api-endpoint">
      <div className="api-endpoint-head">
        <span className={`api-method api-method-${endpoint.method.toLowerCase()}`}>
          {endpoint.method}
        </span>
        <code className="api-path url-wrap">{endpoint.path}</code>
      </div>
      <p className="api-endpoint-summary">{endpoint.summary}</p>
      {endpoint.params && endpoint.params.length > 0 && (
        <dl className="api-params">
          {endpoint.params.map((param) => (
            <div className="api-param" key={param.name}>
              <dt>
                <code>{param.name}</code>
              </dt>
              <dd>{param.description}</dd>
            </div>
          ))}
        </dl>
      )}
      {endpoint.example && <CodeBlock code={endpoint.example} label={t('api.exampleLabel')} />}
      {endpoint.response && <CodeBlock code={endpoint.response} label={t('api.responseLabel')} />}
    </div>
  );
}

/** Codeblock mit Beschriftung und Kopierknopf. */
function CodeBlock({ code, label }: { code: string; label?: string }) {
  return (
    <div className="code-block">
      <div className="code-block-head">
        <span className="code-block-label">{label}</span>
        <CopyButton value={code} />
      </div>
      <pre className="log-pre">{code}</pre>
    </div>
  );
}
