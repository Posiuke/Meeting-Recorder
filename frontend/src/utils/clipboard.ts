/**
 * Text in die Zwischenablage legen.
 *
 * Die Clipboard-API gibt es nur im sicheren Kontext (HTTPS oder localhost) –
 * im Intranet läuft die Anwendung aber gelegentlich auch über http. Deshalb der
 * Rückfall über ein unsichtbares Textfeld: `document.execCommand('copy')` ist
 * veraltet, funktioniert dort aber weiterhin und ist besser, als den Nutzer mit
 * einem toten Knopf stehen zu lassen.
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // weiter mit dem Rückfall
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
}
