#!/usr/bin/env bash
# Gemeinsame Helfer fuer die Docker-Hub-Skripte (wird per `source` eingebunden).

# .env im Repo-Root laden, ohne bereits gesetzte Variablen zu ueberschreiben.
load_env() {
  local env_file="$1/.env"
  [[ -f "$env_file" ]] || return 0
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    [[ -n "${!key:-}" ]] || export "$key=$value"
  done < <(grep -v '^\s*#' "$env_file" | grep '=')
}

# Aktualisiert Kurz- und Langbeschreibung eines Docker-Hub-Repos ueber die Hub-API.
#   dockerhub_update_description <namespace/repo> <kurzbeschreibung> <markdown-datei>
# Nicht-fatal: schlaegt der Schritt fehl, gibt die Funktion 1 zurueck und meldet
# den Grund - ein vorheriger Image-Push bleibt davon unberuehrt.
dockerhub_update_description() {
  local slug="$1" short="${2:0:100}" desc_file="$3"
  if [[ -z "${DOCKERHUB_USERNAME:-}" || -z "${DOCKERHUB_TOKEN:-}" ]]; then
    echo ">> Hinweis: DOCKERHUB_USERNAME/DOCKERHUB_TOKEN fehlen - Beschreibung von $slug wird nicht aktualisiert."
    return 1
  fi
  if [[ ! -f "$desc_file" ]]; then
    echo ">> Hinweis: $desc_file fehlt - Beschreibung von $slug wird nicht aktualisiert."
    return 1
  fi
  if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    echo ">> Hinweis: curl und jq werden fuer die Beschreibung benoetigt - uebersprungen."
    return 1
  fi

  echo ">> Aktualisiere Docker-Hub-Beschreibung ($slug)"
  local token
  token="$(curl -s -H 'Content-Type: application/json' \
      -d "$(jq -n --arg u "$DOCKERHUB_USERNAME" --arg p "$DOCKERHUB_TOKEN" '{username:$u,password:$p}')" \
      https://hub.docker.com/v2/users/login/ | jq -r '.token // empty' || true)"
  if [[ -z "$token" ]]; then
    echo ">> WARNUNG: Docker-Hub-Login fuer die Beschreibung fehlgeschlagen - uebersprungen." >&2
    return 1
  fi

  local payload http_code
  payload="$(jq -n --arg d "$short" --rawfile f "$desc_file" \
      '{description:$d, full_description:$f}')"
  http_code="$(curl -s -o /dev/null -w '%{http_code}' -X PATCH \
      -H "Authorization: JWT $token" -H 'Content-Type: application/json' \
      -d "$payload" \
      "https://hub.docker.com/v2/repositories/$slug/" || true)"
  if [[ "$http_code" == "200" ]]; then
    echo ">> Docker-Hub-Beschreibung von $slug aktualisiert."
  else
    echo ">> WARNUNG: Beschreibung-Update von $slug lieferte HTTP ${http_code:-?}." >&2
    return 1
  fi
}
