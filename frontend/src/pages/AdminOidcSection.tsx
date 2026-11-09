import { useCallback, useEffect, useState } from 'react';
import Alert from '../components/Alert';
import Spinner from '../components/Spinner';
import { errorMessage } from '../api/client';
import {
  createOidcProvider,
  deleteOidcProvider,
  listOidcProviders,
  testOidcProvider,
  updateOidcProvider,
} from '../api/oidc';
import type { OidcProviderRequest, OidcProviderView } from '../api/oidc';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { saveAuthConfig } from '../store/adminSlice';
import { useI18n } from '../i18n';

type Preset = 'microsoft' | 'google' | 'keycloak' | 'generic';

/** Startwerte je Anbieterart – der Admin ersetzt nur noch die Platzhalter. */
const PRESETS: Record<Preset, OidcProviderRequest> = {
  microsoft: {
    slug: 'microsoft',
    displayName: 'Microsoft',
    enabled: true,
    issuerUri: 'https://login.microsoftonline.com/<TENANT-ID>/v2.0',
    clientId: '',
    clientSecret: '',
    scopes: 'openid profile email',
    usernameClaim: 'preferred_username',
    groupsClaim: 'roles',
    adminValues: '',
    allowedValues: '',
  },
  google: {
    slug: 'google',
    displayName: 'Google',
    enabled: true,
    issuerUri: 'https://accounts.google.com',
    clientId: '',
    clientSecret: '',
    scopes: 'openid profile email',
    usernameClaim: 'email',
    groupsClaim: '',
    adminValues: '',
    allowedValues: '',
  },
  keycloak: {
    slug: 'keycloak',
    displayName: 'Keycloak',
    enabled: true,
    issuerUri: 'https://keycloak.example.com/realms/<REALM>',
    clientId: '',
    clientSecret: '',
    scopes: 'openid profile email',
    usernameClaim: 'preferred_username',
    groupsClaim: 'groups',
    adminValues: '',
    allowedValues: '',
  },
  generic: {
    slug: '',
    displayName: '',
    enabled: true,
    issuerUri: 'https://',
    clientId: '',
    clientSecret: '',
    scopes: 'openid profile email',
    usernameClaim: 'preferred_username',
    groupsClaim: '',
    adminValues: '',
    allowedValues: '',
  },
};

function toRequest(p: OidcProviderView): OidcProviderRequest {
  return {
    displayName: p.displayName,
    enabled: p.enabled,
    issuerUri: p.issuerUri,
    clientId: p.clientId,
    clientSecret: '',
    scopes: p.scopes,
    usernameClaim: p.usernameClaim,
    groupsClaim: p.groupsClaim ?? '',
    adminValues: p.adminValues ?? '',
    allowedValues: p.allowedValues ?? '',
  };
}

/**
 * Anmeldung über OpenID Connect: Anbieter anlegen, bearbeiten, testen. Jeder
 * Anbieter erscheint als eigener Knopf auf der Anmeldeseite.
 */
