import { isValidElement, useEffect, useRef, useState, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useI18n } from '../i18n';

/**
 * Darstellung der Markdown-Inhalte aus dem Sprachmodell (Zusammenfassung und
 * geglättetes Transkript).
 *
 * Der GFM-Satz (remark-gfm) ist bewusst aktiviert: Modelle liefern regelmäßig
 * Tabellen, Aufgabenlisten und Durchstreichungen, die der Markdown-Kern allein
 * als rohe Pipe-Zeichen stehen lässt. Rohes HTML bleibt dagegen abgeschaltet
 * (kein rehype-raw) – der Text stammt aus einem Modell und kann vom Besitzer
 * frei bearbeitet werden, HTML gehört dort nicht hinein.
 *
 * Codeblöcke mit der Sprache `mermaid` werden als Diagramm gezeichnet. Das
 * greift nur, wenn im Auswertungs-Prompt ausdrücklich ein Mermaid-Diagramm
 * angefordert wurde; ohne solche Blöcke wird die Mermaid-Bibliothek nie geladen.
 */

/** Fortlaufende, eindeutige IDs für die von Mermaid erzeugten SVG-Elemente. */
let diagramCounter = 0;

type MermaidApi = Awaited<typeof import('mermaid')>['default'];

let mermaidLoader: Promise<MermaidApi> | null = null;

/**
 * Mermaid erst beim ersten Diagramm nachladen (eigener Vite-Chunk, gut 1 MB)
 * und genau einmal konfigurieren.
 *
 * `securityLevel: 'strict'` ist Pflicht, nicht Kosmetik: Beschriftungen kommen
 * aus Modellausgabe, HTML-Labels bleiben damit abgeschaltet. Die Farben sind
 * an die Oberfläche angeglichen – Mermaid kann keine CSS-Variablen lesen, die
 * Werte spiegeln daher `--accent`, `--accent-soft` und `--text` aus styles.css.
 */
function loadMermaid(): Promise<MermaidApi> {
  if (!mermaidLoader) {
    mermaidLoader = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        suppressErrorRendering: true,
        theme: 'base',
        fontFamily:
          "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
        themeVariables: {
          primaryColor: '#e8eef5',
          primaryTextColor: '#1f2933',
          primaryBorderColor: '#1e3a5f',
          secondaryColor: '#f4f6f9',
          tertiaryColor: '#ffffff',
          lineColor: '#67717f',
          fontSize: '14px',
        },
      });
      return mermaid;
    });
  }
  return mermaidLoader;
}

/**
 * Mermaid-Renderläufe werden serialisiert. Die Bibliothek arbeitet beim Zeichnen
 * mit gemeinsam genutzten DOM-Hilfselementen; mehrere gleichzeitig laufende
 * `render`-Aufrufe (mehrere Diagramme in einer Zusammenfassung) stören sich
 * sonst gegenseitig.
 */
let renderQueue: Promise<unknown> = Promise.resolve();

function renderDiagram(id: string, code: string): Promise<string> {
  const next = renderQueue
    .then(() => loadMermaid())
    .then((mermaid) => mermaid.render(id, code))
    .then((result) => result.svg);
  renderQueue = next.catch(() => undefined);
  return next;
}

function MermaidDiagram({ code }: { code: string }) {
  const { t } = useI18n();
  const [svg, setSvg] = useState('');
  const [failed, setFailed] = useState(false);
  const idRef = useRef('');
  if (!idRef.current) {
    diagramCounter += 1;
    idRef.current = `mermaid-diagram-${diagramCounter}`;
  }

  useEffect(() => {
    let cancelled = false;
    setSvg('');
    setFailed(false);
    renderDiagram(idRef.current, code)
      .then((result) => {
        if (!cancelled) setSvg(result);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [code]);

  // Fehlerhafte Diagramm-Syntax darf die Zusammenfassung nicht verschlucken:
  // Der Quelltext bleibt sichtbar, damit der Besitzer ihn korrigieren kann.
  if (failed) {
    return (
      <div className="mermaid-block mermaid-failed">
        <p className="muted">{t('markdown.mermaidFailed')}</p>
        <pre>
          <code>{code}</code>
        </pre>
      </div>
    );
  }

  if (!svg) {
    return <p className="muted mermaid-loading">{t('markdown.mermaidLoading')}</p>;
  }

  // Das SVG stammt aus Mermaid im Strict-Modus (keine HTML-Labels).
  return <div className="mermaid-block" dangerouslySetInnerHTML={{ __html: svg }} />;
}

/**
 * Liefert den Inhalt eines ```mermaid-Blocks, sonst null. Geprüft wird das
 * `code`-Kind des `pre`-Elements, das react-markdown mit `language-mermaid`
 * auszeichnet – Inline-Code trägt nie eine Sprachklasse.
 */
function mermaidSource(children: ReactNode): string | null {
  const child = Array.isArray(children) ? children[0] : children;
  if (!isValidElement(child)) return null;
  const props = child.props as { className?: string; children?: ReactNode };
  if (!/(?:^|\s)language-mermaid(?:\s|$)/.test(props.className ?? '')) return null;
  const source = Array.isArray(props.children)
    ? props.children.filter((part): part is string => typeof part === 'string').join('')
    : typeof props.children === 'string'
      ? props.children
      : '';
  const trimmed = source.replace(/\s+$/, '');
  return trimmed === '' ? null : trimmed;
}

const components: Components = {
  pre({ node: _node, children, ...props }) {
    const diagram = mermaidSource(children);
    if (diagram !== null) return <MermaidDiagram code={diagram} />;
    return <pre {...props}>{children}</pre>;
  },
  // Breite Tabellen scrollen in ihrem eigenen Rahmen, damit die Seite nicht
  // waagerecht wandert.
  table({ node: _node, children, ...props }) {
    return (
      <div className="markdown-table-wrap">
        <table {...props}>{children}</table>
      </div>
    );
  },
  a({ node: _node, children, ...props }) {
    return (
      <a {...props} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    );
  },
};

interface MarkdownProps {
  /** Markdown-Quelltext. */
  children: string;
  /** Zusätzliche Klasse am Rahmen-Element. */
  className?: string;
}

export default function Markdown({ children, className }: MarkdownProps) {
  return (
    <div className={className ? `markdown-body ${className}` : 'markdown-body'}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
