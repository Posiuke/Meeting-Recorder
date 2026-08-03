import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAppSelector } from '../store/hooks';
import Spinner from './Spinner';
import { useI18n } from '../i18n';

/**
 * Schützt Routen: Bei vorhandenem, noch nicht validiertem Token wird ein
 * Spinner angezeigt; ohne gültige Anmeldung erfolgt eine Umleitung zu /login.
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const status = useAppSelector((s) => s.auth.status);
  const location = useLocation();

  if (status === 'idle' || status === 'loading') {
    return (
      <div className="fullpage-center">
        <Spinner label={t('login.checking')} />
      </div>
    );
  }

  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <>{children}</>;
}
