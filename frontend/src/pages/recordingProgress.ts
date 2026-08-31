import type { TranslationKey } from '../i18n';
import type { JobView, RecordingDetail, SummaryView } from '../types';

/**
 * Wo eine Aufnahme im Ablauf steht und was als Nächstes passiert.
 *
 * Bewusst eine **reine Funktion ohne React**: Der Ablauf hat zehn
 * unterscheidbare Zustände (siehe {@link resolveProgress}), und die waren
 * vorher über Status-Badge, Aktionsleiste, einen Hinweistext im
 * Zusammenfassungs-Tab und die Job-Tabelle verstreut. Nutzer mussten sich den
 * Weg daraus zusammenreimen. An einer Stelle entschieden, lässt sich die
 * Zuordnung lesen, prüfen und ändern, ohne durch JSX zu suchen.
 *
 * Die Funktion liefert nur Schlüssel und Rohwerte – Formatieren und Übersetzen
 * bleibt der Komponente. Deshalb auch `sinceIso` statt einer fertigen
 * Minutenangabe: „läuft seit 4 Minuten" hängt an der aktuellen Uhrzeit und
 * gehört nicht in eine Funktion, die für dieselbe Eingabe dasselbe liefern soll.
 */

/** Zustand einer Stufe der Fortschrittskette. */
export type StepState =
  /** Erledigt. */
  | 'done'
  /** Läuft gerade. */
  | 'running'
  /** Eingeplant, wartet auf seinen Start. */
  | 'waiting'
  /** Gescheitert. */
  | 'failed'
  /** Findet für diese Aufnahme nicht statt (KI abgewählt, zu wenig Inhalt). */
  | 'skipped'
  /** Noch offen, nicht eingeplant. */
  | 'open';

export type StepKey = 'recording' | 'transcript' | 'summary';

export interface ProgressStep {
  key: StepKey;
  state: StepState;
}

/**
 * Aktionen, die die Box anbieten kann. Alle drei laufen über Endpunkte, die
 * **Leserecht** genügen lassen (`/process`, `/transcribe`) – anders als
 * „Erneut auswerten"/„Transkription neu erstellen", die dem Besitzer
 * vorbehalten sind. Die Box braucht deshalb keine Besitzerprüfung.
 */
export type NextAction = 'process' | 'transcribe' | 'startAi';

export interface NextStep {
  /** Erklärtext: Zustand und was als Nächstes geschieht. */
  textKey: TranslationKey;
  vars?: Record<string, string | number>;
  /** Genau ein primärer Knopf; null = es läuft von allein oder ist fertig. */
  primary: { action: NextAction; labelKey: TranslationKey } | null;
  /** Zweiter Weg, als Textlink – nicht als gleichrangiger Knopf. */
  secondary: { action: NextAction; labelKey: TranslationKey } | null;
  /** Zusatzhinweis, z.B. erst die Sprecher benennen. */
  tipKey?: TranslationKey;
  /** Grund im Klartext (Fehlermeldung, Verwerfungsgrund, „kein Inhalt"). */
  detail?: string | null;
  /** Startzeitpunkt eines laufenden Schritts, für „läuft seit …". */
  sinceIso?: string | null;
}

export interface Progress {
  steps: ProgressStep[];
  /** null = nichts zu sagen; die Box wird dann nicht angezeigt. */
  next: NextStep | null;
}

/** Rahmenbedingungen der Verarbeitung; null = noch nicht geladen. */
export interface ProcessingInfo {
  windowStart: string;
  windowEnd: string;
  windowOpen: boolean;
}

const ACTION_PROCESS = { action: 'process' as NextAction, labelKey: 'recordingDetail.processNow' as TranslationKey };
const ACTION_TRANSCRIBE = { action: 'transcribe' as NextAction, labelKey: 'recordingDetail.transcribeOnly' as TranslationKey };
const ACTION_START_AI = { action: 'startAi' as NextAction, labelKey: 'recordingDetail.startAi' as TranslationKey };

/**
 * Der Auftrag, der gerade etwas tut oder gleich etwas tun wird. Die Jobliste
 * kommt neueste zuerst; wartend oder laufend ist höchstens einer relevant.
 */