export default function AdminOidcSection() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const authConfig = useAppSelector((s) => s.admin.authConfig);
  const [providers, setProviders] = useState<OidcProviderView[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  /** id des bearbeiteten Anbieters, 'new' für einen neuen, null = keine Bearbeitung */
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<OidcProviderRequest>(PRESETS.microsoft);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  const load = useCallback(() => {
    listOidcProviders()
      .then((list) => {
        setProviders(list);
        setLoadError(null);
      })
      .catch((e) => setLoadError(errorMessage(e)));
  }, []);

  useEffect(load, [load]);

  const localHidden = (authConfig?.['auth.localLoginHidden'] ?? 'false') === 'true';

  const set = <K extends keyof OidcProviderRequest>(key: K, value: OidcProviderRequest[K]) =>
    setForm((f) => ({ ...f, [key]: value }));

  const startNew = (preset: Preset) => {
    setForm({ ...PRESETS[preset] });
    setEditing('new');
    setMessage(null);
  };

  const startEdit = (p: OidcProviderView) => {
    setForm(toRequest(p));
    setEditing(p.id);
    setMessage(null);
  };

  const handleSave = async () => {
    setBusy(true);
    setMessage(null);
    try {
      if (editing === 'new') {
        await createOidcProvider(form);
      } else if (editing) {
        await updateOidcProvider(editing, form);
      }
      setEditing(null);
      setMessage({ kind: 'success', text: t('admin.oidc.saved') });
      load();
    } catch (e) {
      setMessage({ kind: 'error', text: errorMessage(e) });
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async (p: OidcProviderView) => {
    if (!window.confirm(t('admin.oidc.confirmDelete', { name: p.displayName }))) return;
    try {
      await deleteOidcProvider(p.id);
      if (editing === p.id) setEditing(null);
      load();
    } catch (e) {
      setMessage({ kind: 'error', text: errorMessage(e) });
    }
  };

  const handleTest = async (p: OidcProviderView) => {
    setMessage(null);
    try {
      const result = await testOidcProvider(p.id);
      setMessage({ kind: result.ok ? 'success' : 'error', text: result.message });
    } catch (e) {
      setMessage({ kind: 'error', text: errorMessage(e) });
    }
  };

  const handleLocalHidden = async (hidden: boolean) => {
    try {
      await dispatch(saveAuthConfig({ 'auth.localLoginHidden': hidden ? 'true' : 'false' })).unwrap();
    } catch (e) {
      setMessage({ kind: 'error', text: errorMessage(e) });
    }
  };

  const editedProvider = providers?.find((p) => p.id === editing);

  return (
    <section className="card settings-group">
      <h2>{t('admin.oidc.heading')}</h2>
      <p className="settings-note">{t('admin.oidc.note')}</p>
      {loadError && <Alert kind="error">{loadError}</Alert>}
      {message && <Alert kind={message.kind}>{message.text}</Alert>}
      {!providers && !loadError && <Spinner />}

      {providers && providers.length > 0 && (
        <ul className="oidc-provider-list">
          {providers.map((p) => (
            <li key={p.id} className="oidc-provider">
              <div className="oidc-provider-main">
                <strong>{p.displayName}</strong>
                <span className="oidc-provider-meta">
                  {p.slug} · {p.enabled ? t('admin.oidc.enabled') : t('admin.oidc.disabled')}
                </span>
                <span className="oidc-provider-redirect">
                  {t('admin.oidc.redirectUri')}: <code>{p.redirectUri}</code>
                </span>
              </div>
              <div className="oidc-provider-actions">
                <button type="button" className="btn btn-sm" onClick={() => handleTest(p)}>
                  {t('admin.oidc.test')}
                </button>
                <button type="button" className="btn btn-sm" onClick={() => startEdit(p)}>
                  {t('common.edit')}
                </button>
                <button type="button" className="btn btn-sm btn-danger" onClick={() => handleDelete(p)}>
                  {t('common.delete')}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {providers && providers.length > 0 && (
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={localHidden}
            onChange={(e) => handleLocalHidden(e.target.checked)}
          />
          {t('admin.oidc.hideLocal')}
        </label>
      )}

      {editing === null && (
        <div className="settings-group-footer oidc-presets">
          <span>{t('admin.oidc.add')}</span>
          <button type="button" className="btn" onClick={() => startNew('microsoft')}>
            Microsoft Entra ID
          </button>
          <button type="button" className="btn" onClick={() => startNew('google')}>
            Google
          </button>
          <button type="button" className="btn" onClick={() => startNew('keycloak')}>
            Keycloak
          </button>
          <button type="button" className="btn" onClick={() => startNew('generic')}>
            {t('admin.oidc.generic')}
          </button>
        </div>
      )}

      {editing !== null && (
        <div className="oidc-form">
          <h3>{editing === 'new' ? t('admin.oidc.newHeading') : editedProvider?.displayName}</h3>
          <div className="settings-fields">
            {editing === 'new' && (
              <div className="form-field">
                <label htmlFor="oidc-slug">{t('admin.oidc.slug')}</label>
                <input
                  id="oidc-slug"
                  type="text"
                  value={form.slug ?? ''}
                  onChange={(e) => set('slug', e.target.value.toLowerCase())}
                />
                <span className="field-default">{t('admin.oidc.slugHint')}</span>
              </div>
            )}
            <div className="form-field">
              <label htmlFor="oidc-name">{t('admin.oidc.displayName')}</label>
              <input
                id="oidc-name"
                type="text"
                value={form.displayName}
                onChange={(e) => set('displayName', e.target.value)}
              />
              <span className="field-default">{t('admin.oidc.displayNameHint')}</span>
            </div>
            <div className="form-field field-full">
              <label htmlFor="oidc-issuer">{t('admin.oidc.issuer')}</label>
              <input
                id="oidc-issuer"
                type="text"
                value={form.issuerUri}
                onChange={(e) => set('issuerUri', e.target.value)}
              />
              <span className="field-default">{t('admin.oidc.issuerHint')}</span>
            </div>
            <div className="form-field">
              <label htmlFor="oidc-client">{t('admin.oidc.clientId')}</label>
              <input
                id="oidc-client"
                type="text"
                value={form.clientId}
                onChange={(e) => set('clientId', e.target.value)}
              />
            </div>
            <div className="form-field">
              <label htmlFor="oidc-secret">{t('admin.oidc.clientSecret')}</label>
              <input
                id="oidc-secret"
                type="password"
                autoComplete="new-password"
                value={form.clientSecret}
                placeholder={editedProvider?.hasClientSecret ? t('admin.oidc.secretKept') : ''}
                onChange={(e) => set('clientSecret', e.target.value)}
              />
            </div>
            <div className="form-field">
              <label htmlFor="oidc-username">{t('admin.oidc.usernameClaim')}</label>
              <input
                id="oidc-username"
                type="text"
                value={form.usernameClaim}
                onChange={(e) => set('usernameClaim', e.target.value)}
              />
              <span className="field-default">{t('admin.oidc.usernameClaimHint')}</span>
            </div>
            <div className="form-field">
              <label htmlFor="oidc-scopes">{t('admin.oidc.scopes')}</label>
              <input
                id="oidc-scopes"
                type="text"
                value={form.scopes}
                onChange={(e) => set('scopes', e.target.value)}
              />
            </div>
            <div className="form-field">
              <label htmlFor="oidc-groups">{t('admin.oidc.groupsClaim')}</label>
              <input
                id="oidc-groups"
                type="text"
                value={form.groupsClaim}
                onChange={(e) => set('groupsClaim', e.target.value)}
              />
              <span className="field-default">{t('admin.oidc.groupsClaimHint')}</span>
            </div>
            <div className="form-field">
              <label htmlFor="oidc-admins">{t('admin.oidc.adminValues')}</label>
              <input
                id="oidc-admins"
                type="text"
                value={form.adminValues}
                onChange={(e) => set('adminValues', e.target.value)}
              />
              <span className="field-default">{t('admin.oidc.adminValuesHint')}</span>
            </div>
            <div className="form-field field-full">
              <label htmlFor="oidc-allowed">{t('admin.oidc.allowedValues')}</label>
              <input
                id="oidc-allowed"
                type="text"
                value={form.allowedValues}
                onChange={(e) => set('allowedValues', e.target.value)}
              />
              <span className="field-default">{t('admin.oidc.allowedValuesHint')}</span>
            </div>
          </div>
          <label className="checkbox-field">
            <input type="checkbox" checked={form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
            {t('admin.oidc.enabledLabel')}
          </label>
          {editing === 'new' && <p className="settings-note">{t('admin.oidc.redirectAfterSave')}</p>}
          <div className="settings-group-footer">
            <button type="button" className="btn" disabled={busy} onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </button>
            <button type="button" className="btn btn-primary" disabled={busy} onClick={handleSave}>
              {busy ? t('common.saving') : t('common.save')}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
