# REST API

Start the server with data loaded:

```bash
./scripts/run_all.sh starter          # PostgreSQL up, generate, load, validate, detect, evaluate
java -jar target/pricing-sim.jar      # no arguments = web server on :8080 (SERVER_PORT to change)
```

- **Base path:** `/api/v1`.
- **OpenAPI:** `/v3/api-docs`.
- **Swagger UI:** `/swagger-ui.html`.
- **Errors:** RFC 7807 `ProblemDetail` (`application/problem+json`).
- **Real samples:** `scripts/api_smoke.sh` calls the three core endpoints and saves the real responses to
  [evidence/api_samples.md](evidence/api_samples.md).

Code: `com.pricingsim.api` (controllers, `dto/`, `ApiExceptionHandler`), `com.pricingsim.service`,
`com.pricingsim.persistence` (`PricingInputDao`, `DecisionQueryDao`, `ExceptionDao`, `RunDao`). Tests:
`PricingControllerIT`, `DecisionControllerIT`, `ExceptionControllerIT` (MockMvc on H2) and `EngineReplayIT`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/pricing/simulate` | Run the pricing engine for a SKU on a date, with optional what-if overrides. **Read-only.** |
| GET | `/decisions/{decisionId}` | The stored decision record |
| GET | `/decisions/{decisionId}/explain` | Full traceability chain of a decision |
| GET | `/exceptions` | Detected exceptions, filtered and paged |
| GET | `/products/{skuId}/price-history?from&to` | Daily list and shelf price with source decision and reason |
| GET | `/reports/validation-summary` | `validation_summary.json` of the latest `validate` run |
| GET | `/reports/evaluation` | `evaluation_metrics.json` of the latest `evaluate` run |

**Field names:**
- DTO fields are camelCase.
- Stored rows (the decision record and the rows inside `explain`) keep their column names (snake_case), exactly as in
  the data dictionary.
- Decimals are exact (`249.00`) and timestamps are ISO-8601 UTC.

**Labels are never exposed.** No endpoint reads `labels/`, and `NoLeakageTest` scans the API, service and persistence
code to enforce it. `/exceptions` and `explain` show detector findings only.

## POST /pricing/simulate

Builds the decision inputs from the loaded data as of 06:00Z on `asOfDate`, the same way the generator did
(`PricingInputDao`, see [pricing_rules.md](pricing_rules.md)), then runs the same `PricingEngine`. **Nothing is
written.** Without overrides, simulating a stored decision's date reproduces that decision; `EngineReplayIT` checks
this for 100 decisions, explanation included.

Inputs:

| Input | Source |
|---|---|
| Trigger | The stored decision's trigger on that date. Otherwise INITIAL without earlier price history, else WEEKLY_REVIEW. |
| Analyst price | A stored MANUAL_OVERRIDE decision's target price. |
| Previous price | The previous day's `price_history` row. |
| On hand | Opening + receipts of the date. |
| Sales | 7- and 28-day sales before the date. |
| Competitor observations | Those captured and ingested by 06:00Z. |
| Markdowns | Every markdown that includes the SKU. |

Request:

```json
{
  "skuId": "SKU-000143",
  "asOfDate": "2026-02-02",
  "overrides": {
    "landedCost": 150.00,
    "onHandUnits": 0,
    "competitorPrices": [{"competitor": "COMP_ALPHA", "price": 199.00, "capturedAt": "2026-02-01T10:00:00Z"}],
    "promotionIds": ["PROMO-2026-000012"]
  }
}
```

| Field | Rules |
|---|---|
| `skuId` | required, `^SKU-\d{6}$` |
| `asOfDate` | required, ISO date; the SKU needs a cost record for that month (otherwise 404) |
| `overrides.landedCost` | > 0; replaces the cost record's landed cost |
| `overrides.onHandUnits` | ≥ 0; replaces the stock at decision time |
| `overrides.competitorPrices[]` | replace all stored observations: `competitor` (COMP_ALPHA / BETA / GAMMA), `price` > 0, `capturedAt`, optional `matchConfidence` 0–1 (default 1.0); ingested when captured; IDs `SIM-OBS-n` |
| `overrides.promotionIds[]` | restrict the markdowns considered; each must include the SKU (otherwise 404) |

Response (abridged; full sample in [evidence/api_samples.md](evidence/api_samples.md)):

```json
{
  "skuId": "SKU-000143", "asOfDate": "2026-02-02", "decisionTs": "2026-02-02T06:00:00Z", "trigger": "WEEKLY_REVIEW",
  "inputs": {"costRecordId": "COST-SKU-000143-2026-02", "landedCost": 139.96, "prevListPrice": 249.00,
             "previousDecisionId": "DEC-2026-000954", "onHandUnits": 0, "demand7dUnits": 3, "demand28dUnits": 10,
             "velocityRatio": 1.2000, "daysOfCover": 0.00},
  "candidateListPrice": 199.00, "expectedNetPrice": 199.00, "floorPrice": 193.03, "ceilingPrice": 259.00,
  "targetPrice": 169.00, "competitorRefPrice": null, "markdownDiscountPct": 0.0000,
  "marginPct": 0.2967, "requiredMarginPct": 0.2749,
  "decisionType": "DECREASE", "decisionSource": "MANUAL_OVERRIDE", "status": "ESCALATED",
  "primaryReasonCode": "MANUAL_OVERRIDE",
  "reasonCodes": ["MANUAL_OVERRIDE", "STOCKOUT_HOLD", "FLOOR_ENFORCED", "PRICE_POINT_ROUNDED", "LARGE_CHANGE_ESCALATED"],
  "observations": [],
  "promotions": [],
  "explanation": [{"step": "S1", "rule": "Inputs", "inputs": {"trigger": "WEEKLY_REVIEW", "observations": "0",
                   "promotions": "0"}, "output": "snapshot", "message": "visible at decision_ts"}, "… S2–S12 …"],
  "ruleVersion": "pricing-rules-v1",
  "persisted": false
}
```

In this sample the stored decision was a below-floor analyst override (a labelled anomaly). The simulation applies
the guardrails and raises the price to the floor's price point, 199. That is a 20% move from 249, so it is escalated.
This SKU-day has no competitor data or markdowns. When there are some, each `observations[]` entry is `{observationId,
competitor, price, capturedAt, ageHours, usage}` and each `promotions[]` entry is `{promotionId, status, reasonCode,
discountPerUnit, discountPctEffective}`.

```bash
curl -s -X POST http://localhost:8080/api/v1/pricing/simulate -H 'Content-Type: application/json' \
  -d '{"skuId":"SKU-000143","asOfDate":"2026-02-02","overrides":{"onHandUnits":0}}'
