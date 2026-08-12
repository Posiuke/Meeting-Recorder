import { useState } from 'react';
import { copyToClipboard } from '../utils/clipboard';
import { useI18n } from '../i18n';

interface CopyButtonProps {
  value: string;
  className?: string;
}

/**
 * Knopf, der einen Text in die Zwischenablage legt und das kurz bestätigt.
 *
 * Beide Beschriftungen liegen übereinander im selben Raster-Feld: Der Knopf ist
 * damit so breit wie das längere Wort und bleibt beim Wechsel auf „Kopiert"
 * gleich groß. Sonst springt die Breite – und daneben stehender Text (z.B. ein
 * Freigabe-Link) würde neu umbrechen, was aussieht, als hätte er sich geändert.
 */
export default function CopyButton({ value, className }: CopyButtonProps) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    if (await copyToClipboard(value)) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }
  };

  return (
    <button
      type="button"
      className={className ?? 'btn btn-ghost btn-sm'}
      onClick={() => void handleCopy()}
    >
      <span className="copy-labels">
        <span aria-hidden={copied} className={copied ? 'copy-label-off' : undefined}>
          {t('common.copy')}
        </span>
        <span aria-hidden={!copied} className={copied ? undefined : 'copy-label-off'}>
          {t('common.copied')}
        </span>
      </span>
    </button>
  );
}
