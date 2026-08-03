import { useState } from 'react';
import { copyToClipboard } from '../utils/clipboard';
import { useI18n } from '../i18n';

interface CopyButtonProps {
  value: string;
  className?: string;
}

/** Knopf, der einen Text in die Zwischenablage legt und das kurz bestätigt. */
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
      {copied ? t('common.copied') : t('common.copy')}
    </button>
  );
}
