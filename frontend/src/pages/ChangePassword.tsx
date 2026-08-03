import { useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { changePassword } from '../store/authSlice';
import Alert from '../components/Alert';
import { errorMessage } from '../api/client';
import { useI18n } from '../i18n';

/**
 * Passwort aendern. Wird sowohl fuer den erzwungenen Wechsel (Initialpasswort,
 * mustChangePassword) als auch fuer freiwillige Aenderungen genutzt.
 */
export default function ChangePassword() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const user = useAppSelector((s) => s.auth.user);
  const forced = user?.mustChangePassword ?? false;

  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [busy, setBusy] = useState(false);

  if (user && !user.local) {
    return (
      <div className="page narrow">
        <h1>{t('changePassword.heading')}</h1>
        <Alert kind="info">{t('changePassword.ldapManaged')}</Alert>
      </div>
    );
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (next.length < 8) {
      setError(t('changePassword.tooShort'));
      return;
    }
    if (next !== confirm) {
      setError(t('changePassword.mismatch'));
      return;
    }
    setBusy(true);
    try {
      await dispatch(changePassword({ currentPassword: current, newPassword: next })).unwrap();
      setSuccess(true);
      setCurrent('');
      setNext('');
      setConfirm('');
      if (forced) {
        navigate('/', { replace: true });
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page narrow">
      <h1>{t('changePassword.heading')}</h1>
      {forced && <Alert kind="info">{t('changePassword.forcedHint')}</Alert>}
      {success && !forced && <Alert kind="success">{t('changePassword.success')}</Alert>}
      {error && <Alert kind="error">{error}</Alert>}
      <form className="card auth-form" onSubmit={handleSubmit}>
        <div className="form-field">
          <label htmlFor="cp-current">{t('changePassword.currentLabel')}</label>
          <input
            id="cp-current"
            type="password"
            value={current}
            autoComplete="current-password"
            onChange={(e) => setCurrent(e.target.value)}
            required
          />
        </div>
        <div className="form-field">
          <label htmlFor="cp-new">{t('changePassword.newLabel')}</label>
          <input
            id="cp-new"
            type="password"
            value={next}
            autoComplete="new-password"
            onChange={(e) => setNext(e.target.value)}
            required
          />
        </div>
        <div className="form-field">
          <label htmlFor="cp-confirm">{t('changePassword.confirmLabel')}</label>
          <input
            id="cp-confirm"
            type="password"
            value={confirm}
            autoComplete="new-password"
            onChange={(e) => setConfirm(e.target.value)}
            required
          />
        </div>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? t('common.saving') : t('changePassword.heading')}
        </button>
      </form>
    </div>
  );
}
