#!/usr/bin/env bash
# Smoke test of the three core endpoints (PLAN.md §13) against a running server with data loaded
# (./scripts/run_all.sh starter, then java -jar target/pricing-sim.jar). Saves the real responses to
# docs/evidence/api_samples.md. Exits non-zero if any call fails or returns an empty body.
#
#   ./scripts/api_smoke.sh [base-url]     (default http://localhost:8080)
set -euo pipefail

BASE="${1:-http://localhost:8080}"
API="${BASE}/api/v1"
OUT="docs/evidence/api_samples.md"
cd "$(dirname "$0")/.."
mkdir -p "$(dirname "${OUT}")"

pretty() {  # indent JSON text without parsing numbers (keeps 249.00 as 249.00); raw if python3 is missing
  if command -v python3 > /dev/null; then
    python3 -c '
import sys
text, out, ind, in_str, esc = sys.stdin.read(), [], 0, False, False
for c in text:
    if in_str:
        out.append(c)
        if esc:
            esc = False
        elif c == "\\":
            esc = True
        elif c == "\"":
            in_str = False
    elif c == "\"":
        in_str = True
        out.append(c)
    elif c in "{[":
        ind += 1
        out.append(c + "\n" + "  " * ind)
    elif c in "}]":
        ind -= 1
        if out and out[-1].endswith("\n" + "  " * (ind + 1)) and out[-1][0] in "{[":
            out[-1] = out[-1][0]
            out.append(c)
        else:
            out.append("\n" + "  " * ind + c)
    elif c == ",":
        out.append(",\n" + "  " * ind)
    elif c == ":":
        out.append(": ")
    elif not c.isspace():
        out.append(c)
print("".join(out))'
  else
    cat; echo
  fi
}

call() {  # call <name> <curl args...>; prints the body, fails on HTTP >= 400 or an empty body
  local name="$1"; shift
  local body status
  body=$(curl -sS -w '\n%{http_code}' "$@")
  status="${body##*$'\n'}"
  body="${body%$'\n'*}"
  if [ "${status}" -ge 400 ] || [ -z "${body}" ]; then
    echo "FAIL ${name}: HTTP ${status}" >&2
    echo "${body}" >&2
    exit 1
  fi
  echo "OK   ${name}: HTTP ${status}, ${#body} bytes" >&2
  printf '%s' "${body}"
}

field() {  # first value of a JSON string field
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1
}

curl -sf -o /dev/null "${BASE}/v3/api-docs" || { echo "No server at ${BASE} (start: java -jar target/pricing-sim.jar)" >&2; exit 1; }

# Pick a real decision: the first PRICE_BELOW_FLOOR finding if detect has run, else the first decision.
FINDING=$(call "exceptions (lookup)" "${API}/exceptions?issueType=PRICE_BELOW_FLOOR&size=1")
DECISION=$(printf '%s' "${FINDING}" | field entityId)
DECISION="${DECISION:-DEC-2026-000001}"
DETAIL=$(call "decision (lookup)" "${API}/decisions/${DECISION}")
SKU=$(printf '%s' "${DETAIL}" | field sku_id)
DATE=$(printf '%s' "${DETAIL}" | field effective_date)

SIM_BODY="{\"skuId\":\"${SKU}\",\"asOfDate\":\"${DATE}\",\"overrides\":{\"onHandUnits\":0}}"
SIMULATE=$(call "POST /pricing/simulate" -X POST "${API}/pricing/simulate" -H 'Content-Type: application/json' \
  -d "${SIM_BODY}")
EXPLAIN=$(call "GET /decisions/{id}/explain" "${API}/decisions/${DECISION}/explain")
EXCEPTIONS=$(call "GET /exceptions" "${API}/exceptions?category=ANOMALY&page=0&size=5")
NOT_FOUND=$(curl -sS "${API}/decisions/DEC-2026-999999/explain")

{
  echo "# API samples"
  echo
  echo "Real responses captured by \`scripts/api_smoke.sh\` from \`${BASE}\` with the starter dataset loaded."
  echo "The explain sample is a decision the detector flagged (PRICE_BELOW_FLOOR), so it shows a linked finding."
  echo
  echo "## POST /api/v1/pricing/simulate"
  echo
  echo '```bash'
  echo "curl -s -X POST ${API}/pricing/simulate -H 'Content-Type: application/json' \\"
  echo "  -d '${SIM_BODY}'"
  echo '```'
  echo
  echo '```json'
  printf '%s' "${SIMULATE}" | pretty
  echo '```'
  echo
  echo "## GET /api/v1/decisions/${DECISION}/explain"
  echo
  echo '```bash'
  echo "curl -s ${API}/decisions/${DECISION}/explain"
  echo '```'
  echo
  echo '```json'
  printf '%s' "${EXPLAIN}" | pretty
  echo '```'
  echo
  echo "## GET /api/v1/exceptions?category=ANOMALY&page=0&size=5"
  echo
  echo '```bash'
  echo "curl -s '${API}/exceptions?category=ANOMALY&page=0&size=5'"
  echo '```'
  echo
  echo '```json'
  printf '%s' "${EXCEPTIONS}" | pretty
  echo '```'
  echo
  echo "## Error example: unknown decision (RFC 7807 ProblemDetail, HTTP 404)"
  echo
  echo '```json'
  printf '%s' "${NOT_FOUND}" | pretty
  echo '```'
} > "${OUT}"

echo "Wrote ${OUT}"
