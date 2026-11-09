#!/usr/bin/env bash
# Baut das kombinierte App-Image (Frontend + Backend) und pusht es in die
# konfigurierte Registry - auf Docker Hub als <namespace>/meeting-recorder.
#
# Konfiguration (in .env im Repo-Root oder als Umgebungsvariablen):
#   DOCKER_NAMESPACE   Pflicht. Docker-Hub-Benutzer/Organisation, z.B. "posiuke"
#   DOCKER_REGISTRY    Optional. Registry-Host (leer = Docker Hub).
#                      Fuer interne Registry z.B. "registry.example.com"
#   IMAGE_TAG          Optional. Zusaetzlicher Tag.
#   DOCKERHUB_USERNAME Optional. Fuer automatischen Login (sonst: vorher `docker login`).
#   DOCKERHUB_TOKEN    Optional. Access-Token/Passwort fuer den automatischen Login.
#   DOCKER_SHORT_DESCRIPTION  Optional. Kurzbeschreibung (max. 100 Zeichen) fuer Docker Hub.
#
# Tags: Immer "latest", der kurze Git-Hash und die Version aus backend/pom.xml
# (z.B. "1.0.0"). Ein Argument bzw. IMAGE_TAG kommt als weiterer Tag hinzu.
# Dazu jeweils dieselben Tags mit Endung "-slim" fuer das schlanke Image ohne
# Chromium (nur fuer Betrieb ohne BBB_ON, siehe backend/Dockerfile).
#
# Docker-Hub-Beschreibung:
#   Nur bei Docker Hub (DOCKER_REGISTRY leer) und gesetzten DOCKERHUB_USERNAME/
#   DOCKERHUB_TOKEN wird nach dem Push die Repo-Beschreibung aktualisiert:
#   Langbeschreibung aus DOCKERHUB.md, Kurzbeschreibung aus DOCKER_SHORT_DESCRIPTION.
#   Benoetigt `curl` und `jq`; fehlt etwas, wird der Schritt uebersprungen (Push bleibt ok).
#
# Das fruehere Image <namespace>/bbb-recorder wird nicht mehr gebaut; den
# Umzugshinweis dort setzt scripts/dockerhub-legacy-notice.sh.
#
# Aufruf:  ./scripts/docker-push.sh [tag]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
# shellcheck source=lib/dockerhub.sh
source "$REPO_ROOT/scripts/lib/dockerhub.sh"
load_env "$REPO_ROOT"

IMAGE_NAME="meeting-recorder"
DOCKER_NAMESPACE="${DOCKER_NAMESPACE:-}"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-}"
EXTRA_TAG="${1:-${IMAGE_TAG:-}}"
GIT_TAG="$(git rev-parse --short HEAD 2>/dev/null || echo dev)"
# Projektversion: das erste <version> nach <artifactId>bbb-bot-backend</artifactId>
VERSION_TAG="$(sed -n '/<artifactId>bbb-bot-backend<\/artifactId>/,/<\/version>/s:.*<version>\(.*\)</version>.*:\1:p' backend/pom.xml | head -n1)"

if [[ -z "$DOCKER_NAMESPACE" ]]; then
  echo "FEHLER: DOCKER_NAMESPACE ist nicht gesetzt (in .env eintragen, z.B. DOCKER_NAMESPACE=meinbenutzer)" >&2
  exit 1
fi
if [[ -z "$VERSION_TAG" ]]; then
  echo "FEHLER: Version aus backend/pom.xml nicht lesbar" >&2
  exit 1
fi

# Registry-Praefix bauen: docker.io braucht keinen Host-Anteil
PREFIX="$DOCKER_NAMESPACE"
if [[ -n "$DOCKER_REGISTRY" ]]; then
  PREFIX="$DOCKER_REGISTRY/$DOCKER_NAMESPACE"
fi

APP_IMAGE="$PREFIX/$IMAGE_NAME"
REPO_SLUG="$DOCKER_NAMESPACE/$IMAGE_NAME"

TAGS=(latest "$GIT_TAG" "$VERSION_TAG")
if [[ -n "$EXTRA_TAG" && ! " ${TAGS[*]} " =~ " $EXTRA_TAG " ]]; then
  TAGS+=("$EXTRA_TAG")
fi

# Optionaler automatischer Login (sonst muss vorher `docker login` gelaufen sein)
if [[ -n "${DOCKERHUB_USERNAME:-}" && -n "${DOCKERHUB_TOKEN:-}" ]]; then
  echo ">> docker login als $DOCKERHUB_USERNAME"
  echo "$DOCKERHUB_TOKEN" | docker login ${DOCKER_REGISTRY:+"$DOCKER_REGISTRY"} \
      --username "$DOCKERHUB_USERNAME" --password-stdin
fi

# Build-Kontext ist das Repo-Root, Dockerfile liegt unter backend/
echo ">> Baue App-Image ($APP_IMAGE, Version $VERSION_TAG) - Frontend + Backend"
TAG_ARGS=()
for tag in "${TAGS[@]}"; do
  TAG_ARGS+=(-t "$APP_IMAGE:$tag")
done
LABEL_ARGS=(
    --label "org.opencontainers.image.title=Meeting Recorder"
    --label "org.opencontainers.image.version=$VERSION_TAG"
    --label "org.opencontainers.image.revision=$GIT_TAG"
    --label "org.opencontainers.image.source=https://github.com/Posiuke/Meeting-Recorder"
)
docker build -f backend/Dockerfile --target full "${LABEL_ARGS[@]}" "${TAG_ARGS[@]}" .

echo ">> Baue schlankes Image (ohne Chromium, Tags mit -slim)"
SLIM_TAG_ARGS=()
for tag in "${TAGS[@]}"; do
  SLIM_TAG_ARGS+=(-t "$APP_IMAGE:$tag-slim")
done
docker build -f backend/Dockerfile --target slim "${LABEL_ARGS[@]}" "${SLIM_TAG_ARGS[@]}" .

echo ">> Pushe Images"
for tag in "${TAGS[@]}"; do
  docker push "$APP_IMAGE:$tag"
  docker push "$APP_IMAGE:$tag-slim"
done

if [[ -z "$DOCKER_REGISTRY" ]]; then
  dockerhub_update_description "$REPO_SLUG" \
    "${DOCKER_SHORT_DESCRIPTION:-Meeting Recorder: recordings, transcripts & AI summaries. Optional BigBlueButton bots.}" \
    "$REPO_ROOT/DOCKERHUB.md" || true
fi

echo
echo "Fertig. Gepusht:"
echo "  $APP_IMAGE  ($(IFS=', '; echo "${TAGS[*]}"), jeweils auch -slim)"
