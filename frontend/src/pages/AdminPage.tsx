import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  fetchAdminUsers,
  fetchAuthConfig,
  fetchProcessingQueue,
  fetchSettings,
  retryProcessingJob,
  saveAuthConfig,
  saveSettings,
  setUserAdmin,
  testLdap,
} from '../store/adminSlice';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import HelpTip from '../components/HelpTip';
import { api, errorMessage } from '../api/client';
import StatusBadge from '../components/StatusBadge';
import { formatDateTime, formatShortDuration, formatTime } from '../utils/format';
import { useI18n } from '../i18n';
import type { TranslationKey, translate } from '../i18n';
import type {
  ActiveRecordingView,
  ConnectionTestResult,
  LdapTestResult,
  ProcessingJobView,
} from '../types';

interface SettingsGroupDef {
  /** Übersetzungsschlüssel der Überschrift. */
  titleKey: TranslationKey;
  prefix: string;
  /** Übersetzungsschlüssel des Hinweistexts. */
  noteKey?: TranslationKey;
}

const SETTING_GROUPS: SettingsGroupDef[] = [
  { titleKey: 'admin.groupWhisper', prefix: 'whisper.', noteKey: 'admin.groupWhisperNote' },
  { titleKey: 'admin.groupLlm', prefix: 'llm.', noteKey: 'admin.groupLlmNote' },
  { titleKey: 'admin.groupCorrection', prefix: 'correction.', noteKey: 'admin.groupCorrectionNote' },
  { titleKey: 'admin.groupSummary', prefix: 'summary.' },
  { titleKey: 'admin.groupDocuments', prefix: 'documents.', noteKey: 'admin.groupDocumentsNote' },
  { titleKey: 'admin.groupProcessing', prefix: 'processing.', noteKey: 'admin.groupProcessingNote' },
  { titleKey: 'admin.groupRecording', prefix: 'recording.' },
  { titleKey: 'admin.groupCapture', prefix: 'capture.', noteKey: 'admin.groupCaptureNote' },
  { titleKey: 'admin.groupSharing', prefix: 'sharing.', noteKey: 'admin.groupSharingNote' },
  { titleKey: 'admin.groupBot', prefix: 'bot.' },
  { titleKey: 'admin.groupCleanup', prefix: 'cleanup.' },
];

const MULTILINE_KEYS = new Set([
  'summary.systemPrompt',
  'correction.systemPrompt',
  'bot.warnMessage',
]);

/** API-Schlüssel werden maskiert dargestellt (Wert bleibt editierbar). */
const SECRET_KEYS = new Set(['llm.apiKey', 'whisper.openaiApiKey']);

/** Gruppen mit „Verbindung testen"-Button (testet die gespeicherten Einstellungen). */
const TEST_ENDPOINTS: Record<string, string> = {
  'whisper.': '/api/admin/settings/test-whisper',
  'llm.': '/api/admin/settings/test-llm',
  'documents.': '/api/admin/settings/test-tika',
};

/** Einstellungen mit fester Auswahl statt Freitext. */
const SELECT_OPTIONS: Record<string, { value: string; labelKey: TranslationKey }[]> = {
  'whisper.provider': [
    { value: 'local', labelKey: 'admin.providerLocal' },
    { value: 'openai', labelKey: 'admin.providerOpenai' },
  ],
  'documents.ocrStrategy': [
    { value: 'auto', labelKey: 'admin.ocrAuto' },
    { value: 'no_ocr', labelKey: 'admin.ocrNone' },
    { value: 'ocr_only', labelKey: 'admin.ocrOnly' },
    { value: 'ocr_and_text_extraction', labelKey: 'admin.ocrBoth' },
  ],
};

