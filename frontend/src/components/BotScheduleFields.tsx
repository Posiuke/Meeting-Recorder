import { useI18n } from '../i18n';
import type { translate } from '../i18n';
import type { BotScheduleRequest, BotScheduleView, Weekday } from '../types';

/** Wochentage in Kalenderreihenfolge (Montag zuerst, wie ISO und Java). */
export const WEEKDAYS: Weekday[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
];

/** Zeitzone des Browsers – in ihr denkt der Nutzer seine Termine. */
export const browserTimeZone = (): string => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Berlin';
  } catch {
    return 'Europe/Berlin';
  }
};

/** Java schreibt `LocalTime` als „HH:mm:ss" – angezeigt und bearbeitet wird „HH:mm". */
export const hhmm = (time: string | null | undefined): string => (time ? time.slice(0, 5) : '');

/** Leerer, ausgeschalteter Zeitplan mit der üblichen Vorbelegung. */
export const emptySchedule = (): BotScheduleRequest => ({
  enabled: false,
  days: ['MONDAY', 'WEDNESDAY', 'FRIDAY'],
  start: '09:00',
  end: '10:00',
  timeZone: browserTimeZone(),
});

/** Zeitplan einer gespeicherten Vorlage als bearbeitbarer Stand. */
export const scheduleRequestOf = (view: BotScheduleView | null | undefined): BotScheduleRequest => {
  if (!view || (!view.enabled && view.days.length === 0 && !view.start)) return emptySchedule();
  return {
    enabled: view.enabled,
    days: WEEKDAYS.filter((d) => view.days.includes(d)),
    start: hhmm(view.start) || '09:00',
    end: hhmm(view.end) || '10:00',
    timeZone: view.timeZone || browserTimeZone(),
  };
};

/** Kurzform „Mo, Mi, Fr · 09:00–10:00" für die Vorlagenliste. */
export const scheduleSummary = (schedule: BotScheduleView, t: typeof translate): string => {
  const days = WEEKDAYS.filter((d) => schedule.days.includes(d))
    .map((d) => t(`botTemplates.schedule.dayShort.${d}`))
    .join(', ');
  const zone = schedule.timeZone && schedule.timeZone !== browserTimeZone()
    ? ` (${schedule.timeZone})`
    : '';
  return `${days} · ${hhmm(schedule.start)}–${hhmm(schedule.end)}${zone}`;
};

interface BotScheduleFieldsProps {
  value: BotScheduleRequest;
  disabled?: boolean;
  onChange: (next: BotScheduleRequest) => void;
}

/**
 * Zeitplan einer Bot-Vorlage: an den gewählten Wochentagen tritt der Bot zur
 * Startzeit selbst bei und verlässt den Raum zur Endzeit.
 */
export default function BotScheduleFields({ value, disabled, onChange }: BotScheduleFieldsProps) {
  const { t } = useI18n();
  const off = disabled || !value.enabled;
  const overnight = !!value.start && !!value.end && value.end <= value.start;

  const toggleDay = (day: Weekday) => {
    const days = value.days.includes(day)
      ? value.days.filter((d) => d !== day)
      : WEEKDAYS.filter((d) => d === day || value.days.includes(d));
    onChange({ ...value, days });
  };

  return (
    <fieldset className="schedule-fields">
      <legend>{t('botTemplates.schedule.legend')}</legend>
      <label className="checkbox-field">
        <input
          type="checkbox"
          checked={value.enabled}
          disabled={disabled}
          onChange={(e) => onChange({ ...value, enabled: e.target.checked })}
        />
        {t('botTemplates.schedule.enabled')}
      </label>

      <div className="weekday-toggle" role="group" aria-label={t('botTemplates.schedule.days')}>
        {WEEKDAYS.map((day) => (
          <label
            key={day}
            className={`weekday-chip${value.days.includes(day) ? ' active' : ''}${off ? ' disabled' : ''}`}
            title={t(`botTemplates.schedule.dayLong.${day}`)}
          >
            <input
              type="checkbox"
              checked={value.days.includes(day)}
              disabled={off}
              onChange={() => toggleDay(day)}
            />
            {t(`botTemplates.schedule.dayShort.${day}`)}
          </label>
        ))}
      </div>

      <div className="form-row">
        <div className="form-field">
          <label htmlFor="bot-schedule-start">{t('botTemplates.schedule.start')}</label>
          <input
            id="bot-schedule-start"
            type="time"
            value={value.start ?? ''}
            disabled={off}
            required={value.enabled}
            onChange={(e) => onChange({ ...value, start: e.target.value || null })}
          />
        </div>
        <div className="form-field">
          <label htmlFor="bot-schedule-end">{t('botTemplates.schedule.end')}</label>
          <input
            id="bot-schedule-end"
            type="time"
            value={value.end ?? ''}
            disabled={off}
            required={value.enabled}
            onChange={(e) => onChange({ ...value, end: e.target.value || null })}
          />
        </div>
      </div>
      <span className="muted schedule-hint">
        {overnight && value.end !== value.start
          ? t('botTemplates.schedule.overnightHint')
          : t('botTemplates.schedule.hint')}{' '}
        {t('botTemplates.schedule.timeZone', { zone: value.timeZone ?? browserTimeZone() })}
      </span>
    </fieldset>
  );
}

/** Ist der Zeitplan so ausgefüllt, dass der Server ihn annimmt? */
export const scheduleValid = (schedule: BotScheduleRequest): boolean =>
  !schedule.enabled ||
  (schedule.days.length > 0 && !!schedule.start && !!schedule.end && schedule.start !== schedule.end);