function activeJobOf(jobs: JobView[]): JobView | null {
  return jobs.find((j) => j.status === 'PENDING' || j.status === 'RUNNING') ?? null;
}

/** Eine wirklich fertige Fassung – FAILED-Zeilen zählen nicht als Ergebnis. */
function hasFinishedSummary(summaries: SummaryView[]): boolean {
  return summaries.some((s) => s.status === 'DONE' && s.markdown !== null);
}

/**
 * Wartet dieser Auftrag auf das nächtliche Zeitfenster? Ohne geladene
 * Rahmenbedingungen bleibt es bei der bisherigen Annahme (nicht-sofortige
 * Aufträge warten) – lieber ein etwas vagerer Text als ein falscher.
 */
function waitsForWindow(job: JobView, info: ProcessingInfo | null): boolean {
  if (job.status !== 'PENDING' || job.immediate) return false;
  return info === null ? true : !info.windowOpen;
}

/**
 * Fortschrittskette und nächster Schritt.
 *
 * Die Reihenfolge der Fälle ist die Reihenfolge der Auswertung: Was gerade
 * passiert, geht vor dem, was passieren könnte.
 */
export function resolveProgress(detail: RecordingDetail, info: ProcessingInfo | null): Progress {
  const rec = detail.recording;
  const jobs = detail.jobs;
  const activeJob = activeJobOf(jobs);
  const lastJob = jobs[0] ?? null;
  const hasTranscript = detail.segments.some((s) => s.hasTranscript);
  const summaryDone = hasFinishedSummary(detail.summaries);
  const aiOff = !rec.aiAnalysis;

  return {
    steps: [
      { key: 'recording', state: recordingState(rec.status) },
      { key: 'transcript', state: transcriptState(detail, hasTranscript, activeJob) },
      { key: 'summary', state: summaryState(detail, hasTranscript, summaryDone, activeJob) },
    ],
    next: nextStep(detail, { activeJob, lastJob, hasTranscript, summaryDone, aiOff }, info),
  };
}

function recordingState(status: RecordingDetail['recording']['status']): StepState {
  if (status === 'RECORDING' || status === 'FINALIZING') return 'running';
  if (status === 'DISCARDED') return 'failed';
  return 'done';
}

function transcriptState(
  detail: RecordingDetail,
  hasTranscript: boolean,
  activeJob: JobView | null,
): StepState {
  const status = detail.recording.status;
  if (hasTranscript) return 'done';
  if (status === 'RECORDING' || status === 'FINALIZING') return 'open';
  if (status === 'DISCARDED') return 'skipped';
  // Ohne gewünschte KI-Analyse entsteht gar kein Auftrag: Die Aufnahme springt
  // direkt auf DONE, ein Transkript gibt es nie.
  if (!detail.recording.aiAnalysis) return 'skipped';
  if (activeJob?.status === 'RUNNING') return 'running';
  // Ob auf das Zeitfenster oder auf den Läufer gewartet wird, erklärt die Box -
  // die Kette zeigt nur, dass die Stufe eingeplant ist.
  if (activeJob) return 'waiting';
  if (status === 'FAILED') return 'failed';
  // DONE ohne Transkript heißt: Es wurde bewusst nicht erstellt (zu wenig Inhalt).
  if (status === 'DONE') return 'skipped';
  return 'open';
}

function summaryState(
  detail: RecordingDetail,
  hasTranscript: boolean,
  summaryDone: boolean,
  activeJob: JobView | null,
): StepState {
  const status = detail.recording.status;
  if (summaryDone) return 'done';
  if (status === 'DISCARDED') return 'skipped';
  if (!detail.recording.aiAnalysis) return 'skipped';
  // Nur-Transkription führt zu keiner Zusammenfassung - Schritt 2 bleibt offen.
  if (activeJob?.status === 'RUNNING' && !activeJob.transcribeOnly && hasTranscript) return 'running';
  if (activeJob && !activeJob.transcribeOnly) return 'waiting';
  if (status === 'FAILED' && hasTranscript) return 'failed';
  // DONE, aber keine Fassung: bewusst übersprungen (zu wenig Inhalt).
  if (status === 'DONE') return 'skipped';
  return 'open';
}

