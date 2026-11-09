import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { login } from '../store/authSlice';
import Alert from '../components/Alert';
import { LANGUAGES, useI18n } from '../i18n';
import type { Language } from '../i18n';
import { fetchLoginOptions, oidcStartUrl } from '../api/oidc';
import type { LoginOptions } from '../api/oidc';

/** Bekannte Fehlerkennungen der OIDC-Anmeldung (siehe OidcException im Backend). */
const SSO_ERRORS = [
  'access_denied',
  'not_allowed',
  'username_taken',
  'no_username',
  'state_expired',
  'state_mismatch',
  'unknown_provider',
  'discovery_failed',
  'token_failed',
  'invalid_token',
  'session',
] as const;
type SsoError = (typeof SSO_ERRORS)[number];

export default function LoginPage() {
  const dispatch = useAppDispatch();
  const { t, language, setLanguage } = useI18n();
  const location = useLocation();
  const status = useAppSelector((s) => s.auth.status);
  const error = useAppSelector((s) => s.auth.error);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [searchParams] = useSearchParams();
  const [options, setOptions] = useState<LoginOptions | null>(null);
  const [showLocal, setShowLocal] = useState(false);

  // Anbieter-Knöpfe laden; scheitert das, bleibt die Passwortanmeldung.
  useEffect(() => {
    fetchLoginOptions()
      .then(setOptions)
      .catch(() => setOptions({ providers: [], localLoginHidden: false }));
  }, []);

  const from = (location.state as { from?: { pathname: string; search?: string } } | null)?.from;
  if (status === 'authenticated') {
    return <Navigate to={from?.pathname ?? '/'} replace />;
  }

  const ssoErrorCode = searchParams.get('sso_error');
  const ssoError = ssoErrorCode
    ? (SSO_ERRORS as readonly string[]).includes(ssoErrorCode)
      ? t(`login.ssoError.${ssoErrorCode as SsoError}`)
      : t('login.ssoError.provider_error')
    : null;
  const providers = options?.providers ?? [];
  const localVisible = !options?.localLoginHidden || showLocal;
  const next = from ? from.pathname + (from.search ?? '') : '/';

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password) return;
    setBusy(true);
    try {
      await dispatch(login({ username: username.trim(), password })).unwrap();
    } catch {
      // Fehlermeldung steht im auth-Slice
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <span className="app-brand-dot" />
          {t('app.brand')}
        </div>
        {/* Sprachwahl schon vor der Anmeldung - sonst begrüßt die Anwendung
            jeden zunächst in einer Sprache, die er vielleicht nicht liest. */}
        <div className="login-language">
          <label htmlFor="login-language">{t('app.languageLabel')}</label>
          <select
            id="login-language"
            value={language}
            onChange={(e) => setLanguage(e.target.value as Language)}
          >
            {LANGUAGES.map((entry) => (
              <option key={entry.code} value={entry.code}>
                {entry.label}
              </option>
            ))}
          </select>
        </div>
        {ssoError && <Alert kind="error">{ssoError}</Alert>}
        {providers.length > 0 && (
          <div className="login-sso">
            {providers.map((p) => (
              <a key={p.slug} className="btn btn-primary btn-block" href={oidcStartUrl(p.slug, next)}>
                {t('login.ssoButton', { name: p.displayName })}
              </a>
            ))}
          </div>
        )}
        {!localVisible && (
          <button type="button" className="link-button login-local-toggle" onClick={() => setShowLocal(true)}>
            {t('login.showLocal')}
          </button>
        )}
        {localVisible && providers.length > 0 && <div className="login-divider">{t('login.or')}</div>}
        {localVisible && <p className="login-hint">{t('login.hint')}</p>}
        {localVisible && error && <Alert kind="error">{error}</Alert>}
        {localVisible && (
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label htmlFor="login-username">{t('login.username')}</label>
            <input
              id="login-username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoFocus={providers.length === 0}
              required
            />
          </div>
          <div className="form-field">
            <label htmlFor="login-password">{t('login.password')}</label>
            <input
              id="login-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </div>
          <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
            {busy ? t('login.submitting') : t('login.submit')}
          </button>
        </form>
        )}
      </div>
    </div>
  );
}
