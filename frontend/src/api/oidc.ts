import { api } from './client';

/** Anmeldeknopf eines OIDC-Anbieters (öffentlich, für die Anmeldeseite). */
export interface LoginProvider {
  slug: string;
  displayName: string;
}

export interface LoginOptions {
  providers: LoginProvider[];
  /** Passwortformular nur auf Klick – die Knöpfe stehen im Vordergrund. */
  localLoginHidden: boolean;
}

export interface OidcProviderView {
  id: string;
  slug: string;
  displayName: string;
  enabled: boolean;
  issuerUri: string;
  clientId: string;
  hasClientSecret: boolean;
  scopes: string;
  usernameClaim: string;
  groupsClaim: string | null;
  adminValues: string | null;
  allowedValues: string | null;
  /** Beim Anbieter einzutragende Redirect-URI. */
  redirectUri: string;
}

export interface OidcProviderRequest {
  slug?: string;
  displayName: string;
  enabled: boolean;
  issuerUri: string;
  clientId: string;
  /** Leer = vorhandenes Secret behalten. */
  clientSecret: string;
  scopes: string;
  usernameClaim: string;
  groupsClaim: string;
  adminValues: string;
  allowedValues: string;
}

export interface OidcTestResult {
  ok: boolean;
  message: string;
}

export function fetchLoginOptions(): Promise<LoginOptions> {
  return api<LoginOptions>('/api/auth/options');
}

/** Startadresse der Anmeldung – eine Browser-Navigation, kein fetch. */
export function oidcStartUrl(slug: string, next: string): string {
  return `/api/auth/oidc/${encodeURIComponent(slug)}/start?next=${encodeURIComponent(next)}`;
}

export function listOidcProviders(): Promise<OidcProviderView[]> {
  return api<OidcProviderView[]>('/api/admin/oidc-providers');
}

export function createOidcProvider(body: OidcProviderRequest): Promise<OidcProviderView> {
  return api<OidcProviderView>('/api/admin/oidc-providers', { method: 'POST', body });
}

export function updateOidcProvider(id: string, body: OidcProviderRequest): Promise<OidcProviderView> {
  return api<OidcProviderView>(`/api/admin/oidc-providers/${id}`, { method: 'PUT', body });
}

export function deleteOidcProvider(id: string): Promise<void> {
  return api<void>(`/api/admin/oidc-providers/${id}`, { method: 'DELETE' });
}

export function testOidcProvider(id: string): Promise<OidcTestResult> {
  return api<OidcTestResult>(`/api/admin/oidc-providers/${id}/test`, { method: 'POST' });
}
