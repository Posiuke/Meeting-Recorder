/**
 * Kleines Hilfe-Symbol (?) mit Erklärungs-Tooltip bei Hover oder Tastatur-Fokus.
 * Für kurze Hilfetexte neben Formularfeldern.
 */
export default function HelpTip({ text }: { text: string }) {
  return (
    <span className="help-tip" tabIndex={0} aria-label={text}>
      ?
      <span className="help-tip-bubble" role="tooltip">
        {text}
      </span>
    </span>
  );
}
