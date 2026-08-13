import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from './store/hooks';
import { fetchMe } from './store/authSlice';
import { isLanguage, setLanguage } from './i18n';
import Layout from './components/Layout';
import RequireAuth from './components/RequireAuth';
import LoginPage from './pages/LoginPage';
import BotsPage from './pages/BotsPage';
import RecordingsPage from './pages/RecordingsPage';
import RecordingDetailPage from './pages/RecordingDetailPage';
import SharePage from './pages/SharePage';
import GroupsPage from './pages/GroupsPage';
import TemplatesPage from './pages/TemplatesPage';
import GlossaryPage from './pages/GlossaryPage';
import ApiPage from './pages/ApiPage';
import AdminPage from './pages/AdminPage';
import ChangePassword from './pages/ChangePassword';

/** Erzwingt die Passwort-Aenderung, solange ein Initialpasswort aktiv ist. */
function PasswordGate({ children }: { children: ReactNode }) {
  const mustChange = useAppSelector((s) => s.auth.user?.mustChangePassword ?? false);
  const location = useLocation();
  if (mustChange && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  const dispatch = useAppDispatch();
  const status = useAppSelector((s) => s.auth.status);
  const userLanguage = useAppSelector((s) => s.auth.user?.language);

  // Beim App-Start: gespeichertes Token über /api/auth/me validieren.
  useEffect(() => {
    if (status === 'idle') {
      dispatch(fetchMe());
    }
  }, [status, dispatch]);

  // Die am Konto gespeicherte Sprache gewinnt, sobald der Nutzer bekannt ist -
  // sie gilt damit auf jedem Gerät und in jedem Browser.
  useEffect(() => {
    if (isLanguage(userLanguage)) {
      setLanguage(userLanguage);
    }
  }, [userLanguage]);

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {/* Freigabe-Link: bewusst ohne RequireAuth - die Berechtigung steckt im Token */}
      <Route path="/share/:token" element={<SharePage />} />
      <Route
        element={
          <RequireAuth>
            <PasswordGate>
              <Layout />
            </PasswordGate>
          </RequireAuth>
        }
      >
        {/* Startseite nach dem Anmelden: die Aufnahmen */}
        <Route path="/" element={<Navigate to="/recordings" replace />} />
        <Route path="/recordings" element={<RecordingsPage />} />
        <Route path="/recordings/:id" element={<RecordingDetailPage />} />
        <Route path="/bots" element={<BotsPage />} />
        <Route path="/groups" element={<GroupsPage />} />
        <Route path="/templates" element={<TemplatesPage />} />
        <Route path="/glossary" element={<GlossaryPage />} />
        <Route path="/api" element={<ApiPage />} />
        <Route path="/admin" element={<AdminPage />} />
        <Route path="/change-password" element={<ChangePassword />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