/** Kurze Hilfe-Tooltips zu erklärungsbedürftigen Einstellungen. */
const KEY_HELP: Record<string, TranslationKey> = {
  'whisper.provider': 'admin.keyHelp.whisperProvider',
  'whisper.url': 'admin.keyHelp.whisperUrl',
  'whisper.openaiUrl': 'admin.keyHelp.whisperOpenaiUrl',
  'whisper.openaiApiKey': 'admin.keyHelp.whisperOpenaiApiKey',
  'whisper.openaiModel': 'admin.keyHelp.whisperOpenaiModel',
  'llm.baseUrl': 'admin.keyHelp.llmBaseUrl',
  'llm.apiKey': 'admin.keyHelp.llmApiKey',
  'llm.model': 'admin.keyHelp.llmModel',
  'documents.enabled': 'admin.keyHelp.documentsEnabled',
  'documents.maxMegabytes': 'admin.keyHelp.documentsMaxMegabytes',
  'documents.tikaUrl': 'admin.keyHelp.documentsTikaUrl',
  'documents.tikaTimeoutSec': 'admin.keyHelp.documentsTikaTimeoutSec',
  'documents.ocrStrategy': 'admin.keyHelp.documentsOcrStrategy',
  'documents.ocrLanguage': 'admin.keyHelp.documentsOcrLanguage',
  'documents.maxCharsPerDocument': 'admin.keyHelp.documentsMaxCharsPerDocument',
  'documents.promptMaxChars': 'admin.keyHelp.documentsPromptMaxChars',
  'llm.disableThinking': 'admin.keyHelp.llmDisableThinking',
  'correction.enabled': 'admin.keyHelp.correctionEnabled',
  'correction.systemPrompt': 'admin.keyHelp.correctionSystemPrompt',
  'correction.chunkChars': 'admin.keyHelp.correctionChunkChars',
  'correction.maxSentenceChars': 'admin.keyHelp.correctionMaxSentenceChars',
  'correction.glossaryMaxChars': 'admin.keyHelp.correctionGlossaryMaxChars',
  'capture.enabled': 'admin.keyHelp.captureEnabled',
  'capture.maxMegabytes': 'admin.keyHelp.captureMaxMegabytes',
  'capture.staleMinutes': 'admin.keyHelp.captureStaleMinutes',
  'sharing.publicLinks': 'admin.keyHelp.sharingPublicLinks',
  'bot.warnMessage': 'admin.keyHelp.botWarnMessage',
  'bot.anonymousStopEnabled': 'admin.keyHelp.botAnonymousStopEnabled',
  'bot.publicUrl': 'admin.keyHelp.botPublicUrl',
};

type AdminTab = 'settings' | 'auth' | 'users' | 'processing';

/** Nachladeintervall des Betriebsbildes – kurz, weil sich die Schlange bewegt. */
const PROCESSING_REFRESH_MS = 10_000;

export default function AdminPage() {
  const { t } = useI18n();
  const user = useAppSelector((s) => s.auth.user);
  const [tab, setTab] = useState<AdminTab>('settings');

  if (!user?.admin) {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="page">
      <h1>{t('admin.heading')}</h1>
      <div className="tabs">
        <button
          type="button"
          className={`tab${tab === 'settings' ? ' active' : ''}`}
          onClick={() => setTab('settings')}
        >
          {t('admin.tabSettings')}
        </button>
        <button
          type="button"
          className={`tab${tab === 'auth' ? ' active' : ''}`}
          onClick={() => setTab('auth')}
        >
          {t('admin.tabAuth')}
        </button>
        <button
          type="button"
          className={`tab${tab === 'users' ? ' active' : ''}`}
          onClick={() => setTab('users')}
        >
          {t('admin.tabUsers')}
        </button>
        <button
          type="button"
          className={`tab${tab === 'processing' ? ' active' : ''}`}
          onClick={() => setTab('processing')}
        >
          {t('admin.tabProcessing')}
        </button>
      </div>
      {tab === 'settings' && <SettingsTab />}
      {tab === 'auth' && <AuthTab />}
      {tab === 'users' && <UsersTab />}
      {tab === 'processing' && <ProcessingTab />}
    </div>
  );
}

