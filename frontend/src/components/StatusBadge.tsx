import { useI18n } from '../i18n';
import type { TranslationKey } from '../i18n';

/** Status, für die es eine Übersetzung gibt (Schlüssel unter `status.`). */
const KNOWN_STATUSES = [
  'STARTING', 'JOINED', 'RECORDING', 'RECONNECTING', 'STOPPED', 'FAILED',
  'FINALIZING', 'RECORDED', 'PROCESSING', 'TRANSCRIBED', 'DONE', 'DISCARDED',
  'TRANSCODING', 'READY', 'EMPTY', 'PENDING', 'RUNNING',
];

const STYLES: Record<string, { color: string; pulse?: boolean }> = {
  STARTING: { color: 'gray' },
  JOINED: { color: 'green' },
  RECORDING: { color: 'red', pulse: true },
  RECONNECTING: { color: 'orange' },
  STOPPED: { color: 'gray' },
  FAILED: { color: 'red' },
  FINALIZING: { color: 'orange' },
  RECORDED: { color: 'blue' },
  PROCESSING: { color: 'orange', pulse: true },
  TRANSCRIBED: { color: 'blue' },
  DONE: { color: 'green' },
  DISCARDED: { color: 'gray' },
  TRANSCODING: { color: 'orange' },
  READY: { color: 'green' },
  EMPTY: { color: 'gray' },
  PENDING: { color: 'gray' },
  RUNNING: { color: 'orange', pulse: true },
};

export default function StatusBadge({ status }: { status: string }) {
  const { t } = useI18n();
  const style = STYLES[status] ?? { color: 'gray' };
  const classes = `badge badge-${style.color}${style.pulse ? ' badge-pulse' : ''}`;
  // Unbekannte Status (z.B. aus einer neueren Backend-Version) unverändert zeigen
  const label = KNOWN_STATUSES.includes(status)
    ? t(`status.${status}` as TranslationKey)
    : status;
  return <span className={classes}>{label}</span>;
}
