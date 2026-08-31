import { useI18n } from '../i18n';
import type { NextAction, NextStep } from '../pages/recordingProgress';

/**
 * Die Box „Wie geht es weiter?" über der Tab-Leiste.
 *
 * Sie sagt in Klartext, was der Zustand ist und was als Nächstes passiert – von
 * allein oder auf Klick – und bietet **genau einen** primären Knopf. Ein
 * zweiter Weg steht als Textlink daneben, nicht als gleichrangiger Knopf: Sonst
 * wären wieder zwei Angebote gleich wichtig, und genau das war das Problem der
 * flachen Aktionsleiste.
 *
 * Läuft gerade etwas, gibt es keinen Knopf – nur den Zustand und, wenn bekannt,
 * seit wann. Bewusst keine Restzeitschätzung: Die Dauer skaliert mit der
 * Audiolänge, und eine falsche Zusage ist schlechter als keine.
 */
export default function NextStepCard({
  next,
  busy,
  onAction,
}: {
  next: NextStep;
  /** Eine Aktion läuft gerade – alle Knöpfe sperren. */
  busy: boolean;
  onAction: (action: NextAction) => void;
}) {
  const { t } = useI18n();

  const attempt = Number(next.vars?.attempt ?? 1);
  const max = Number(next.vars?.max ?? 0);
  const sinceLabel = next.sinceIso ? elapsedLabel(next.sinceIso, t) : null;

  return (
    <section className="card next-step">
      <h2 className="next-step-heading">{t('recordingProgress.heading')}</h2>
      <p className="next-step-text">
        {t(next.textKey, next.vars)}
        {sinceLabel && <span className="muted"> {sinceLabel}</span>}
      </p>

      {/* Zweiter Versuch ist eine Information für sich: Es lief schon einmal schief. */}
      {next.sinceIso && attempt > 1 && (
        <p className="muted next-step-detail">
          {t('recordingProgress.attemptNote', { attempt, max })}
        </p>
      )}

      {next.detail && (
        <p className="next-step-detail">
          <span className="meta-label">{t('recordingProgress.reasonLabel')}</span>{' '}
          <span className="next-step-reason">{next.detail}</span>
        </p>
      )}

      {next.tipKey && <p className="muted next-step-detail">{t(next.tipKey)}</p>}

      {(next.primary || next.secondary) && (
        <div className="next-step-actions">
          {next.primary && (
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy}
              onClick={() => onAction(next.primary!.action)}
            >
              {busy ? t('recordingDetail.starting') : t(next.primary.labelKey)}
            </button>
          )}
          {next.secondary && (
            <span className="next-step-secondary">
              {next.primary && <>{t('recordingProgress.orLabel')} </>}
              <button
                type="button"
                className="link-button"
                disabled={busy}
                onClick={() => onAction(next.secondary!.action)}
              >
                {/* Ohne primären Knopf ist der Nebenweg der einzige Ausweg und
                    braucht eine Beschriftung, die für sich steht. */}
                {next.primary
                  ? t(next.secondary.labelKey)
                  : t('recordingProgress.anywayLabel')}
              </button>
            </span>
          )}
        </div>
      )}
    </section>
  );
}

/**
 * „seit 4 Min." – aus dem Startzeitpunkt. Unter einer Minute gibt es keine
 * sinnvolle Zahl, dann sagt es das auch so.
 */
function elapsedLabel(sinceIso: string, t: ReturnType<typeof useI18n>['t']): string | null {
  const started = new Date(sinceIso).getTime();
  if (Number.isNaN(started)) return null;
  const minutes = Math.floor((Date.now() - started) / 60_000);
  return minutes < 1
    ? t('recordingProgress.runningSinceShort')
    : t('recordingProgress.runningSince', { minutes });
}
