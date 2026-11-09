#!/usr/bin/env bash
# Setzt auf dem alten Docker-Hub-Repo posiuke/bbb-recorder den Umzugshinweis
# (Inhalt: docs/DOCKERHUB_BBB_RECORDER_DEPRECATED.md). Seit 1.0.0 erscheint
# das Image als posiuke/meeting-recorder; das alte Repo wird nicht mehr gepflegt.
#
# Braucht DOCKERHUB_USERNAME und DOCKERHUB_TOKEN (in .env oder als Umgebungs-
# variablen) sowie curl und jq. Beliebig oft wiederholbar.
#
# Aufruf:  ./scripts/dockerhub-legacy-notice.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/dockerhub.sh
source "$REPO_ROOT/scripts/lib/dockerhub.sh"
load_env "$REPO_ROOT"

LEGACY_SLUG="${LEGACY_SLUG:-posiuke/bbb-recorder}"

dockerhub_update_description "$LEGACY_SLUG" \
  "DEPRECATED - moved to posiuke/meeting-recorder. This image is no longer maintained." \
  "$REPO_ROOT/docs/DOCKERHUB_BBB_RECORDER_DEPRECATED.md"
