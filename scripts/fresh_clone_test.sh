#!/usr/bin/env bash
# Fresh-clone test (PLAN.md §18): clone the repository into a new temp directory and run what a reviewer runs, isolated
# from any local setup (its own Docker Compose project and PostgreSQL port).
#
#   ./scripts/fresh_clone_test.sh <repo-url> [branch-or-tag]
#
# Steps: clone -> .env with a free port -> ./mvnw clean verify -> ./scripts/run_all.sh starter ->
# ./scripts/repro_check.sh starter -> regenerated starter checksums == committed data/sample/checksums.sha256 ->
# sha256sum -c inside data/sample. The Compose project is removed at the end (volumes included).
# Env: FRESH_PORT (default 55432), KEEP=1 keeps the clone directory.
set -euo pipefail

URL="${1:?usage: fresh_clone_test.sh <repo-url> [branch-or-tag]}"
REF="${2:-}"
PORT="${FRESH_PORT:-55432}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pricing-sim-fresh-XXXXXX")"
PROJECT="pricingsim-fresh-$$"
export COMPOSE_PROJECT_NAME="${PROJECT}"
RESULTS=()

cleanup() {
  if [ -d "${WORK}/repo" ]; then
    (cd "${WORK}/repo" && docker compose -p "${PROJECT}" down -v --remove-orphans > /dev/null 2>&1) || true
  fi
  if [ "${KEEP:-0}" = "1" ]; then echo "Kept ${WORK}"; else rm -rf "${WORK}"; fi
}
trap cleanup EXIT

check() {  # check <name> <command...>
  local name="$1"; shift
  local started; started=$(date +%s)
  echo
  echo "==> ${name}"
  if "$@"; then
    RESULTS+=("PASS  ${name} ($(( $(date +%s) - started )) s)")
  else
    RESULTS+=("FAIL  ${name}")
    printf '%s\n' "${RESULTS[@]}"
    echo "FRESH-CLONE TEST FAILED at: ${name}"
    exit 1
  fi
}

clone() {
  if [ -n "${REF}" ]; then
    git clone --quiet --branch "${REF}" "${URL}" "${WORK}/repo"
  else
    git clone --quiet "${URL}" "${WORK}/repo"
  fi
}
configure() {
  cp .env.example .env
  sed -i.bak "s/^POSTGRES_PORT=.*/POSTGRES_PORT=${PORT}/" .env && rm -f .env.bak
  grep -q "^POSTGRES_PORT=${PORT}$" .env
}
checksums_match() {
  diff out/starter/checksums.sha256 data/sample/checksums.sha256
}
sample_verifies() {
  (cd data/sample && sha256sum -c --quiet checksums.sha256)
}

echo "Fresh clone of ${URL}${REF:+ (${REF})} into ${WORK}/repo; Compose project ${PROJECT}, port ${PORT}"
check "git clone" clone
cd "${WORK}/repo"
echo "Commit: $(git log -1 --format='%h %s')"
check ".env from .env.example (POSTGRES_PORT=${PORT})" configure
check "./mvnw clean verify" ./mvnw -q clean verify
check "./scripts/run_all.sh starter" env SKIP_BUILD=1 ./scripts/run_all.sh starter
check "./scripts/repro_check.sh starter" ./scripts/repro_check.sh starter
check "regenerated starter checksums == data/sample/checksums.sha256" checksums_match
check "sha256sum -c data/sample/checksums.sha256" sample_verifies

echo
printf '%s\n' "${RESULTS[@]}"
echo "FRESH-CLONE TEST PASSED"