interface Facts {
  activeJob: JobView | null;
  lastJob: JobView | null;
  hasTranscript: boolean;
  summaryDone: boolean;
  aiOff: boolean;
}

function nextStep(detail: RecordingDetail, f: Facts, info: ProcessingInfo | null): NextStep | null {
  const rec = detail.recording;

  if (rec.status === 'DISCARDED') {
    return {
      textKey: 'recordingProgress.discarded',
      primary: null,
      secondary: null,
      detail: rec.discardReason,
    };
  }

  if (rec.status === 'RECORDING' || rec.status === 'FINALIZING') {
    return { textKey: 'recordingProgress.stillRecording', primary: null, secondary: null };
  }

  // Es passiert gerade etwas - dann gibt es nichts anzubieten.
  if (f.activeJob?.status === 'RUNNING') {
    return {
      textKey: f.activeJob.transcribeOnly
        ? 'recordingProgress.runningTranscript'
        : 'recordingProgress.runningAnalysis',
      primary: null,
      secondary: null,
      sinceIso: f.activeJob.startedAt,
      // Ein zweiter Versuch ist eine Information für sich - die Komponente
      // hängt den Hinweis an, wenn attempt > 1 ist.
      vars: { attempt: f.activeJob.attempts, max: f.activeJob.maxAttempts },
    };
  }

  if (f.activeJob) {
    // Wartet auf das Zeitfenster: Der Nutzer soll die Uhrzeit erfahren und den
    // Weg daran vorbei - das ist die häufigste "warum passiert nichts?"-Frage.
    if (waitsForWindow(f.activeJob, info)) {
      return {
        textKey: info
          ? 'recordingProgress.waitingWindowAt'
          : 'recordingProgress.waitingWindow',
        vars: info ? { start: info.windowStart, end: info.windowEnd } : undefined,
        primary: ACTION_PROCESS,
        secondary: ACTION_TRANSCRIBE,
      };
    }
    // Eingeplant und startfähig: Der Scheduler greift beim nächsten Durchlauf
    // zu, sofern nicht schon ein anderer Auftrag läuft.
    return { textKey: 'recordingProgress.queued', primary: null, secondary: null };
  }

  if (rec.status === 'FAILED') {
    return {
      textKey: f.hasTranscript
        ? 'recordingProgress.failedAfterTranscript'
        : 'recordingProgress.failed',
      vars: f.lastJob ? { attempts: f.lastJob.attempts, max: f.lastJob.maxAttempts } : undefined,
      primary: ACTION_PROCESS,
      secondary: null,
      detail: f.lastJob?.lastError ?? null,
    };
  }

  if (rec.status === 'TRANSCRIBED') {
    return {
      textKey: 'recordingProgress.transcribed',
      primary: ACTION_START_AI,
      secondary: null,
      // Die Namen wandern in die Zusammenfassung - danach ist es zu spät.
      tipKey: detail.participants.length > 0 ? 'recordingDetail.transcribedTip' : undefined,
    };
  }

  if (rec.status === 'RECORDED') {
    return {
      textKey: 'recordingProgress.notPlanned',
      primary: ACTION_PROCESS,
      secondary: ACTION_TRANSCRIBE,
    };
  }

  if (rec.status === 'DONE') {
    if (f.summaryDone) return null; // fertig - die Box hat nichts mehr zu sagen
    if (f.aiOff) {
      return {
        textKey: 'recordingProgress.aiOff',
        primary: ACTION_PROCESS,
        secondary: null,
      };
    }
    // Auftrag war erfolgreich, hat aber bewusst nichts erzeugt. Der Grund steht
    // im Auftrag - bisher nur in der Job-Tabelle im letzten Tab.
    return {
      textKey: 'recordingProgress.noContent',
      primary: null,
      secondary: ACTION_PROCESS,
      detail: f.lastJob?.lastError ?? null,
    };
  }

  return null;
}
