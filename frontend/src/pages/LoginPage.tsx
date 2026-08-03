import { useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { login } from '../store/authSlice';
import Alert from '../components/Alert';
import { LANGUAGES, useI18n } from '../i18n';
import type { Language } from '../i18n';

export default function LoginPage() {
  const dispatch = useAppDispatch();
  const { t, language, setLanguage } = useI18n();
  const location = useLocation();
  const status = useAppSelector((s) => s.auth.status);
  const error = useAppSelector((s) => s.auth.error);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  if (status === 'authenticated') {
    const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname;
    return <Navigate to={from ?? '/'} replace />;
  }

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
        <p className="login-hint">{t('login.hint')}</p>
        {error && <Alert kind="error">{error}</Alert>}
        <form onSubmit={handleSubmit}>
          <div className="form-field">
            <label htmlFor="login-username">{t('login.username')}</label>
            <input
              id="login-username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoFocus
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
      </div>
    </div>
  );
}
