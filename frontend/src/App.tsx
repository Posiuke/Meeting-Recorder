import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from './store/hooks';
import { fetchMe } from './store/authSlice';
import { fetchFeatures } from './store/featuresSlice';
import { isLanguage, setLanguage } from './i18n';
import Layout from './components/Layout';
import RequireAuth from './components/RequireAuth';
import LoginPage from './pages/LoginPage';
import SsoCallbackPage from './pages/SsoCallbackPage';
import BotsPage from './pages/BotsPage';
import RecordingsPage from './pages/RecordingsPage';
import RecordingDetailPage from './pages/RecordingDetailPage';
import SharePage from './pages/SharePage';
import StopRecordingPage from './pages/StopRecordingPage';
import GroupsPage from './pages/GroupsPage';
import TemplatesPage from './pages/TemplatesPage';
import GlossaryPage from './pages/GlossaryPage';
import ApiPage from './pages/ApiPage';
import AdminPage from './pages/AdminPage';
import ChangePassword from './pages/ChangePassword';
import Spinner from './components/Spinner';

/** Erzwingt die Passwort-Aenderung, solange ein Initialpasswort aktiv ist. */
function PasswordGate({ children }: { children: ReactNode }) {
  const mustChange = useAppSelector((s) => s.auth.user?.mustChangePassword ?? false);
  const location = useLocation();
  if (mustChange && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}

/**
 * Bereiche der BigBlueButton-Anbindung gibt es nur mit BBB_ON am Server.
 * Bis die Angabe da ist, warten statt umzuleiten - sonst landet ein Neuladen
 * von /bots kurz auf der Startseite.
 */
function RequireBbb({ children }: { children: ReactNode }) {
  const { loaded, bbbEnabled } = useAppSelector((s) => s.features);
  if (!loaded) return <Spinner />;
  if (!bbbEnabled) return <Navigate to="/" replace />;
  return <>{children}</>;
}

export default function App() {
  const dispatch = useAppDispatch();
  const status = useAppSelector((s) => s.auth.status);
  const userLanguage = useAppSelector((s) => s.auth.user?.language);

  const featuresLoaded = useAppSelector((s) => s.features.loaded);

  // Optionale Bereiche (z.B. BigBlueButton) - öffentlich, daher unabhängig vom Login.
  useEffect(() => {
    if (!featuresLoaded) {
      dispatch(fetchFeatures());
    }
  }, [featuresLoaded, dispatch]);

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
      <Route path="/login/sso" element={<SsoCallbackPage />} />
      {/* Freigabe-Link: bewusst ohne RequireAuth - die Berechtigung steckt im Token */}
      <Route path="/share/:token" element={<SharePage />} />
      {/* Anonymer Stopp-Link aus dem Aufnahme-Hinweis des Bots - ohne Anmeldung */}
      <Route
        path="/stop/:token"
        element={
          <RequireBbb>
            <StopRecordingPage />
          </RequireBbb>
        }
      />
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
        <Route
          path="/bots"
          element={
            <RequireBbb>
              <BotsPage />
            </RequireBbb>
          }
        />
        <Route path="/groups" element={<GroupsPage />} />
        <Route path="/templates" element={<TemplatesPage />} />
        <Route path="/glossary" element={<GlossaryPage />} />
        {/* Nicht /api: Das ist das Präfix der Backend-Routen, ein Neuladen der
            Seite landete dort beim Backend und endete mit 401. */}
        <Route path="/developer" element={<ApiPage />} />
        <Route path="/api" element={<Navigate to="/developer" replace />} />
        <Route path="/admin" element={<AdminPage />} />
        <Route path="/change-password" element={<ChangePassword />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
