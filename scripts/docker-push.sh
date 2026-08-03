#!/usr/bin/env bash
# Baut das kombinierte App-Image (Frontend + Backend) und pusht es in die
# konfigurierte Registry.
#
# Konfiguration (in .env im Repo-Root oder als Umgebungsvariablen):
#   DOCKER_NAMESPACE   Pflicht. Docker-Hub-Benutzer/Organisation, z.B. "michaelmaciuch"
#   DOCKER_REGISTRY    Optional. Registry-Host (leer = Docker Hub).
#                      Fuer interne Registry z.B. "registry.example.com"
#   IMAGE_TAG          Optional. Zusaetzlicher Tag (Default: kurzer Git-Hash).
#   DOCKERHUB_USERNAME Optional. Fuer automatischen Login (sonst: vorher `docker login`).
#   DOCKERHUB_TOKEN    Optional. Access-Token/Passwort fuer den automatischen Login.
#   DOCKER_SHORT_DESCRIPTION  Optional. Kurzbeschreibung (max. 100 Zeichen) fuer Docker Hub.
#
# Docker-Hub-Beschreibung:
#   Nur bei Docker Hub (DOCKER_REGISTRY leer) und gesetzten DOCKERHUB_USERNAME/
#   DOCKERHUB_TOKEN wird nach dem Push die Repo-Beschreibung aktualisiert:
#   Langbeschreibung aus DOCKERHUB.md, Kurzbeschreibung aus DOCKER_SHORT_DESCRIPTION.
#   Benoetigt `curl` und `jq`; fehlt etwas, wird der Schritt uebersprungen (Push bleibt ok).
#
# Aufruf:  ./scripts/docker-push.sh [tag]
#   Ohne Argument werden "latest" und der Git-Hash getaggt und gepusht.
#   Mit Argument (z.B. "v3.0.0") zusaetzlich dieser Tag.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# .env laden (falls vorhanden), ohne bereits gesetzte Variablen zu ueberschreiben
if [[ -f .env ]]; then
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    [[ -n "${!key:-}" ]] || export "$key=$value"
  done < <(grep -v '^\s*#' .env | grep '=')
fi

DOCKER_NAMESPACE="${DOCKER_NAMESPACE:-}"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-}"
EXTRA_TAG="${1:-${IMAGE_TAG:-}}"
GIT_TAG="$(git rev-parse --short HEAD 2>/dev/null || echo dev)"

if [[ -z "$DOCKER_NAMESPACE" ]]; then
  echo "FEHLER: DOCKER_NAMESPACE ist nicht gesetzt (in .env eintragen, z.B. DOCKER_NAMESPACE=meinbenutzer)" >&2
  exit 1
fi

# Registry-Praefix bauen: docker.io braucht keinen Host-Anteil
PREFIX="$DOCKER_NAMESPACE"
if [[ -n "$DOCKER_REGISTRY" ]]; then
  PREFIX="$DOCKER_REGISTRY/$DOCKER_NAMESPACE"
fi

APP_IMAGE="$PREFIX/bbb-recorder"
REPO_SLUG="$DOCKER_NAMESPACE/bbb-recorder"

# Aktualisiert Kurz- und Langbeschreibung des Docker-Hub-Repos ueber die Hub-API.
# Nicht-fatal: schlaegt der Schritt fehl, bleibt der Image-Push davon unberuehrt.
update_dockerhub_description() {
  if [[ -n "$DOCKER_REGISTRY" ]]; then
    return 0  # nur Docker Hub, nicht fuer eigene Registries
  fi
  local desc_file="$REPO_ROOT/DOCKERHUB.md"
  if [[ -z "${DOCKERHUB_USERNAME:-}" || -z "${DOCKERHUB_TOKEN:-}" ]]; then
    echo ">> Hinweis: DOCKERHUB_USERNAME/DOCKERHUB_TOKEN fehlen - Beschreibung wird nicht aktualisiert."
    return 0
  fi
  if [[ ! -f "$desc_file" ]]; then
    echo ">> Hinweis: $desc_file fehlt - Beschreibung wird nicht aktualisiert."
    return 0
  fi
  if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    echo ">> Hinweis: curl und jq werden fuer die Beschreibung benoetigt - uebersprungen."
    return 0
  fi

  local short="${DOCKER_SHORT_DESCRIPTION:-BBB-Recorder: Aufnahme-Bot fuer BigBlueButton-Meetings mit Transkript & KI-Zusammenfassung.}"
  short="${short:0:100}"

  echo ">> Aktualisiere Docker-Hub-Beschreibung ($REPO_SLUG)"
  local token
  token="$(curl -s -H 'Content-Type: application/json' \
      -d "$(jq -n --arg u "$DOCKERHUB_USERNAME" --arg p "$DOCKERHUB_TOKEN" '{username:$u,password:$p}')" \
      https://hub.docker.com/v2/users/login/ | jq -r '.token // empty' || true)"
  if [[ -z "$token" ]]; then
    echo ">> WARNUNG: Docker-Hub-Login fuer die Beschreibung fehlgeschlagen - uebersprungen." >&2
    return 0
  fi

  local payload http_code
  payload="$(jq -n --arg d "$short" --rawfile f "$desc_file" \
      '{description:$d, full_description:$f}')"
  http_code="$(curl -s -o /dev/null -w '%{http_code}' -X PATCH \
      -H "Authorization: JWT $token" -H 'Content-Type: application/json' \
      -d "$payload" \
      "https://hub.docker.com/v2/repositories/$REPO_SLUG/" || true)"
  if [[ "$http_code" == "200" ]]; then
    echo ">> Docker-Hub-Beschreibung aktualisiert."
  else
    echo ">> WARNUNG: Beschreibung-Update lieferte HTTP ${http_code:-?}." >&2
  fi
}

# Optionaler automatischer Login (sonst muss vorher `docker login` gelaufen sein)
if [[ -n "${DOCKERHUB_USERNAME:-}" && -n "${DOCKERHUB_TOKEN:-}" ]]; then
  echo ">> docker login als $DOCKERHUB_USERNAME"
  echo "$DOCKERHUB_TOKEN" | docker login ${DOCKER_REGISTRY:+"$DOCKER_REGISTRY"} \
      --username "$DOCKERHUB_USERNAME" --password-stdin
fi

# Build-Kontext ist das Repo-Root, Dockerfile liegt unter backend/
echo ">> Baue App-Image ($APP_IMAGE) - Frontend + Backend"
docker build -f backend/Dockerfile -t "$APP_IMAGE:latest" -t "$APP_IMAGE:$GIT_TAG" .

if [[ -n "$EXTRA_TAG" ]]; then
  docker tag "$APP_IMAGE:latest" "$APP_IMAGE:$EXTRA_TAG"
fi

echo ">> Pushe Image"
docker push "$APP_IMAGE:latest"
docker push "$APP_IMAGE:$GIT_TAG"
if [[ -n "$EXTRA_TAG" ]]; then
  docker push "$APP_IMAGE:$EXTRA_TAG"
fi

update_dockerhub_description

echo
echo "Fertig. Gepusht:"
echo "  $APP_IMAGE  (latest, $GIT_TAG${EXTRA_TAG:+, $EXTRA_TAG})"
