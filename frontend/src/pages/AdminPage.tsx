import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  fetchAdminUsers,
  fetchAuthConfig,
  fetchProcessingQueue,
  fetchSettings,
  createLocalUser,
  resetUserPassword,
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
import AdminGroupsTab from './AdminGroupsTab';
import { formatDateTime, formatShortDuration, formatTime } from '../utils/format';
import { useI18n } from '../i18n';
import type { TranslationKey, translate } from '../i18n';
import type {
  ActiveRecordingView,
  ConnectionTestResult,
  LdapTestResult,
  ProcessingJobView,
  SettingSpec,
} from '../types';
import { STT_AUTO, STT_LANGUAGES, sttLanguageLabel } from '../components/SttLanguageSelect';

interface SettingsGroupDef {
  /** Übersetzungsschlüssel der Überschrift. */
  titleKey: TranslationKey;
  prefix: string;
  /** Übersetzungsschlüssel des Hinweistexts. */
  noteKey?: TranslationKey;
}

const SETTING_GROUPS: SettingsGroupDef[] = [
  { titleKey: 'admin.groupWhisper', prefix: 'whisper.', noteKey: 'admin.groupWhisperNote' },
  { titleKey: 'admin.groupSpeakers', prefix: 'speakers.', noteKey: 'admin.groupSpeakersNote' },
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

/** Ersatz, falls das Backend für einen Schlüssel (noch) keinen Typ liefert. */
const TEXT_SPEC: SettingSpec = { type: 'TEXT', min: null, max: null, options: null, optional: true };

/**
 * Prüft einen Wert gegen seine Typbeschreibung – dieselben Regeln wie im
 * Backend (SettingsService#problemWith), damit Fehler schon beim Tippen
 * auffallen und nicht erst beim Speichern.
 */
function settingProblem(spec: SettingSpec, raw: string, t: typeof translate): string | null {
  const v = raw.trim();
  if (v === '') {
    if (spec.type === 'TEXT' || spec.type === 'MULTILINE' || spec.type === 'SECRET') return null;
    return spec.optional ? null : t('admin.fieldRequired');
  }
  switch (spec.type) {
    case 'INTEGER':
    case 'DECIMAL': {
      const pattern = spec.type === 'INTEGER' ? /^-?\d+$/ : /^-?\d+(\.\d+)?$/;
      if (!pattern.test(v)) {
        return t(spec.type === 'INTEGER' ? 'admin.fieldInteger' : 'admin.fieldNumber');
      }
      const n = Number(v);
      if (spec.min !== null && n < spec.min) return t('admin.fieldMin', { min: spec.min });
      if (spec.max !== null && n > spec.max) return t('admin.fieldMax', { max: spec.max });
      return null;
    }
    case 'BOOLEAN':
      return v === 'true' || v === 'false' ? null : t('admin.fieldChoice');
    case 'CHOICE':
      return spec.options?.includes(v.toLowerCase()) ? null : t('admin.fieldChoice');
    case 'URL':
      return /^https?:\/\/[^\s/]+/i.test(v) ? null : t('admin.fieldUrl');
    case 'TIME':
      return /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/.test(v) ? null : t('admin.fieldTime');
    default:
      return null;
  }
}

/** Sehr große Obergrenzen („praktisch unbegrenzt“) nicht als Bereich anzeigen. */
const SHOWN_MAX_LIMIT = 1_000_000_000;

/**
 * Gruppen mit Anbieter-Umschalter: Es werden nur die Felder des gewählten
 * Anbieters gezeigt. Die anderen bleiben gespeichert – beim Zurückschalten ist
 * die alte Konfiguration wieder da.
 */
const PROVIDER_FIELDS: Record<string, { providerKey: string; only: Record<string, string[]> }> = {
  'whisper.': {
    providerKey: 'whisper.provider',
    only: {
      local: ['whisper.url'],
      openai: [
        'whisper.openaiUrl',
        'whisper.openaiApiKey',
        'whisper.openaiModel',
        'whisper.openaiDiarizeModel',
      ],
    },
  },
  'llm.': {
    providerKey: 'llm.provider',
    only: {
      local: ['llm.baseUrl', 'llm.apiKey', 'llm.model', 'llm.disableThinking'],
      openai: [
        'llm.openaiUrl',
        'llm.openaiApiKey',
        'llm.openaiModel',
        'llm.openaiReasoningEffort',
      ],
    },
  },
};

/** Ist das Feld beim aktuell gewählten Anbieter seiner Gruppe sichtbar? */
function visibleForProvider(key: string, values: Record<string, string>): boolean {
  for (const def of Object.values(PROVIDER_FIELDS)) {
    const provider = values[def.providerKey] ?? '';
    for (const [name, keys] of Object.entries(def.only)) {
      if (keys.includes(key)) return name === provider;
    }
  }
  return true;
}

/** Modellliste des Cloud-LLM-Anbieters (GET /models über das Backend). */
interface LlmModelList {
  success: boolean;
  models: string[];
  error: string | null;
}

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
  'llm.provider': [
    { value: 'local', labelKey: 'admin.llmProviderLocal' },
    { value: 'openai', labelKey: 'admin.llmProviderOpenai' },
  ],
  'llm.openaiReasoningEffort': [
    { value: 'off', labelKey: 'admin.reasoningOff' },
    { value: 'none', labelKey: 'admin.reasoningNone' },
    { value: 'minimal', labelKey: 'admin.reasoningMinimal' },
    { value: 'low', labelKey: 'admin.reasoningLow' },
    { value: 'medium', labelKey: 'admin.reasoningMedium' },
    { value: 'high', labelKey: 'admin.reasoningHigh' },
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
  'whisper.openaiDiarizeModel': 'admin.keyHelp.whisperOpenaiDiarizeModel',
  'whisper.diarize': 'admin.keyHelp.whisperDiarize',
  'speakers.nameSuggestions': 'admin.keyHelp.speakersNameSuggestions',
  'speakers.autoApply': 'admin.keyHelp.speakersAutoApply',
  'speakers.bbbActivity': 'admin.keyHelp.speakersBbbActivity',
  'llm.baseUrl': 'admin.keyHelp.llmBaseUrl',
  'llm.apiKey': 'admin.keyHelp.llmApiKey',
  'llm.model': 'admin.keyHelp.llmModel',
  'llm.provider': 'admin.keyHelp.llmProvider',
  'llm.openaiUrl': 'admin.keyHelp.llmOpenaiUrl',
  'llm.openaiApiKey': 'admin.keyHelp.llmOpenaiApiKey',
  'llm.openaiModel': 'admin.keyHelp.llmOpenaiModel',
  'llm.openaiReasoningEffort': 'admin.keyHelp.llmOpenaiReasoningEffort',
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

type AdminTab = 'settings' | 'auth' | 'users' | 'groups' | 'processing';

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
          className={`tab${tab === 'groups' ? ' active' : ''}`}
          onClick={() => setTab('groups')}
        >
          {t('admin.tabGroups')}
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
      {tab === 'groups' && <AdminGroupsTab />}
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
  const { settings, defaults, schema, settingsLoading, settingsError } = useAppSelector((s) => s.admin);
  const [values, setValues] = useState<Record<string, string>>({});
  const [messages, setMessages] = useState<
    Record<string, { kind: 'success' | 'error'; text: string }>
  >({});
  const [savingGroup, setSavingGroup] = useState<string | null>(null);
  const [testingGroup, setTestingGroup] = useState<string | null>(null);
  const [llmModels, setLlmModels] = useState<LlmModelList | null>(null);
  const [llmModelsLoading, setLlmModelsLoading] = useState(false);

  useEffect(() => {
    dispatch(fetchSettings());
  }, [dispatch]);

  // Adresse und Key dürfen noch ungespeichert sein: So lässt sich ein neuer Key
  // ausprobieren, bevor er gespeichert wird.
  const loadLlmModels = async (baseUrl: string, apiKey: string) => {
    setLlmModelsLoading(true);
    try {
      setLlmModels(
        await api<LlmModelList>('/api/admin/settings/llm-models', {
          method: 'POST',
          body: { baseUrl, apiKey },
        }),
      );
    } catch (e) {
      setLlmModels({ success: false, models: [], error: errorMessage(e) });
    } finally {
      setLlmModelsLoading(false);
    }
  };

  // Beim Umschalten auf die Cloud gleich einmal laden – dafür ist die Liste da.
  const llmCloud = values['llm.provider'] === 'openai';
  useEffect(() => {
    if (llmCloud && llmModels === null && !llmModelsLoading) {
      void loadLlmModels(values['llm.openaiUrl'] ?? '', values['llm.openaiApiKey'] ?? '');
    }
  }, [llmCloud]);

  useEffect(() => {
    if (settings) {
      setValues({ ...settings });
    }
  }, [settings]);

  const groupedKeys = useMemo(() => {
    if (!settings) return [];
    // Der Anbieter-Umschalter steht oben in seiner Gruppe: Er entscheidet,
    // welche Felder darunter überhaupt gelten.
    const allKeys = Object.keys(settings).sort((a, b) => {
      const pa = a.endsWith('.provider') ? 0 : 1;
      const pb = b.endsWith('.provider') ? 0 : 1;
      return pa - pb || a.localeCompare(b);
    });
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
  const specOf = (key: string): SettingSpec => schema?.[key] ?? TEXT_SPEC;
  // Nur geänderte Felder prüfen: Ein schon gespeicherter Altwert soll das
  // Speichern anderer Felder der Gruppe nicht blockieren.
  const problemOf = (key: string) =>
    isDirty(key) ? settingProblem(specOf(key), values[key] ?? '', t) : null;

  const handleSaveGroup = async (def: SettingsGroupDef, keys: string[]) => {
    const changed: Record<string, string> = {};
    for (const key of keys) {
      if (isDirty(key)) {
        changed[key] = values[key] ?? '';
      }
    }
    if (Object.keys(changed).length === 0) return;
    if (Object.keys(changed).some((key) => problemOf(key) !== null)) {
      setMessages((m) => ({ ...m, [def.prefix]: { kind: 'error', text: t('admin.fixInvalid') } }));
      return;
    }
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
        const invalidCount = keys.filter((key) => problemOf(key) !== null).length;
        const message = messages[def.prefix];
        return (
          <section key={def.prefix} className="card settings-group">
            <h2>{t(def.titleKey)}</h2>
            {def.noteKey && <p className="settings-note">{t(def.noteKey)}</p>}
            {message && <Alert kind={message.kind}>{message.text}</Alert>}
            <div className="settings-fields">
              {keys.filter((key) => visibleForProvider(key, values)).map((key) => {
                const dirty = isDirty(key);
                const defaultValue = defaults?.[key];
                const spec = specOf(key);
                const multiline = spec.type === 'MULTILINE';
                const problem = problemOf(key);
                const setValue = (value: string) => setValues((v) => ({ ...v, [key]: value }));
                // Auswahl mit übersetzten Beschriftungen, sonst die rohen Werte des Schemas.
                const selectOptions: { value: string; label: string }[] | undefined =
                  SELECT_OPTIONS[key]?.map((o) => ({ value: o.value, label: t(o.labelKey) })) ??
                  (spec.type === 'BOOLEAN'
                    ? [
                        { value: 'true', label: t('admin.boolTrue') },
                        { value: 'false', label: t('admin.boolFalse') },
                      ]
                    : spec.type === 'CHOICE'
                      ? (spec.options ?? []).map((o) => ({ value: o, label: o }))
                      : spec.type === 'LANGUAGE'
                        ? [STT_AUTO, ...STT_LANGUAGES].map((code) => ({
                            value: code,
                            label: sttLanguageLabel(code, t),
                          }))
                        : undefined);
                const label = def.prefix ? key.slice(def.prefix.length) : key;
                return (
                  <div
                    key={key}
                    className={`form-field${dirty ? ' field-changed' : ''}${
                      multiline ? ' field-full' : ''
                    }${problem ? ' field-invalid' : ''}`}
                  >
                    <label htmlFor={`setting-${key}`} title={key}>
                      {label}
                      {KEY_HELP[key] && <HelpTip text={t(KEY_HELP[key])} />}
                      {dirty && <span className="dirty-marker">{t('admin.changedMarker')}</span>}
                    </label>
                    {multiline ? (
                      <textarea
                        id={`setting-${key}`}
                        rows={key.endsWith('Prompt') || key.endsWith('Message') ? 6 : 3}
                        value={values[key] ?? ''}
                        onChange={(e) => setValue(e.target.value)}
                      />
                    ) : key === 'llm.openaiModel' ? (
                      <div className="model-picker">
                        <select
                          id={`setting-${key}`}
                          value={values[key] ?? ''}
                          onChange={(e) =>
                            setValues((v) => ({ ...v, [key]: e.target.value }))
                          }
                        >
                          {(values[key] ?? '') !== '' &&
                            !(llmModels?.models ?? []).includes(values[key]) && (
                              <option value={values[key]}>{values[key]}</option>
                            )}
                          {(llmModels?.models ?? []).map((m) => (
                            <option key={m} value={m}>
                              {m}
                            </option>
                          ))}
                        </select>
                        <button
                          type="button"
                          className="btn btn-sm"
                          disabled={llmModelsLoading}
                          onClick={() =>
                            loadLlmModels(
                              values['llm.openaiUrl'] ?? '',
                              values['llm.openaiApiKey'] ?? '',
                            )
                          }
                        >
                          {llmModelsLoading ? t('admin.llmModelsLoading') : t('admin.llmModelsLoad')}
                        </button>
                        {llmModels && !llmModels.success && (
                          <span className="field-error">{llmModels.error}</span>
                        )}
                        {llmModels?.success && (
                          <span className="muted">
                            {t('admin.llmModelsCount', { count: llmModels.models.length })}
                          </span>
                        )}
                      </div>
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
                            {o.label}
                          </option>
                        ))}
                        {(values[key] ?? '') !== '' &&
                          !selectOptions.some((o) => o.value === values[key]) && (
                            <option value={values[key]}>{values[key]}</option>
                          )}
                      </select>
                    ) : spec.type === 'INTEGER' || spec.type === 'DECIMAL' ? (
                      <input
                        id={`setting-${key}`}
                        type="number"
                        inputMode={spec.type === 'INTEGER' ? 'numeric' : 'decimal'}
                        step={spec.type === 'INTEGER' ? 1 : 'any'}
                        min={spec.min ?? undefined}
                        max={spec.max !== null && spec.max < SHOWN_MAX_LIMIT ? spec.max : undefined}
                        value={values[key] ?? ''}
                        onChange={(e) => setValue(e.target.value)}
                      />
                    ) : spec.type === 'TIME' ? (
                      <input
                        id={`setting-${key}`}
                        type="time"
                        value={values[key] ?? ''}
                        onChange={(e) => setValue(e.target.value)}
                      />
                    ) : (
                      <input
                        id={`setting-${key}`}
                        type={spec.type === 'SECRET' ? 'password' : spec.type === 'URL' ? 'url' : 'text'}
                        autoComplete={spec.type === 'SECRET' ? 'new-password' : undefined}
                        spellCheck={spec.type === 'URL' || spec.type === 'SECRET' ? false : undefined}
                        value={values[key] ?? ''}
                        onChange={(e) => setValue(e.target.value)}
                      />
                    )}
                    {problem && <span className="field-error">{problem}</span>}
                    {!problem &&
                      (spec.type === 'INTEGER' || spec.type === 'DECIMAL') &&
                      spec.min !== null &&
                      spec.max !== null &&
                      spec.max < SHOWN_MAX_LIMIT && (
                        <span className="field-hint">
                          {t('admin.fieldRange', { min: spec.min, max: spec.max })}
                        </span>
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
                disabled={dirtyCount === 0 || invalidCount > 0 || savingGroup !== null}
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

/** Mindestlänge lokaler Passwörter – wie im Backend (AuthService.MIN_PASSWORD_LENGTH). */
const MIN_PASSWORD_LENGTH = 8;

/** Zufallspasswort ohne leicht verwechselbare Zeichen (0/O, 1/l/I) zum Weitergeben. */
function generatePassword(length = 14): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_!';
  const random = new Uint32Array(length);
  crypto.getRandomValues(random);
  return Array.from(random, (n) => alphabet[n % alphabet.length]).join('');
}

/**
 * Formular „Lokalen Benutzer anlegen“: für Nutzer ohne LDAP/Active Directory.
 * Das Initialpasswort gibt der Admin weiter; beim ersten Login muss es
 * geändert werden.
 */
function CreateLocalUserForm() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const [open, setOpen] = useState(false);
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [admin, setAdmin] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<{ username: string; password: string } | null>(null);

  const usernameValid = /^[A-Za-z0-9._@-]{2,64}$/.test(username.trim());
  const passwordValid = password.length >= MIN_PASSWORD_LENGTH;

  const reset = () => {
    setUsername('');
    setDisplayName('');
    setEmail('');
    setPassword('');
    setAdmin(false);
  };

  const handleSubmit = async () => {
    if (!usernameValid || !passwordValid) return;
    setBusy(true);
    setError(null);
    try {
      const user = await dispatch(
        createLocalUser({ username: username.trim(), displayName, email, password, admin }),
      ).unwrap();
      setCreated({ username: user.username, password });
      reset();
      setOpen(false);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="admin-create-user">
      {created && (
        <Alert kind="success">
          {t('admin.usersCreated', { username: created.username })}{' '}
          <code className="admin-password">{created.password}</code>
          <button type="button" className="link-button" onClick={() => setCreated(null)}>
            {t('admin.usersCreatedDismiss')}
          </button>
        </Alert>
      )}
      {!open ? (
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => {
            setOpen(true);
            setCreated(null);
            if (!password) setPassword(generatePassword());
          }}
        >
          {t('admin.usersCreateOpen')}
        </button>
      ) : (
        <form
          className="admin-create-user-form"
          onSubmit={(e) => {
            e.preventDefault();
            void handleSubmit();
          }}
        >
          <h3>{t('admin.usersCreateTitle')}</h3>
          <p className="muted">{t('admin.usersCreateHint')}</p>
          {error && <Alert kind="error">{error}</Alert>}
          <div className="form-row">
            <div className={`form-field${username && !usernameValid ? ' field-invalid' : ''}`}>
              <label htmlFor="new-user-name">{t('admin.usersUsername')}</label>
              <input
                id="new-user-name"
                value={username}
                autoComplete="off"
                maxLength={64}
                required
                onChange={(e) => setUsername(e.target.value)}
              />
              {username && !usernameValid && (
                <span className="field-error">{t('admin.usersUsernameRule')}</span>
              )}
            </div>
            <div className="form-field">
              <label htmlFor="new-user-display">{t('admin.usersDisplayName')}</label>
              <input
                id="new-user-display"
                value={displayName}
                maxLength={200}
                placeholder={username.trim()}
                onChange={(e) => setDisplayName(e.target.value)}
              />
            </div>
            <div className="form-field">
              <label htmlFor="new-user-email">{t('admin.usersEmail')}</label>
              <input
                id="new-user-email"
                type="email"
                value={email}
                maxLength={200}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
          </div>
          <div className="form-row">
            <div className={`form-field${password && !passwordValid ? ' field-invalid' : ''}`}>
              <label htmlFor="new-user-password">{t('admin.usersInitialPassword')}</label>
              <div className="model-picker">
                <input
                  id="new-user-password"
                  value={password}
                  autoComplete="new-password"
                  spellCheck={false}
                  required
                  onChange={(e) => setPassword(e.target.value)}
                />
                <button type="button" className="btn btn-sm" onClick={() => setPassword(generatePassword())}>
                  {t('admin.usersPasswordGenerate')}
                </button>
              </div>
              <span className={password && !passwordValid ? 'field-error' : 'field-hint'}>
                {t('admin.usersPasswordRule', { min: MIN_PASSWORD_LENGTH })}
              </span>
            </div>
          </div>
          <label className="checkbox-field">
            <input type="checkbox" checked={admin} onChange={(e) => setAdmin(e.target.checked)} />
            {t('admin.usersCreateAdmin')}
          </label>
          <div className="settings-group-footer">
            <button
              type="submit"
              className="btn btn-primary"
              disabled={busy || !usernameValid || !passwordValid}
            >
              {busy ? t('common.saving') : t('admin.usersCreateSubmit')}
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={busy}
              onClick={() => {
                setOpen(false);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

function UsersTab() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const me = useAppSelector((s) => s.auth.user);
  const { users, usersLoading, usersError } = useAppSelector((s) => s.admin);
  const [error, setError] = useState<string | null>(null);
  const [busyUserId, setBusyUserId] = useState<string | null>(null);
  const [resetUserId, setResetUserId] = useState<string | null>(null);
  const [resetPassword, setResetPassword] = useState('');
  const [resetDone, setResetDone] = useState<{ username: string; password: string } | null>(null);
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

  const handleReset = async (userId: string, username: string) => {
    if (resetPassword.length < MIN_PASSWORD_LENGTH) return;
    setError(null);
    setBusyUserId(userId);
    try {
      await dispatch(resetUserPassword({ userId, password: resetPassword })).unwrap();
      setResetDone({ username, password: resetPassword });
      setResetUserId(null);
      setResetPassword('');
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
        {resetDone && (
          <Alert kind="success">
            {t('admin.usersResetDone', { username: resetDone.username })}{' '}
            <code className="admin-password">{resetDone.password}</code>
            <button type="button" className="link-button" onClick={() => setResetDone(null)}>
              {t('admin.usersCreatedDismiss')}
            </button>
          </Alert>
        )}

        <CreateLocalUserForm />

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
                  <td>
                    {user.username}
                    <div className="admin-user-account">
                      <span className={`badge ${user.local ? 'badge-blue' : 'badge-gray'}`}>
                        {user.local ? t('admin.usersTypeLocal') : t('admin.usersTypeLdap')}
                      </span>
                      {user.local && user.mustChangePassword && (
                        <span className="muted"> {t('admin.usersMustChange')}</span>
                      )}
                    </div>
                    {user.local &&
                      user.id !== me?.id &&
                      (resetUserId === user.id ? (
                        <form
                          className="admin-reset-form"
                          onSubmit={(e) => {
                            e.preventDefault();
                            void handleReset(user.id, user.username);
                          }}
                        >
                          <input
                            value={resetPassword}
                            aria-label={t('admin.usersInitialPassword')}
                            autoComplete="new-password"
                            spellCheck={false}
                            onChange={(e) => setResetPassword(e.target.value)}
                          />
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm"
                            onClick={() => setResetPassword(generatePassword())}
                          >
                            {t('admin.usersPasswordGenerate')}
                          </button>
                          <button
                            type="submit"
                            className="btn btn-primary btn-sm"
                            disabled={
                              busyUserId === user.id || resetPassword.length < MIN_PASSWORD_LENGTH
                            }
                          >
                            {t('admin.usersResetSubmit')}
                          </button>
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm"
                            onClick={() => setResetUserId(null)}
                          >
                            {t('common.cancel')}
                          </button>
                        </form>
                      ) : (
                        <button
                          type="button"
                          className="link-button"
                          onClick={() => {
                            setResetUserId(user.id);
                            setResetPassword(generatePassword());
                            setResetDone(null);
                          }}
                        >
                          {t('admin.usersResetPassword')}
                        </button>
                      ))}
                  </td>
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
