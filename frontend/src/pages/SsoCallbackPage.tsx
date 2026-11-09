import { useEffect, useRef } from 'react';
import { Navigate } from 'react-router-dom';
import { setToken } from '../api/client';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { fetchMe } from '../store/authSlice';
import Spinner from '../components/Spinner';
import { useI18n } from '../i18n';

/** Nur interne Ziele – passend zur Prüfung im Backend (OidcLoginService.safeNext). */
function safeNext(next: string | null): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/';
  return next;
}

/**
 * Rückkehr von der OIDC-Anmeldung. Das Backend hängt das eigene Login-Token
 * als Fragment an (#token=…&next=…) – es landet so weder in Server-Logs noch im
 * Referer. Danach läuft alles wie nach einer Passwortanmeldung.
 */
export default function SsoCallbackPage() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const status = useAppSelector((s) => s.auth.status);
  const params = useRef(new URLSearchParams(window.location.hash.replace(/^#/, '')));
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const token = params.current.get('token');
    // Token sofort aus der Adressleiste entfernen (Verlauf, Lesezeichen)
    window.history.replaceState(null, '', window.location.pathname);
    if (token) {
      setToken(token);
      dispatch(fetchMe());
    }
  }, [dispatch]);

  if (!params.current.get('token')) {
    return <Navigate to="/login?sso_error=session" replace />;
  }
  if (status === 'authenticated') {
    return <Navigate to={safeNext(params.current.get('next'))} replace />;
  }
  if (status === 'unauthenticated') {
    return <Navigate to="/login?sso_error=session" replace />;
  }
  return <Spinner label={t('login.checking')} />;
}
