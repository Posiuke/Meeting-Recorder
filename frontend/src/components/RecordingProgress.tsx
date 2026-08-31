import { useI18n } from '../i18n';
import type { TranslationKey } from '../i18n';
import type { ProgressStep, StepKey, StepState } from '../pages/recordingProgress';

const STEP_LABELS: Record<StepKey, TranslationKey> = {
  recording: 'recordingProgress.stepRecording',
  transcript: 'recordingProgress.stepTranscript',
  summary: 'recordingProgress.stepSummary',
};

const STATE_LABELS: Record<StepState, TranslationKey> = {
  done: 'recordingProgress.stateDone',
  running: 'recordingProgress.stateRunning',
  waiting: 'recordingProgress.stateWaiting',
  failed: 'recordingProgress.stateFailed',
  skipped: 'recordingProgress.stateSkipped',
  open: 'recordingProgress.stateOpen',
};

/** Zeichen je Zustand – Farbe allein trägt die Aussage nicht. */
const STATE_MARKS: Record<StepState, string> = {
  done: '✓',
  running: '●',
  waiting: '·',
  failed: '✕',
  skipped: '–',
  open: '·',
};

/**
 * Fortschrittskette einer Aufnahme: Aufnahme → Transkript → Zusammenfassung.
 *
 * Drei Stufen und nicht vier: Die Transkript-Glättung ist ein Zwischenschritt,
 * den der Nutzer nicht steuert und dessen Ausfall die Auswertung nicht stoppt –
 * als eigene Stufe würde sie mehr verwirren als erklären.
 *
 * Der Zustand steht als Zeichen UND als Wort an jeder Stufe. Farbe allein wäre
 * für Farbfehlsichtige keine Information, und ein „✓" ohne Wort ist im
 * Screenreader nur ein Häkchen.
 */
export default function RecordingProgress({ steps }: { steps: ProgressStep[] }) {
  const { t } = useI18n();
  return (
    <ol className="progress-chain">
      {steps.map((step) => (
        <li key={step.key} className={`progress-step progress-${step.state}`}>
          <span className="progress-mark" aria-hidden="true">
            {STATE_MARKS[step.state]}
          </span>
          <span className="progress-text">
            <span className="progress-name">{t(STEP_LABELS[step.key])}</span>
            <span className="progress-state">{t(STATE_LABELS[step.state])}</span>
          </span>
        </li>
      ))}
    </ol>
  );
}
