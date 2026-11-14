#!/usr/bin/env bash
# Full pipeline (PLAN.md §17): docker up -> build -> generate -> load -> validate -> detect -> evaluate -> export.
#
#   ./scripts/run_all.sh [tiny|starter|full] [extra generate options, e.g. --seed 7]
#
# Each step is a separate CLI run, so every step's exit code is checked; validate exits non-zero on any structural
# failure. Outputs go to out/<scale>/. Set SKIP_BUILD=1 to reuse target/pricing-sim.jar, JAVA_OPTS to change the JVM.
set -euo pipefail

SCALE="${1:-starter}"
shift || true
EXTRA=("$@")
OUT="out/${SCALE}"
JAR="target/pricing-sim.jar"
JAVA_OPTS="${JAVA_OPTS:--Xmx2g}"

cd "$(dirname "$0")/.."

step() {
  echo
  echo "==> $*"
  STEP_STARTED=$(date +%s)
}
done_step() {
  echo "    ($(( $(date +%s) - STEP_STARTED )) s)"
}
run() {
  # shellcheck disable=SC2086
  java ${JAVA_OPTS} -jar "${JAR}" "$@"
}

[ -f .env ] || { echo "No .env found: copying .env.example"; cp .env.example .env; }

step "Start PostgreSQL (docker compose up -d --wait)"
docker compose up -d --wait
done_step

if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "${JAR}" ]; then
  step "Build (./mvnw -q -DskipTests package; tests run with ./mvnw verify)"
  ./mvnw -q -DskipTests package
  done_step
fi

step "Generate ${SCALE} -> ${OUT}"
run generate --scale "${SCALE}" --out "${OUT}" ${EXTRA[@]+"${EXTRA[@]}"}
done_step

step "Load into PostgreSQL"
run load --scale "${SCALE}" --out "${OUT}"
done_step

step "Validate"
run validate --scale "${SCALE}" --out "${OUT}"
done_step

step "Detect exceptions"
run detect --scale "${SCALE}" --out "${OUT}"
done_step

step "Evaluate against ground truth"
run evaluate --scale "${SCALE}" --out "${OUT}"
done_step

FORMATS="json,sql"
if [ "${SCALE}" = "full" ]; then
  FORMATS="json"   # PLAN.md §11: the SQL seed is produced for the starter dataset only
fi
step "Export (${FORMATS})"
run export --scale "${SCALE}" --out "${OUT}" --formats "${FORMATS}"
done_step

echo
echo "Pipeline finished. Key outputs in ${OUT}/:"
echo "  manifest.json, checksums.sha256, labels/, validation_summary.md, detected_exceptions.csv, evaluation_metrics.json"
grep -E '^\| \*\*' "${OUT}/validation_summary.md" || true
