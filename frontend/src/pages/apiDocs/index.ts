import type { Language } from '../../i18n';
import { apiDocsDe } from './de';
import { apiDocsEn } from './en';

/**
 * Inhalte des API-Hilfebereichs als Datenstruktur – bewusst nicht im
 * i18n-Wörterbuch: Dort liegen kurze Bedienungstexte, hier ganze Tabellen mit
 * Endpunkten, Beispielen und Antworten. Als Struktur bleiben sie zweisprachig
 * pflegbar und die Seite muss sie nur noch darstellen.
 */
export interface ApiParamDoc {
  name: string;
  description: string;
}

export interface ApiEndpointDoc {
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  path: string;
  summary: string;
  params?: ApiParamDoc[];
  /** curl-Beispiel; `$BBB` und `$KEY` sind im Schnellstart gesetzt. */
  example?: string;
  /** Gekürzte Beispielantwort. */
  response?: string;
}

export interface ApiDocSection {
  id: string;
  title: string;
  intro?: string;
  endpoints: ApiEndpointDoc[];
}

export interface ApiDocs {
  quickstart: {
    intro: string;
    example: string;
    baseUrlLabel: string;
  };
  auth: {
    title: string;
    text: string;
    notes: string[];
  };
  sections: ApiDocSection[];
  errors: {
    title: string;
    intro: string;
    rows: { code: string; meaning: string }[];
    example: string;
  };
}

export function getApiDocs(language: Language): ApiDocs {
  return language === 'en' ? apiDocsEn : apiDocsDe;
}
