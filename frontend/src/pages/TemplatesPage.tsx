import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  createPromptTemplate,
  deletePromptTemplate,
  fetchDefaultPrompt,
  fetchPromptTemplates,
  updatePromptTemplate,
} from '../store/promptTemplatesSlice';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import HelpTip from '../components/HelpTip';
import { PRESET_KEYS } from '../components/PromptPresetSelect';
import { errorMessage } from '../api/client';
import { formatDateTime } from '../utils/format';
import { useI18n } from '../i18n';
import type { PromptTemplateView } from '../types';

/** Serverseitige Grenzen (PromptTemplateController). */
const MAX_PROMPT_LENGTH = 8000;
const MAX_NAME_LENGTH = 100;
const MAX_MODEL_LENGTH = 200;
/** Bandbreite der Temperatur bei OpenAI-kompatiblen Endpunkten. */
const MAX_TEMPERATURE = 2;
const MAX_TEMPLATES = 100;
/** Ab dieser Anzahl lohnt sich das Filterfeld in der Liste. */
const FILTER_THRESHOLD = 6;

interface EditorState {
  /** ID der eigenen Vorlage; null = neuer, noch nicht gespeicherter Entwurf. */
  id: string | null;
  name: string;
  prompt: string;
  /** Modell dieser Vorlage; leer = Vorgabe des Administrators. */
  model: string;
  /** Temperatur dieser Vorlage als Eingabetext; leer = Vorgabe des Administrators. */
  temperature: string;
  /** Schlüssel der integrierten Vorlage, aus der der Entwurf entstanden ist. */
  fromBuiltIn: string | null;
  /** Zuletzt gespeicherter Stand – Grundlage der "nicht gespeichert"-Erkennung. */
  base: { name: string; prompt: string; model: string; temperature: string };
}

const editorFor = (template: PromptTemplateView): EditorState => {
  const model = template.model ?? '';
  const temperature = template.temperature?.toString() ?? '';
  return {
    id: template.id,
    name: template.name,
    prompt: template.prompt,
    model,
    temperature,
    fromBuiltIn: null,
    base: { name: template.name, prompt: template.prompt, model, temperature },
  };
};

const draft = (name: string, prompt: string, fromBuiltIn: string | null = null): EditorState => ({
  id: null,
  name,
  prompt,
  model: '',
  temperature: '',
  fromBuiltIn,
  // Der vorbelegte Stand ist die Vergleichsbasis: Wer eine integrierte Vorlage
  // nur ansieht, hat nichts geaendert und soll beim Weiterklicken auch nicht
  // gefragt werden. Speichern ist trotzdem moeglich (siehe canSave).
  base: { name, prompt, model: '', temperature: '' },
});

/**
 * Eigener Tab für die Promptvorlagen der Zusammenfassung: links die Liste
 * (eigene und integrierte Vorlagen), rechts ein großzügiger Editor. Der Dialog
 * "Auswertung anpassen" bleibt für den Schnellzugriff pro Aufnahme zuständig –
 * das ausführliche Überarbeiten der Vorlagen passiert hier.
 */