function AuthTab() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { authConfig, authLoading, authError } = useAppSelector((s) => s.admin);
  const [values, setValues] = useState<Record<string, string>>({});
  const [saveMsg, setSaveMsg] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  const [saving, setSaving] = useState(false);

  const [testUser, setTestUser] = useState('');
  const [testPass, setTestPass] = useState('');
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<LdapTestResult | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  useEffect(() => {
    dispatch(fetchAuthConfig());
  }, [dispatch]);

  useEffect(() => {
    if (authConfig) setValues({ ...authConfig });
  }, [authConfig]);

  if (authLoading && !authConfig) {
    return <Spinner label={t('admin.authLoading')} />;
  }
  if (authError && !authConfig) {
    return <Alert kind="error">{authError}</Alert>;
  }

  const ldapEnabled = (values['auth.ldapEnabled'] ?? 'false') === 'true';
  const set = (key: string, value: string) => setValues((v) => ({ ...v, [key]: value }));

  const handleSave = async () => {
    setSaving(true);
    setSaveMsg(null);
    try {
      await dispatch(saveAuthConfig(values)).unwrap();
      setSaveMsg({ kind: 'success', text: t('admin.authSaved') });
    } catch (e) {
      setSaveMsg({ kind: 'error', text: errorMessage(e) });
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    setTestError(null);
    try {
      const result = await dispatch(
        testLdap({ username: testUser.trim(), password: testPass }),
      ).unwrap();
      setTestResult(result);
    } catch (e) {
      setTestError(errorMessage(e));
    } finally {
      setTesting(false);
    }
  };

  return (
    <div>
      <section className="card settings-group">
        <h2>{t('admin.authHeading')}</h2>
        <p className="settings-note">{t('admin.authNote')}</p>
        {saveMsg && <Alert kind={saveMsg.kind}>{saveMsg.text}</Alert>}
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={ldapEnabled}
            onChange={(e) => set('auth.ldapEnabled', e.target.checked ? 'true' : 'false')}
          />
          {t('admin.authEnable')}
        </label>
        <div className="settings-fields">
          <div className="form-field">
            <label htmlFor="ldap-domain">{t('admin.authDomain')}</label>
            <input
              id="ldap-domain"
              type="text"
              value={values['auth.ldapDomain'] ?? ''}
              onChange={(e) => set('auth.ldapDomain', e.target.value)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="ldap-url">{t('admin.authUrl')}</label>
            <input
              id="ldap-url"
              type="text"
              value={values['auth.ldapUrl'] ?? ''}
              onChange={(e) => set('auth.ldapUrl', e.target.value)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="ldap-rootdn">{t('admin.authRootDn')}</label>
            <input
              id="ldap-rootdn"
              type="text"
              value={values['auth.ldapRootDn'] ?? ''}
              onChange={(e) => set('auth.ldapRootDn', e.target.value)}
            />
          </div>
          <div className="form-field field-full">
            <label htmlFor="ldap-admins">{t('admin.authAdmins')}</label>
            <input
              id="ldap-admins"
              type="text"
              value={values['auth.bootstrapAdmins'] ?? ''}
              onChange={(e) => set('auth.bootstrapAdmins', e.target.value)}
            />
            <span className="field-default">{t('admin.authAdminsHint')}</span>
          </div>
        </div>
        <div className="settings-group-footer">
          <button type="button" className="btn btn-primary" disabled={saving} onClick={handleSave}>
            {saving ? t('common.saving') : t('common.save')}
          </button>
        </div>
      </section>

      <section className="card settings-group">
        <h2>{t('admin.authTestHeading')}</h2>
        <p className="settings-note">
          {t('admin.authTestNotePrefix')}
          <strong>{t('admin.authTestNoteStrong')}</strong>
          {t('admin.authTestNoteSuffix')}
        </p>
        {testError && <Alert kind="error">{testError}</Alert>}
        {testResult && (
          <Alert kind={testResult.success ? 'success' : 'error'}>
            {testResult.message}
            {testResult.success && testResult.displayName && (
              <> — {testResult.displayName}
              {testResult.email ? ` (${testResult.email})` : ''}</>
            )}
          </Alert>
        )}
        <div className="settings-fields">
          <div className="form-field">
            <label htmlFor="ldap-test-user">{t('admin.authTestUser')}</label>
            <input
              id="ldap-test-user"
              type="text"
              value={testUser}
              onChange={(e) => setTestUser(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="ldap-test-pass">{t('admin.authTestPass')}</label>
            <input
              id="ldap-test-pass"
              type="password"
              value={testPass}
              onChange={(e) => setTestPass(e.target.value)}
            />
          </div>
        </div>
        <div className="settings-group-footer">
          <button
            type="button"
            className="btn"
            disabled={testing || !testUser.trim() || !testPass}
            onClick={handleTest}
          >
            {testing ? t('admin.testing') : t('admin.authTestSubmit')}
          </button>
        </div>
      </section>
    </div>
  );
}

function SettingsTab() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { settings, defaults, settingsLoading, settingsError } = useAppSelector((s) => s.admin);
  const [values, setValues] = useState<Record<string, string>>({});
  const [messages, setMessages] = useState<
    Record<string, { kind: 'success' | 'error'; text: string }>
  >({});
  const [savingGroup, setSavingGroup] = useState<string | null>(null);
  const [testingGroup, setTestingGroup] = useState<string | null>(null);

  useEffect(() => {
    dispatch(fetchSettings());
  }, [dispatch]);

  useEffect(() => {
    if (settings) {
      setValues({ ...settings });
    }
  }, [settings]);

  const groupedKeys = useMemo(() => {
    if (!settings) return [];
    const allKeys = Object.keys(settings).sort();
    const used = new Set<string>();
    const result: { def: SettingsGroupDef; keys: string[] }[] = [];
    for (const def of SETTING_GROUPS) {
      const keys = allKeys.filter((k) => k.startsWith(def.prefix));
      keys.forEach((k) => used.add(k));
      if (keys.length > 0) {
        result.push({ def, keys });
      }
    }
    const rest = allKeys.filter((k) => !used.has(k));
    if (rest.length > 0) {
      result.push({ def: { titleKey: 'admin.groupOther', prefix: '' }, keys: rest });
    }
    return result;
  }, [settings]);

  if (settingsLoading && !settings) {
    return <Spinner label={t('admin.settingsLoading')} />;
  }
  if (settingsError && !settings) {
    return <Alert kind="error">{settingsError}</Alert>;
  }
  if (!settings) {
    return null;
  }

  const isDirty = (key: string) => (values[key] ?? '') !== (settings[key] ?? '');

  const handleSaveGroup = async (def: SettingsGroupDef, keys: string[]) => {
    const changed: Record<string, string> = {};
    for (const key of keys) {
      if (isDirty(key)) {
        changed[key] = values[key] ?? '';
      }
    }
    if (Object.keys(changed).length === 0) return;
    setSavingGroup(def.prefix);
    setMessages((m) => {
      const next = { ...m };
      delete next[def.prefix];
      return next;
    });
    try {
      await dispatch(saveSettings(changed)).unwrap();
      setMessages((m) => ({
        ...m,
        [def.prefix]: { kind: 'success', text: t('admin.settingsSaved') },
      }));
    } catch (e) {
      setMessages((m) => ({
        ...m,
        [def.prefix]: { kind: 'error', text: errorMessage(e) },
      }));
    } finally {
      setSavingGroup(null);
    }
  };

  const handleTest = async (def: SettingsGroupDef) => {
    const endpoint = TEST_ENDPOINTS[def.prefix];
    if (!endpoint) return;
    setTestingGroup(def.prefix);
    setMessages((m) => {
      const next = { ...m };
      delete next[def.prefix];
      return next;
    });
    try {
      const result = await api<ConnectionTestResult>(endpoint, { method: 'POST' });
      setMessages((m) => ({
        ...m,
        [def.prefix]: { kind: result.success ? 'success' : 'error', text: result.message },
      }));
    } catch (e) {
      setMessages((m) => ({
        ...m,
        [def.prefix]: { kind: 'error', text: errorMessage(e) },
      }));
    } finally {
      setTestingGroup(null);
    }
  };

  return (
    <div>
      {groupedKeys.map(({ def, keys }) => {
        const dirtyCount = keys.filter(isDirty).length;
        const message = messages[def.prefix];
        return (
          <section key={def.prefix} className="card settings-group">
            <h2>{t(def.titleKey)}</h2>
            {def.noteKey && <p className="settings-note">{t(def.noteKey)}</p>}
            {message && <Alert kind={message.kind}>{message.text}</Alert>}
            <div className="settings-fields">
              {keys.map((key) => {
                const dirty = isDirty(key);
                const defaultValue = defaults?.[key];
                const multiline = MULTILINE_KEYS.has(key);
                const selectOptions = SELECT_OPTIONS[key];
                const label = def.prefix ? key.slice(def.prefix.length) : key;
                return (
                  <div
                    key={key}
                    className={`form-field${dirty ? ' field-changed' : ''}${
                      multiline ? ' field-full' : ''
                    }`}
                  >
                    <label htmlFor={`setting-${key}`} title={key}>
                      {label}
                      {KEY_HELP[key] && <HelpTip text={t(KEY_HELP[key])} />}
                      {dirty && <span className="dirty-marker">{t('admin.changedMarker')}</span>}
                    </label>
                    {multiline ? (
                      <textarea
                        id={`setting-${key}`}
                        rows={6}
                        value={values[key] ?? ''}
                        onChange={(e) =>
                          setValues((v) => ({ ...v, [key]: e.target.value }))
                        }
                      />
                    ) : selectOptions ? (
                      <select
                        id={`setting-${key}`}
                        value={values[key] ?? ''}
                        onChange={(e) =>
                          setValues((v) => ({ ...v, [key]: e.target.value }))
                        }
                      >
                        {selectOptions.map((o) => (
                          <option key={o.value} value={o.value}>
                            {t(o.labelKey)}
                          </option>
                        ))}
                        {(values[key] ?? '') !== '' &&
                          !selectOptions.some((o) => o.value === values[key]) && (
                            <option value={values[key]}>{values[key]}</option>
                          )}
                      </select>
                    ) : (
                      <input
                        id={`setting-${key}`}
                        type={SECRET_KEYS.has(key) ? 'password' : 'text'}
                        autoComplete={SECRET_KEYS.has(key) ? 'new-password' : undefined}
                        value={values[key] ?? ''}
                        onChange={(e) =>
                          setValues((v) => ({ ...v, [key]: e.target.value }))
                        }
                      />
                    )}
                    {defaultValue !== undefined && (
                      <span className="field-default">
                        {t('admin.defaultPrefix', {
                          value: defaultValue === '' ? t('admin.defaultEmpty') : defaultValue,
                        })}
                        {(values[key] ?? '') !== defaultValue && (
                          <button
                            type="button"
                            className="link-button"
                            onClick={() =>
                              setValues((v) => ({ ...v, [key]: defaultValue }))
                            }
                          >
                            {t('admin.applyDefault')}
                          </button>
                        )}
                      </span>
                    )}
                  </div>
                );
              })}
            </div>
            <div className="settings-group-footer">
              <button
                type="button"
                className="btn btn-primary"
                disabled={dirtyCount === 0 || savingGroup !== null}
                onClick={() => handleSaveGroup(def, keys)}
              >
                {savingGroup === def.prefix
                  ? t('common.saving')
                  : dirtyCount > 0
                    ? t('admin.saveCount', { count: dirtyCount })
                    : t('common.save')}
              </button>
              {TEST_ENDPOINTS[def.prefix] && (
                <>
                  <button
                    type="button"
                    className="btn"
                    disabled={savingGroup !== null || testingGroup !== null || dirtyCount > 0}
                    onClick={() => handleTest(def)}
                  >
                    {testingGroup === def.prefix ? t('admin.testing') : t('admin.testConnection')}
                  </button>
                  {dirtyCount > 0 && (
                    <span className="muted">{t('admin.testAfterSave')}</span>
                  )}
                </>
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
}

/** Abstand der automatischen Aktualisierung der Nutzerliste. */
const USERS_REFRESH_MS = 20000;

/** Volle Minuten seit dem Zeitpunkt – für "nimmt seit 23 Min auf". */
function minutesSince(iso: string): number {
  return Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 60000));
}

function UsersTab() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const me = useAppSelector((s) => s.auth.user);
  const { users, usersLoading, usersError } = useAppSelector((s) => s.admin);
  const [error, setError] = useState<string | null>(null);
  const [busyUserId, setBusyUserId] = useState<string | null>(null);
  const [refreshedAt, setRefreshedAt] = useState<string>(() => new Date().toISOString());

  // Der Zustand ist nur brauchbar, wenn er aktuell ist: Die Liste lädt sich
  // nach, solange der Tab offen ist.
  useEffect(() => {
    const load = () => {
      void dispatch(fetchAdminUsers());
      setRefreshedAt(new Date().toISOString());
    };
    load();
    const timer = window.setInterval(load, USERS_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [dispatch]);

  const sourceLabel = (source: ActiveRecordingView['source']) => {
    if (source === 'CAPTURE') return t('recordingDetail.sourceCapture');
    if (source === 'UPLOAD') return t('recordingDetail.sourceUpload');
    return t('recordingDetail.sourceBot');
  };

  const since = (recording: ActiveRecordingView) =>
    t('admin.usersRecordingSince', {
      time: formatTime(recording.startedAt),
      minutes: minutesSince(recording.startedAt),
    });

  const recordingUsers = users.filter((u) => u.activeRecordings.length > 0);

  const handleToggle = async (userId: string, admin: boolean) => {
    setError(null);
    setBusyUserId(userId);
    try {
      await dispatch(setUserAdmin({ userId, admin })).unwrap();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusyUserId(null);
    }
  };

  if (usersLoading && users.length === 0) {
    return <Spinner label={t('admin.usersLoading')} />;
  }

  return (
    <>
      {/* Erst die Warnung, dann die Liste: Vor Wartungsarbeiten ist genau das
          die Frage, die ein Admin beantwortet haben will. */}
      {recordingUsers.length > 0 ? (
        <Alert kind="info">
          <strong>{t('admin.usersRunningTitle')}</strong>
          <ul className="admin-running-list">
            {recordingUsers.flatMap((user) =>
              user.activeRecordings.map((recording) => (
                <li key={recording.id}>
                  <span className="badge badge-red badge-pulse">
                    {recording.status === 'FINALIZING'
                      ? t('admin.usersFinalizing')
                      : t('admin.usersRecording')}
                  </span>{' '}
                  {t('admin.usersRunningEntry', {
                    user: user.displayName || user.username,
                    title: recording.title ?? t('admin.usersUntitled'),
                    source: sourceLabel(recording.source),
                    since: since(recording),
                  })}
                </li>
              )),
            )}
          </ul>
          {t('admin.usersRunningWarning')}
        </Alert>
      ) : (
        <p className="muted">{t('admin.usersRunningNone')}</p>
      )}

      <div className="card">
        {usersError && <Alert kind="error">{usersError}</Alert>}
        {error && <Alert kind="error">{error}</Alert>}

        <div className="admin-users-head">
          <span className="muted">
            {t('admin.usersRefreshed', { time: formatTime(refreshedAt) })}
          </span>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            disabled={usersLoading}
            onClick={() => {
              void dispatch(fetchAdminUsers());
              setRefreshedAt(new Date().toISOString());
            }}
          >
            {t('admin.usersRefresh')}
          </button>
        </div>

        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t('admin.usersUsername')}</th>
                <th>{t('admin.usersDisplayName')}</th>
                <th>{t('admin.usersEmail')}</th>
                <th>
                  {t('admin.usersStatus')}
                  <HelpTip text={t('admin.usersStatusHelp')} />
                </th>
                <th>{t('admin.usersRecordingColumn')}</th>
                <th>{t('admin.usersAdmin')}</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id}>
                  <td>{user.username}</td>
                  <td>{user.displayName}</td>
                  <td>{user.email ?? '–'}</td>
                  <td>
                    {user.online ? (
                      <span className="badge badge-green badge-pulse">
                        {t('admin.usersOnline')}
                      </span>
                    ) : (
                      <span className="badge badge-gray">{t('admin.usersOffline')}</span>
                    )}
                    <div className="muted admin-user-seen">
                      {user.lastSeenAt
                        ? t('admin.usersLastSeen', { date: formatDateTime(user.lastSeenAt) })
                        : user.lastLoginAt
                          ? t('admin.usersLastLogin', { date: formatDateTime(user.lastLoginAt) })
                          : t('admin.usersLastSeenNever')}
                    </div>
                  </td>
                  <td>
                    {user.activeRecordings.length === 0 ? (
                      <span className="muted">{t('admin.usersRecordingNone')}</span>
                    ) : (
                      user.activeRecordings.map((recording) => (
                        <div key={recording.id} className="admin-user-recording">
                          <span className="badge badge-red badge-pulse">
                            {recording.status === 'FINALIZING'
                              ? t('admin.usersFinalizing')
                              : t('admin.usersRecording')}
                          </span>
                          <span className="muted">
                            {recording.title ?? t('admin.usersUntitled')} ·{' '}
                            {sourceLabel(recording.source)} · {since(recording)}
                          </span>
                        </div>
                      ))
                    )}
                  </td>
                  <td>
                    <label className="checkbox-field">
                      <input
                        type="checkbox"
                        checked={user.admin}
                        disabled={user.id === me?.id || busyUserId === user.id}
                        onChange={(e) => handleToggle(user.id, e.target.checked)}
                      />
                      {user.id === me?.id && (
                        <span className="muted">{t('admin.usersYourself')}</span>
                      )}
                    </label>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

/**
 * Admin-Tab „Verarbeitung" (Issue #5): das Betriebsbild der Warteschlange.
 *
 * Die Frage, die sich morgens stellt, ist „ist die Nacht durchgelaufen?".
 * Deshalb steht oben, ob das Zeitfenster offen ist und was wartet, darunter die
 * Schlange, dann die Fehlschläge mit Grund und zuletzt die Dauern – Ausreißer
 * erkennt man erst, wenn man den Normalfall daneben sieht.
 */
function ProcessingTab() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { processing, processingLoading, processingError } = useAppSelector((s) => s.admin);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyJobId, setBusyJobId] = useState<string | null>(null);
  const [refreshedAt, setRefreshedAt] = useState<string>(() => new Date().toISOString());

  // Ein Betriebsbild ist nur brauchbar, wenn es aktuell ist.
  useEffect(() => {
    const load = () => {
      void dispatch(fetchProcessingQueue());
      setRefreshedAt(new Date().toISOString());
    };
    load();
    const timer = window.setInterval(load, PROCESSING_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [dispatch]);

  const handleRetry = async (jobId: string) => {
    setActionError(null);
    setBusyJobId(jobId);
    try {
      await dispatch(retryProcessingJob(jobId)).unwrap();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusyJobId(null);
    }
  };

  if (processingLoading && processing === null) {
    return <Spinner label={t('admin.processingLoading')} />;
  }
  if (processingError && processing === null) {
    return <Alert kind="error">{processingError}</Alert>;
  }
  if (processing === null) return null;

  const { durations } = processing;
  const waitingForWindow = processing.queue.filter((j) => j.waitsForWindow).length;

  return (
    <>
      {/* Zeitfenster zuerst: Steht die Schlange, ist das die erste Erklärung. */}
      {processing.windowOpen ? (
        <Alert kind="info">
          {t('admin.processingWindowOpen', {
            start: processing.windowStart,
            end: processing.windowEnd,
          })}
        </Alert>
      ) : (
        <Alert kind="info">
          {t('admin.processingWindowClosed', {
            start: processing.windowStart,
            end: processing.windowEnd,
          })}
          {waitingForWindow > 0 && (
            <> {t('admin.processingWaitingForWindow', { count: waitingForWindow })}</>
          )}
        </Alert>
      )}

      <div className="stat-row">
        <Stat label={t('admin.processingPending')} value={processing.pending} />
        <Stat label={t('admin.processingRunning')} value={processing.running} />
        <Stat
          label={t('admin.processingFailed')}
          value={processing.failed}
          warn={processing.failed > 0}
        />
        <Stat label={t('admin.processingDone')} value={processing.done} />
      </div>

      <div className="card">
        <div className="admin-users-head">
          <span className="muted">
            {t('admin.processingRefreshed', { time: formatTime(refreshedAt) })}
          </span>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            disabled={processingLoading}
            onClick={() => {
              void dispatch(fetchProcessingQueue());
              setRefreshedAt(new Date().toISOString());
            }}
          >
            {t('admin.usersRefresh')}
          </button>
        </div>
        {processingError && <Alert kind="error">{processingError}</Alert>}
        {actionError && <Alert kind="error">{actionError}</Alert>}
      </div>

      <section>
        <h2>{t('admin.processingQueueTitle')}</h2>
        {processing.queue.length === 0 ? (
          <p className="muted">{t('admin.processingQueueEmpty')}</p>
        ) : (
          <JobTable jobs={processing.queue} showWaiting />
        )}
      </section>

      <section>
        <h2>{t('admin.processingFailuresTitle')}</h2>
        {processing.failures.length === 0 ? (
          <p className="muted">{t('admin.processingFailuresEmpty')}</p>
        ) : (
          <JobTable
            jobs={processing.failures}
            showError
            onRetry={handleRetry}
            busyJobId={busyJobId}
          />
        )}
      </section>

      <section>
        <h2>{t('admin.processingDurationsTitle')}</h2>
        {durations.sample === 0 ? (
          <p className="muted">{t('admin.processingDurationsEmpty')}</p>
        ) : (
          <div className="card">
            <p className="muted">{t('admin.processingDurationsNote', { count: durations.sample })}</p>
            <div className="stat-row">
              <Stat
                label={t('admin.processingMedianTotal')}
                value={formatShortDuration(durations.medianMs)}
              />
              <Stat
                label={t('admin.processingMaxTotal')}
                value={formatShortDuration(durations.maxMs)}
              />
              <Stat
                label={t('admin.processingMedianStt')}
                value={formatShortDuration(durations.medianSttMs)}
              />
              <Stat
                label={t('admin.processingMedianCorrection')}
                value={formatShortDuration(durations.medianCorrectionMs)}
              />
              <Stat
                label={t('admin.processingMedianSummary')}
                value={formatShortDuration(durations.medianSummaryMs)}
              />
            </div>
          </div>
        )}
      </section>
    </>
  );
}

/** Eine Kennzahl mit Beschriftung. */
function Stat({
  label,
  value,
  warn = false,
}: {
  label: string;
  value: number | string;
  warn?: boolean;
}) {
  return (
    <div className={`stat${warn ? ' stat-warn' : ''}`}>
      <span className="stat-value">{value}</span>
      <span className="stat-label">{label}</span>
    </div>
  );
}

/**
 * Auftragstabelle – einmal für die Schlange (mit Wartezeit), einmal für die
 * Fehlschläge (mit Grund und Knopf). Die Spalten unterscheiden sich, der Rest
 * nicht; zwei fast gleiche Tabellen wären nur doppelte Pflege.
 */
function JobTable({
  jobs,
  showWaiting = false,
  showError = false,
  onRetry,
  busyJobId,
}: {
  jobs: ProcessingJobView[];
  showWaiting?: boolean;
  showError?: boolean;
  onRetry?: (jobId: string) => void;
  busyJobId?: string | null;
}) {
  const { t } = useI18n();
  return (
    <div className="card table-card">
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{t('admin.processingColRecording')}</th>
              <th>{t('admin.processingColMode')}</th>
              <th>{t('common.status')}</th>
              <th>{t('admin.processingColAttempt')}</th>
              {showWaiting && <th>{t('admin.processingColWaiting')}</th>}
              <th>{t('admin.processingColSteps')}</th>
              {showError && <th>{t('admin.processingColError')}</th>}
              {onRetry && <th></th>}
            </tr>
          </thead>
          <tbody>
            {jobs.map((job) => (
              <tr key={job.id}>
                <td className="cell-url">
                  <Link to={`/recordings/${job.recordingId}`}>
                    {job.recordingTitle ?? t('admin.processingUntitled')}
                  </Link>
                  {job.recordingStatus && (
                    <>
                      {' '}
                      <StatusBadge status={job.recordingStatus} />
                    </>
                  )}
                </td>
                <td>
                  {t(`admin.processingMode.${job.mode}` as TranslationKey)}
                  {job.immediate && (
                    <>
                      {' '}
                      <span className="badge badge-blue">{t('admin.processingImmediate')}</span>
                    </>
                  )}
                </td>
                <td>
                  <StatusBadge status={job.status} />
                  {job.waitsForWindow && (
                    <>
                      {' '}
                      <span className="badge badge-orange">{t('admin.processingWaits')}</span>
                    </>
                  )}
                </td>
                <td>
                  {t('admin.processingAttemptOf', {
                    attempts: job.attempts,
                    max: job.maxAttempts,
                  })}
                </td>
                {showWaiting && <td>{formatShortDuration(job.waitingMs)}</td>}
                <td className="cell-steps">{stepSummary(job, t)}</td>
                {showError && <td className="cell-error">{job.lastError ?? '–'}</td>}
                {onRetry && (
                  <td>
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={busyJobId !== null && busyJobId !== undefined}
                      title={t('admin.processingRetryHint')}
                      onClick={() => onRetry(job.id)}
                    >
                      {busyJobId === job.id
                        ? t('common.pleaseWait')
                        : t('admin.processingRetry')}
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/**
 * Dauer der Schritte in einer Zelle. Nicht gelaufene Schritte bleiben weg –
 * eine Spalte voller „–" sagt weniger als drei Werte, die wirklich anfielen.
 */
function stepSummary(job: ProcessingJobView, t: typeof translate): string {
  const parts: string[] = [];
  if (job.sttMs != null) parts.push(`${t('admin.processingStepStt')} ${formatShortDuration(job.sttMs)}`);
  if (job.correctionMs != null) {
    parts.push(`${t('admin.processingStepCorrection')} ${formatShortDuration(job.correctionMs)}`);
  }
  if (job.summaryMs != null) {
    parts.push(`${t('admin.processingStepSummary')} ${formatShortDuration(job.summaryMs)}`);
  }
  if (job.durationMs != null) {
    parts.push(`${t('admin.processingStepTotal')} ${formatShortDuration(job.durationMs)}`);
  }
  return parts.length === 0 ? '–' : parts.join(' · ');
}