```

## GET /decisions/{decisionId} and /decisions/{decisionId}/explain

`/decisions/{id}` returns the stored `price_decision` row, with `explanation` parsed into an array.

`/explain` returns the full chain:

| Field | Content |
|---|---|
| `decision` | The stored row: every input, outcome, reason code and status |
| `product` | SKU policy: MRP, MAP, minimum margins, price position |
| `costRecord` | The cost record used (landed = unit + freight) |
| `observations[]` | Every observation considered, with competitor, price, capture and ingestion time, confidence, `usage` (USED / EXCLUDED_STALE / EXCLUDED_LOW_CONFIDENCE / EXCLUDED_SUPERSEDED) and `age_hours` |
| `promotions[]` | Every markdown considered, with APPLIED / REJECTED, `reason_code`, `discount_pct_effective` and its `rules[]` |
| `explanation[]` | The 12 engine steps S1–S12 |
| `priceHistory` | `from`, `to`, `days` and the `price_history` rows this decision set |
| `statusEvents[]` | PROPOSED → (ESCALATED →) APPROVED → APPLIED (→ SUPERSEDED), with actor and time |
| `previousDecisionId` / `nextDecisionId` | The supersession chain |
| `detectedExceptions[]` | Detector findings whose entity is this decision or one of its price-history rows |

```bash
curl -s http://localhost:8080/api/v1/decisions/DEC-2026-001222/explain
```

## GET /exceptions

| Parameter | Values |
|---|---|
| `category` | `ANOMALY` or `OPERATIONAL` |
| `issueType` | PRICE_BELOW_FLOOR, PROMO_STACK_VIOLATION, STALE_OBSERVATION_AS_CURRENT, MARGIN_BEFORE_DISCOUNT, PRICE_CHANGE_WITHOUT_REASON, COMPETITOR_DATA_STALE, GUARDRAIL_CONFLICT_ESCALATED, STOCKOUT, COUPON_REJECTED |
| `skuId` | `^SKU-\d{6}$` |
| `from`, `to` | ISO dates, inclusive, on `event_date` |
| `page` | ≥ 0, default 0 |
| `size` | 1–500, default 50 |

Results are sorted by `event_date`, then `exception_id`. That is a total order, so pages never overlap or skip rows.

```json
{"content": [{"exceptionId": "EXC-2026-0000011", "runId": "DETECT-56e948afd3f5cb08", "category": "ANOMALY",
              "issueType": "STALE_OBSERVATION_AS_CURRENT", "entityType": "price_decision",
              "entityId": "DEC-2026-000411", "skuId": "SKU-000016", "eventDate": "2026-01-12",
              "relatedIds": ["OBS-2026-0000783"], "violatedRule": "OBSERVATION_FRESH_72H", "observedValue": "107.04",
              "expectedValue": "72", "recommendedAction": "EXCLUDE_OBSERVATION",
              "detail": "observation OBS-2026-0000783 used at age 107.04 h"}],
 "page": 0, "size": 5, "totalElements": 25, "totalPages": 5}
```

```bash
curl -s 'http://localhost:8080/api/v1/exceptions?category=ANOMALY&page=0&size=5'
curl -s 'http://localhost:8080/api/v1/exceptions?issueType=STOCKOUT&skuId=SKU-000001&from=2026-01-01&to=2026-01-31'
```

## GET /products/{skuId}/price-history

Optional `from` and `to` are inclusive ISO dates. Each row has `priceDate`, `listPrice`, `shelfPrice`,
`previousListPrice`, `changeReason`, `sourceDecisionId`, `decisionTrigger`, `decisionPrimaryReason` and
`costRecordId`.

```bash
curl -s 'http://localhost:8080/api/v1/products/SKU-000001/price-history?from=2026-01-01&to=2026-01-07'
```

## GET /reports/validation-summary and /reports/evaluation

These return the JSON files written by the latest `validate` / `evaluate` run, located through
`pipeline_run.input_path`. They return 404 until those commands have run. Start the server from the repository
root so the relative `out/<scale>` path resolves.

## Errors

| Case | Status |
|---|---|
| Malformed JSON, invalid date, failed bean validation (pattern, range, size), unknown enum value, `from` after `to` | 400 |
| Unknown SKU, decision or promotion; date outside the SKU's loaded data; report not produced yet | 404 |

```json
{"type": "about:blank", "title": "Not found", "status": 404,
 "detail": "Unknown decision DEC-2026-999999", "instance": "/api/v1/decisions/DEC-2026-999999/explain"}
```