export default function TemplatesPage() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { items, loading, loaded, error, defaultPrompt } = useAppSelector(
    (s) => s.promptTemplates,
  );

  const [editor, setEditor] = useState<EditorState | null>(null);
  const [filter, setFilter] = useState('');
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<PromptTemplateView | null>(null);
  /** Aktion, die auf das Verwerfen ungespeicherter Änderungen wartet. */
  const [pending, setPending] = useState<(() => void) | null>(null);
  const savedTimer = useRef<number | null>(null);
  const nameInput = useRef<HTMLInputElement | null>(null);
  /** Verhindert, dass die Vorauswahl nach Löschen oder Speichern erneut greift. */
  const preselected = useRef(false);

  useEffect(() => {
    void dispatch(fetchPromptTemplates());
    void dispatch(fetchDefaultPrompt());
  }, [dispatch]);

  useEffect(() => () => {
    if (savedTimer.current !== null) window.clearTimeout(savedTimer.current);
  }, []);

  // Beim ersten Laden die erste Vorlage öffnen - der Editor steht damit
  // gefüllt da, statt eine leere Fläche zu zeigen.
  useEffect(() => {
    if (preselected.current || !loaded || items.length === 0) return;
    preselected.current = true;
    setEditor(editorFor(items[0]));
  }, [items, loaded]);

  const name = editor?.name ?? '';
  const prompt = editor?.prompt ?? '';
  const model = editor?.model ?? '';
  const temperature = editor?.temperature ?? '';
  const trimmedName = name.trim();
  const trimmedPrompt = prompt.trim();
  const dirty =
    editor !== null &&
    (name !== editor.base.name ||
      prompt !== editor.base.prompt ||
      model !== editor.base.model ||
      temperature !== editor.base.temperature);
  const promptTooLong = prompt.length > MAX_PROMPT_LENGTH;
  const nameTooLong = name.length > MAX_NAME_LENGTH;
  // Leer heißt "Vorgabe des Administrators"; sonst eine Zahl in der Bandbreite,
  // die OpenAI-kompatible Endpunkte annehmen.
  const temperatureNum = temperature.trim() === '' ? null : Number(temperature);
  const temperatureInvalid =
    temperatureNum !== null &&
    (Number.isNaN(temperatureNum) || temperatureNum < 0 || temperatureNum > MAX_TEMPERATURE);
  const modelTooLong = model.length > MAX_MODEL_LENGTH;
  // Ein Entwurf laesst sich immer speichern, sobald Name und Prompt stehen -
  // auch unveraendert aus einer integrierten Vorlage uebernommen. Bei einer
  // gespeicherten Vorlage gibt es ohne Aenderung nichts zu speichern.
  const canSave =
    editor !== null &&
    (editor.id === null || dirty) &&
    !busy &&
    trimmedName !== '' &&
    trimmedPrompt !== '' &&
    !promptTooLong &&
    !nameTooLong &&
    !modelTooLong &&
    !temperatureInvalid;

  const flashSaved = () => {
    setSaved(true);
    if (savedTimer.current !== null) window.clearTimeout(savedTimer.current);
    savedTimer.current = window.setTimeout(() => setSaved(false), 2500);
  };

  const open = (next: EditorState | null) => {
    setEditor(next);
    setFormError(null);
    setSaved(false);
    // Bei einem neuen Entwurf steht der Cursor gleich im Namensfeld.
    if (next && next.id === null) {
      window.setTimeout(() => nameInput.current?.focus(), 0);
    }
  };

  /** Wechsel nur nach Rückfrage, wenn im Editor ungespeicherte Änderungen stehen. */
  const guarded = (action: () => void) => {
    if (dirty) {
      setPending(() => action);
      return;
    }
    action();
  };

  const handleSave = useCallback(async () => {
    if (!editor || !canSave) return;
    const nextName = editor.name.trim();
    const nextPrompt = editor.prompt.trim();
    const nextModel = editor.model.trim() === '' ? null : editor.model.trim();
    const nextTemperature = temperatureNum;
    setBusy(true);
    setFormError(null);
    try {
      if (editor.id) {
        const updated = await dispatch(
          updatePromptTemplate({
            id: editor.id,
            name: nextName,
            prompt: nextPrompt,
            model: nextModel,
            temperature: nextTemperature,
          }),
        ).unwrap();
        setEditor(editorFor(updated));
      } else {
        const created = await dispatch(
          createPromptTemplate({
            name: nextName,
            prompt: nextPrompt,
            model: nextModel,
            temperature: nextTemperature,
          }),
        ).unwrap();
        setEditor(editorFor(created));
      }
      flashSaved();
    } catch (e) {
      setFormError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }, [canSave, dispatch, editor, temperatureNum]);

  // Strg+S / ⌘+S speichert – bei langen Prompts erwartet man das. Nur solange
  // ein Editor offen ist, sonst bleibt das Browser-Kürzel unangetastet.
  useEffect(() => {
    if (editor === null) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        void handleSave();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [editor, handleSave]);

  // Browser-Rückfrage beim Schließen/Neuladen mit offenen Änderungen.
  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  const handleDelete = async () => {
    if (!confirmDelete) return;
    setBusy(true);
    setFormError(null);
    try {
      await dispatch(deletePromptTemplate(confirmDelete.id)).unwrap();
      if (editor?.id === confirmDelete.id) open(null);
      setConfirmDelete(null);
    } catch (e) {
      setFormError(errorMessage(e));
      setConfirmDelete(null);
    } finally {
      setBusy(false);
    }
  };

  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (needle === '') return items;
    return items.filter(
      (item) =>
        item.name.toLowerCase().includes(needle) || item.prompt.toLowerCase().includes(needle),
    );
  }, [filter, items]);

  const adminDefault = (defaultPrompt ?? '').trim();

  return (
    <div className="page">
      <div className="page-head">
        <h1>{t('templates.heading')}</h1>
        <button
          type="button"
          className="btn btn-primary"
          disabled={items.length >= MAX_TEMPLATES}
          title={
            items.length >= MAX_TEMPLATES ? t('templates.limitReached', { max: MAX_TEMPLATES }) : undefined
          }
          onClick={() => guarded(() => open(draft('', '')))}
        >
          {t('templates.new')}
        </button>
      </div>

      <p className="muted">{t('templates.intro')}</p>

      {error && <Alert kind="error">{error}</Alert>}

      {loading && !loaded && <Spinner label={t('templates.loading')} />}

      <div className="template-layout">
        <aside className="card template-list-card">
          <h2 className="template-list-title">
            {t('templates.mine')}
            <span className="template-count">
              {t('templates.count', { count: items.length, max: MAX_TEMPLATES })}
            </span>
          </h2>

          {items.length >= FILTER_THRESHOLD && (
            <input
              type="search"
              className="template-filter"
              placeholder={t('templates.filterPlaceholder')}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
          )}

          {loaded && items.length === 0 && (
            <p className="muted template-list-empty">{t('templates.empty')}</p>
          )}
          {items.length > 0 && visible.length === 0 && (
            <p className="muted template-list-empty">{t('templates.filterEmpty')}</p>
          )}

          <ul className="template-list">
            {visible.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  className={`template-list-item${editor?.id === item.id ? ' active' : ''}`}
                  onClick={() => guarded(() => open(editorFor(item)))}
                >
                  <span className="template-list-name">{item.name}</span>
                  <span className="template-list-meta">
                    {t('templates.updatedAt', {
                      date: formatDateTime(item.updatedAt ?? item.createdAt),
                    })}
                  </span>
                </button>
              </li>
            ))}
          </ul>

          <h2 className="template-list-title template-list-title-spaced">
            {t('templates.builtIn')}
            <HelpTip text={t('templates.builtInHelp')} />
          </h2>
          <ul className="template-list">
            {PRESET_KEYS.map((preset) => (
              <li key={preset.key}>
                <button
                  type="button"
                  className={`template-list-item${
                    editor?.fromBuiltIn === preset.key ? ' active' : ''
                  }`}
                  onClick={() =>
                    guarded(() =>
                      open(draft(t(preset.labelKey), t(preset.promptKey), preset.key)),
                    )
                  }
                >
                  <span className="template-list-name">{t(preset.labelKey)}</span>
                  <span className="template-list-meta">{t('templates.builtInAction')}</span>
                </button>
              </li>
            ))}
          </ul>
        </aside>

        <section className="card template-editor">
          {!editor ? (
            <div className="template-editor-empty">
              <h2>{t('templates.editorEmptyTitle')}</h2>
              <p className="muted">{t('templates.editorEmpty')}</p>
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => open(draft('', ''))}
              >
                {t('templates.new')}
              </button>
            </div>
          ) : (
            <>
              <div className="template-editor-head">
                <h2>{editor.id ? editor.base.name : t('templates.newTitle')}</h2>
                {dirty && <span className="badge badge-orange">{t('templates.unsaved')}</span>}
                {saved && !dirty && (
                  <span className="badge badge-green">{t('templates.savedFlash')}</span>
                )}
              </div>

              {editor.fromBuiltIn && (
                <p className="muted">
                  {t('templates.fromBuiltIn', {
                    name: t(
                      PRESET_KEYS.find((p) => p.key === editor.fromBuiltIn)?.labelKey ??
                        'templates.builtIn',
                    ),
                  })}
                </p>
              )}

              {formError && <Alert kind="error">{formError}</Alert>}

              <div className="form-field">
                <label htmlFor="template-name">{t('templates.nameLabel')}</label>
                <input
                  id="template-name"
                  type="text"
                  value={name}
                  maxLength={MAX_NAME_LENGTH}
                  placeholder={t('templates.namePlaceholder')}
                  disabled={busy}
                  ref={nameInput}
                  onChange={(e) => setEditor({ ...editor, name: e.target.value })}
                />
                {nameTooLong && (
                  <span className="field-error">
                    {t('templates.nameTooLong', { max: MAX_NAME_LENGTH })}
                  </span>
                )}
              </div>

              <div className="form-row">
                <div className="form-field grow">
                  <label htmlFor="template-model">
                    {t('templates.modelLabel')}
                    <HelpTip text={t('templates.modelHelp')} />
                  </label>
                  <input
                    id="template-model"
                    type="text"
                    value={model}
                    maxLength={MAX_MODEL_LENGTH}
                    placeholder={t('templates.modelPlaceholder')}
                    disabled={busy}
                    onChange={(e) => setEditor({ ...editor, model: e.target.value })}
                  />
                </div>
                <div className="form-field">
                  <label htmlFor="template-temperature">
                    {t('templates.temperatureLabel')}
                    <HelpTip text={t('templates.temperatureHelp')} />
                  </label>
                  <input
                    id="template-temperature"
                    type="number"
                    min={0}
                    max={MAX_TEMPERATURE}
                    step={0.1}
                    value={temperature}
                    placeholder={t('templates.temperaturePlaceholder')}
                    disabled={busy}
                    onChange={(e) => setEditor({ ...editor, temperature: e.target.value })}
                  />
                  {temperatureInvalid && (
                    <span className="field-error">
                      {t('templates.temperatureInvalid', { max: MAX_TEMPERATURE })}
                    </span>
                  )}
                </div>
              </div>

              <div className="form-field template-prompt">
                <label htmlFor="template-prompt">
                  {t('templates.promptLabel')}
                  <HelpTip text={t('templates.promptHelp')} />
                </label>
                <textarea
                  id="template-prompt"
                  value={prompt}
                  disabled={busy}
                  placeholder={t('templates.promptPlaceholder')}
                  onChange={(e) => setEditor({ ...editor, prompt: e.target.value })}
                />
                <div className="template-editor-status">
                  <span className={promptTooLong ? 'field-error' : 'muted'}>
                    {t('templates.charCount', {
                      count: prompt.length,
                      max: MAX_PROMPT_LENGTH,
                    })}
                  </span>
                  <span className="muted">{t('templates.saveShortcutHint')}</span>
                </div>
              </div>

              <div className="template-editor-actions">
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={!canSave}
                  onClick={() => void handleSave()}
                >
                  {busy ? t('common.saving') : t('common.save')}
                </button>
                <button
                  type="button"
                  className="btn btn-ghost"
                  disabled={busy || (!dirty && editor.id !== null)}
                  onClick={() =>
                    editor.id
                      ? open({
                          ...editor,
                          name: editor.base.name,
                          prompt: editor.base.prompt,
                          model: editor.base.model,
                          temperature: editor.base.temperature,
                        })
                      : guarded(() => open(null))
                  }
                >
                  {editor.id ? t('templates.discardChanges') : t('templates.discardDraft')}
                </button>
                <button
                  type="button"
                  className="btn btn-ghost"
                  disabled={busy || adminDefault === '' || trimmedPrompt === adminDefault}
                  title={
                    adminDefault === ''
                      ? t('templates.loadDefaultUnavailable')
                      : t('templates.loadDefaultHint')
                  }
                  onClick={() => setEditor({ ...editor, prompt: defaultPrompt ?? '' })}
                >
                  {t('templates.loadDefault')}
                </button>
                {editor.id && (
                  <>
                    <button
                      type="button"
                      className="btn btn-ghost"
                      disabled={busy}
                      title={t('templates.duplicateHint')}
                      onClick={() =>
                        // Kopiert wird der gespeicherte Stand - offene
                        // Aenderungen gehen laut Rueckfrage bewusst verloren.
                        guarded(() =>
                          open(
                            draft(
                              t('templates.copySuffix', { name: editor.base.name }).slice(
                                0,
                                MAX_NAME_LENGTH,
                              ),
                              editor.base.prompt,
                            ),
                          ),
                        )
                      }
                    >
                      {t('templates.duplicate')}
                    </button>
                    <button
                      type="button"
                      className="btn btn-ghost btn-danger-text template-editor-delete"
                      disabled={busy}
                      onClick={() => {
                        const current = items.find((i) => i.id === editor.id);
                        if (current) setConfirmDelete(current);
                      }}
                    >
                      {t('common.delete')}
                    </button>
                  </>
                )}
              </div>
            </>
          )}
        </section>
      </div>

      {confirmDelete && (
        <ConfirmDialog
          title={t('templates.confirmDeleteTitle')}
          message={t('templates.confirmDeleteMessage', { name: confirmDelete.name })}
          confirmLabel={t('common.delete')}
          danger
          busy={busy}
          onConfirm={handleDelete}
          onCancel={() => setConfirmDelete(null)}
        />
      )}

      {pending && (
        <ConfirmDialog
          title={t('templates.unsavedTitle')}
          message={
            editor?.id
              ? t('templates.unsavedMessage', { name: editor.base.name })
              : t('templates.unsavedMessageDraft')
          }
          confirmLabel={t('templates.unsavedConfirm')}
          danger
          onConfirm={() => {
            const action = pending;
            setPending(null);
            action();
          }}
          onCancel={() => setPending(null)}
        />
      )}
    </div>
  );
}
