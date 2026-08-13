import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { loggedOut, saveLanguage } from '../store/authSlice';
import { LANGUAGES, useI18n } from '../i18n';
import type { Language } from '../i18n';

export default function Layout() {
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const user = useAppSelector((s) => s.auth.user);
  const { t, language } = useI18n();

  const handleLogout = () => {
    dispatch(loggedOut());
    navigate('/login');
  };

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header-inner">
          <div className="app-brand">
            <span className="app-brand-dot" />
            {t('app.brand')}
          </div>
          <nav className="app-nav">
            <NavLink to="/recordings">{t('app.nav.recordings')}</NavLink>
            <NavLink to="/groups">{t('app.nav.groups')}</NavLink>
            <NavLink to="/bots">{t('app.nav.bots')}</NavLink>
            <NavLink to="/templates">{t('app.nav.templates')}</NavLink>
            <NavLink to="/glossary">{t('app.nav.glossary')}</NavLink>
            <NavLink to="/api">{t('app.nav.api')}</NavLink>
            {user?.admin && <NavLink to="/admin">{t('app.nav.admin')}</NavLink>}
          </nav>
          <div className="app-user">
            <span className="app-user-name">{user?.displayName}</span>
            <select
              className="language-select"
              aria-label={t('app.languageLabel')}
              title={t('app.languageLabel')}
              value={language}
              onChange={(e) => void dispatch(saveLanguage(e.target.value as Language))}
            >
              {LANGUAGES.map((entry) => (
                <option key={entry.code} value={entry.code}>
                  {entry.label}
                </option>
              ))}
            </select>
            {user?.local && (
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => navigate('/change-password')}
              >
                {t('app.changePassword')}
              </button>
            )}
            <button type="button" className="btn btn-ghost btn-sm" onClick={handleLogout}>
              {t('app.logout')}
            </button>
          </div>
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
