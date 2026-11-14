# API samples

Real responses captured by `scripts/api_smoke.sh` from `http://localhost:8080` with the starter dataset loaded.
The explain sample is a decision the detector flagged (PRICE_BELOW_FLOOR), so it shows a linked finding.

## POST /api/v1/pricing/simulate

```bash
curl -s -X POST http://localhost:8080/api/v1/pricing/simulate -H 'Content-Type: application/json' \
  -d '{"skuId":"SKU-000143","asOfDate":"2026-02-02","overrides":{"onHandUnits":0}}'
```

```json
{
  "skuId": "SKU-000143",
  "asOfDate": "2026-02-02",
  "decisionTs": "2026-02-02T06:00:00Z",
  "trigger": "WEEKLY_REVIEW",
  "inputs": {
    "costRecordId": "COST-SKU-000143-2026-02",
    "landedCost": 139.96,
    "prevListPrice": 249.00,
    "previousDecisionId": "DEC-2026-000953",
    "onHandUnits": 0,
    "demand7dUnits": 3,
    "demand28dUnits": 10,
    "velocityRatio": 1.2000,
    "daysOfCover": 0.00
  },
  "candidateListPrice": 199.00,
  "expectedNetPrice": 199.00,
  "floorPrice": 193.03,
  "ceilingPrice": 259.00,
  "targetPrice": 169.00,
  "competitorRefPrice": null,
  "markdownDiscountPct": 0.0000,
  "marginPct": 0.2967,
  "requiredMarginPct": 0.2749,
  "decisionType": "DECREASE",
  "decisionSource": "MANUAL_OVERRIDE",
  "status": "ESCALATED",
  "primaryReasonCode": "MANUAL_OVERRIDE",
  "reasonCodes": [
    "MANUAL_OVERRIDE",
    "STOCKOUT_HOLD",
    "FLOOR_ENFORCED",
    "PRICE_POINT_ROUNDED",
    "LARGE_CHANGE_ESCALATED"
  ],
  "observations": [],
  "promotions": [],
  "explanation": [
    {
      "step": "S1",
      "rule": "Inputs",
      "inputs": {
        "trigger": "WEEKLY_REVIEW",
        "observations": "0",
        "promotions": "0"
      },
      "output": "snapshot",
      "message": "visible at decision_ts"
    },
    {
      "step": "S2",
      "rule": "Floor",
      "inputs": {
        "landed": "139.96",
        "min_margin": "0.2749",
        "map": null
      },
      "output": "193.03",
      "message": "max(landed/(1-min_margin), map)"
    },
    {
      "step": "S3",
      "rule": "Ceiling",
      "inputs": {
        "mrp": "259.00"
      },
      "output": "259.00",
      "message": "MRP"
    },
    {
      "step": "S4",
      "rule": "Competitor ref",
      "inputs": {
        "considered": "0",
        "used": "0",
        "stale": "0"
      },
      "output": null,
      "message": "none usable"
    },
    {
      "step": "S5",
      "rule": "Target",
      "inputs": {
        "position": "MATCH"
      },
      "output": "169.00",
      "message": "manual override"
    },
    {
      "step": "S6",
      "rule": "Demand/inventory",
      "inputs": {
        "on_hand": "0",
        "cover": "0.00",
        "velocity": "1.2000",
        "history_days": "28"
      },
      "output": "169.00",
      "message": "stockout: hold, suppress markdowns"
    },
    {
      "step": "S7",
      "rule": "Step limit",
      "inputs": {
        "prev": "249.00"
      },
      "output": "169.00",
      "message": "n/a"
    },
    {
      "step": "S8",
      "rule": "Clamp",
      "inputs": {},
      "output": "193.03",
      "message": "raised to floor"
    },
    {
      "step": "S9",
      "rule": "Price point",
      "inputs": {},
      "output": "199.00",
      "message": "rounded"
    },
    {
      "step": "S10",
      "rule": "Promotions",
      "inputs": {
        "eligible": "0"
      },
      "output": "0 applied",
      "message": "suppressed: stockout"
    },
    {
      "step": "S11",
      "rule": "Net margin",
      "inputs": {
        "net": "199.00",
        "required": "0.2749"
      },
      "output": "0.2967",
      "message": "(net - landed) / net"
    },
    {
      "step": "S12",
      "rule": "Outcome",
      "inputs": {},
      "output": "DECREASE ESCALATED",
      "message": "MANUAL_OVERRIDE"
    }
  ],
  "ruleVersion": "pricing-rules-v1",
  "persisted": false
}
```

## GET /api/v1/decisions/DEC-2026-001221/explain

```bash
curl -s http://localhost:8080/api/v1/decisions/DEC-2026-001221/explain
```

```json
{
  "decision": {
    "decision_id": "DEC-2026-001221",
    "sku_id": "SKU-000143",
    "decision_ts": "2026-02-02T06:00:00Z",
    "effective_date": "2026-02-02",
    "trigger": "WEEKLY_REVIEW",
    "decision_source": "MANUAL_OVERRIDE",
    "previous_decision_id": "DEC-2026-000953",
    "cost_record_id": "COST-SKU-000143-2026-02",
    "landed_cost": 139.96,
    "prev_list_price": 249.00,
    "floor_price": 193.03,
    "ceiling_price": 259.00,
    "competitor_ref_price": null,
    "competitor_fresh_count": 0,
    "competitor_stale_count": 0,
    "on_hand_units": 4,
    "demand_7d_units": 3,
    "demand_28d_units": 10,
    "velocity_ratio": 1.2000,
    "days_of_cover": 11.20,
    "target_price": 169.00,
    "final_list_price": 169.00,
    "markdown_discount_pct": 0.0000,
    "expected_net_price": 169.00,
    "margin_pct": 0.1718,
    "required_margin_pct": 0.2749,
    "decision_type": "DECREASE",
    "primary_reason_code": "MANUAL_OVERRIDE",
    "reason_codes": "MANUAL_OVERRIDE|LARGE_CHANGE_ESCALATED",
    "rule_version": "pricing-rules-v1",
    "status": "SUPERSEDED"
  },
  "product": {
    "sku_id": "SKU-000143",
    "product_name": "Aquaveil Deodorant 150 ml",
    "brand": "Aquaveil",
    "category": "PERSONAL_CARE",
    "mrp": 259.00,
    "map_price": null,
    "min_margin_pct": 0.2749,
    "min_promo_margin_pct": 0.2181,
    "price_position": "MATCH",
    "status": "ACTIVE"
  },
  "costRecord": {
    "cost_record_id": "COST-SKU-000143-2026-02",
    "sku_id": "SKU-000143",
    "cost_month": "2026-02",
    "effective_from": "2026-02-01",
    "effective_to": "2026-03-01",
    "unit_cost": 134.63,
    "freight_cost": 5.33,
    "landed_cost": 139.96,
    "change_pct": 0.0000,
    "change_reason": "NO_CHANGE"
  },
  "observations": [],
  "promotions": [],
  "explanation": [
    {
      "step": "S1",
      "rule": "Inputs",
      "inputs": {
        "trigger": "WEEKLY_REVIEW",
        "observations": "0",
        "promotions": "0"
      },
      "output": "snapshot",
      "message": "visible at decision_ts"
    },
    {
      "step": "S2",
      "rule": "Floor",
      "inputs": {
        "landed": "139.96",
        "min_margin": "0.2749",
        "map": null
      },
      "output": "193.03",
      "message": "max(landed/(1-min_margin), map)"
    },
    {
      "step": "S3",
      "rule": "Ceiling",
      "inputs": {
        "mrp": "259.00"
      },
      "output": "259.00",
      "message": "MRP"
    },
    {
      "step": "S4",
      "rule": "Competitor ref",
      "inputs": {
        "considered": "0",
        "used": "0",
        "stale": "0"
      },
      "output": null,
      "message": "none usable"
    },
    {
      "step": "S5",
      "rule": "Target",
      "inputs": {
        "position": "MATCH"
      },
      "output": "169.00",
      "message": "manual override"
    },
    {
      "step": "S6",
      "rule": "Demand/inventory",
      "inputs": {
        "on_hand": "4",
        "cover": "11.20",
        "velocity": "1.2000",
        "history_days": "28"
      },
      "output": "169.00",
      "message": "manual: none"
    },
    {
      "step": "S7",
      "rule": "Step limit",
      "inputs": {
        "prev": "249.00"
      },
      "output": "169.00",
      "message": "n/a"
    },
    {
      "step": "S8",
      "rule": "Clamp",
      "inputs": {},
      "output": "169.00",
      "message": "inside"
    },
    {
      "step": "S9",
      "rule": "Price point",
      "inputs": {},
      "output": "169.00",
      "message": "already a point"
    },
    {
      "step": "S10",
      "rule": "Promotions",
      "inputs": {
        "eligible": "0"
      },
      "output": "0 applied",
      "message": "by (priority, promotion_id)"
    },
    {
      "step": "S11",
      "rule": "Net margin",
      "inputs": {
        "net": "169.00",
        "required": "0.2749"
      },
      "output": "0.1718",
      "message": "(net - landed) / net"
    },
    {
      "step": "S12",
      "rule": "Outcome",
      "inputs": {},
      "output": "DECREASE ESCALATED",
      "message": "MANUAL_OVERRIDE"
    }
  ],
  "priceHistory": {
    "from": "2026-02-02",
    "to": "2026-02-04",
    "days": 3,
    "rows": [
      {
        "price_history_id": "PRC-SKU-000143-20260202",
        "price_date": "2026-02-02",
        "list_price": 169.00,
        "shelf_price": 169.00,
        "previous_list_price": 249.00,
        "change_reason": "MANUAL_OVERRIDE"
      },
      {
        "price_history_id": "PRC-SKU-000143-20260203",
        "price_date": "2026-02-03",
        "list_price": 169.00,
        "shelf_price": 169.00,
        "previous_list_price": 169.00,
        "change_reason": "NO_CHANGE"
      },
      {
        "price_history_id": "PRC-SKU-000143-20260204",
        "price_date": "2026-02-04",
        "list_price": 169.00,
        "shelf_price": 169.00,
        "previous_list_price": 169.00,
        "change_reason": "NO_CHANGE"
      }
    ]
  },
  "statusEvents": [
    {
      "event_id": "DEV-2026-0004275",
      "from_status": null,
      "to_status": "PROPOSED",
      "event_ts": "2026-02-02T06:00:00Z",
      "actor_role": "MERCH_ANALYST",
      "note": "Analyst price override"
    },
    {
      "event_id": "DEV-2026-0004468",
      "from_status": "PROPOSED",
      "to_status": "ESCALATED",
      "event_ts": "2026-02-02T06:00:01Z",
      "actor_role": "SYSTEM",
      "note": "Change above auto-approve limit"
    },
    {
      "event_id": "DEV-2026-0004907",
      "from_status": "ESCALATED",
      "to_status": "APPROVED",
      "event_ts": "2026-02-02T06:29:00Z",
      "actor_role": "MERCH_ANALYST",
      "note": "Approved after review"
    },
    {
      "event_id": "DEV-2026-0004909",
      "from_status": "APPROVED",
      "to_status": "APPLIED",
      "event_ts": "2026-02-02T06:29:01Z",
      "actor_role": "SYSTEM",
      "note": "Price applied"
    },
    {
      "event_id": "DEV-2026-0004921",
      "from_status": "APPLIED",
      "to_status": "SUPERSEDED",
      "event_ts": "2026-02-05T07:06:01Z",
      "actor_role": "SYSTEM",
      "note": "Superseded by DEC-2026-001277"
    }
  ],
  "previousDecisionId": "DEC-2026-000953",
  "nextDecisionId": "DEC-2026-001277",
  "detectedExceptions": [
    {
      "exception_id": "EXC-2026-0000001",
      "run_id": "DETECT-6f47106c3a1ce835",
      "category": "ANOMALY",
      "issue_type": "PRICE_BELOW_FLOOR",
      "entity_type": "price_decision",
      "entity_id": "DEC-2026-001221",
      "sku_id": "SKU-000143",
      "event_date": "2026-02-02",
      "related_ids": "COST-SKU-000143-2026-02",
      "violated_rule": "LIST_PRICE_GTE_FLOOR",
      "observed_value": "169.00",
      "expected_value": "193.03",
      "recommended_action": "BLOCK_PRICE",
      "detail": "final_list_price 169.00 < floor 193.03"
    }
  ]
}
```

## GET /api/v1/exceptions?category=ANOMALY&page=0&size=5

```bash
curl -s 'http://localhost:8080/api/v1/exceptions?category=ANOMALY&page=0&size=5'
```

```json
{
  "content": [
    {
      "exceptionId": "EXC-2026-0000011",
      "runId": "DETECT-6f47106c3a1ce835",
      "category": "ANOMALY",
      "issueType": "STALE_OBSERVATION_AS_CURRENT",
      "entityType": "price_decision",
      "entityId": "DEC-2026-000223",
      "skuId": "SKU-000034",
      "eventDate": "2026-01-05",
      "relatedIds": [
        "OBS-2026-0000111"
      ],
      "violatedRule": "OBSERVATION_FRESH_72H",
      "observedValue": "75.70",
      "expectedValue": "72",
      "recommendedAction": "EXCLUDE_OBSERVATION",
      "detail": "observation OBS-2026-0000111 used at age 75.70 h"
    },
    {
      "exceptionId": "EXC-2026-0000012",
      "runId": "DETECT-6f47106c3a1ce835",
      "category": "ANOMALY",
      "issueType": "STALE_OBSERVATION_AS_CURRENT",
      "entityType": "price_decision",
      "entityId": "DEC-2026-000354",
      "skuId": "SKU-000172",
      "eventDate": "2026-01-05",
      "relatedIds": [
        "OBS-2026-0000087"
      ],
      "violatedRule": "OBSERVATION_FRESH_72H",
      "observedValue": "80.67",
      "expectedValue": "72",
      "recommendedAction": "EXCLUDE_OBSERVATION",
      "detail": "observation OBS-2026-0000087 used at age 80.67 h"
    },
    {
      "exceptionId": "EXC-2026-0000013",
      "runId": "DETECT-6f47106c3a1ce835",
      "category": "ANOMALY",
      "issueType": "STALE_OBSERVATION_AS_CURRENT",
      "entityType": "price_decision",
      "entityId": "DEC-2026-000522",
      "skuId": "SKU-000132",
      "eventDate": "2026-01-12",
      "relatedIds": [
        "OBS-2026-0000640"
      ],
      "violatedRule": "OBSERVATION_FRESH_72H",
      "observedValue": "136.08",
      "expectedValue": "72",
      "recommendedAction": "EXCLUDE_OBSERVATION",
      "detail": "observation OBS-2026-0000640 used at age 136.08 h"
    },
    {
      "exceptionId": "EXC-2026-0000016",
      "runId": "DETECT-6f47106c3a1ce835",
      "category": "ANOMALY",
      "issueType": "MARGIN_BEFORE_DISCOUNT",
      "entityType": "price_decision",
      "entityId": "DEC-2026-000437",
      "skuId": "SKU-000044",
      "eventDate": "2026-01-12",
      "relatedIds": [],
      "violatedRule": "MARGIN_ON_NET_PRICE",
      "observedValue": "0.2293",
      "expectedValue": "-0.0276",
      "recommendedAction": "RECOMPUTE_MARGIN_ON_NET",
      "detail": "stored margin 0.2293, net margin -0.0276 < required 0.0564"
    },
    {
      "exceptionId": "EXC-2026-0000017",
      "runId": "DETECT-6f47106c3a1ce835",
      "category": "ANOMALY",
      "issueType": "MARGIN_BEFORE_DISCOUNT",
      "entityType": "price_decision",
      "entityId": "DEC-2026-000640",
      "skuId": "SKU-000036",
      "eventDate": "2026-01-19",
      "relatedIds": [],
      "violatedRule": "MARGIN_ON_NET_PRICE",
      "observedValue": "0.3701",
      "expectedValue": "0.1602",
      "recommendedAction": "RECOMPUTE_MARGIN_ON_NET",
      "detail": "stored margin 0.3701, net margin 0.1602 < required 0.1807"
    }
  ],
  "page": 0,
  "size": 5,
  "totalElements": 25,
  "totalPages": 5
}
```

## Error example: unknown decision (RFC 7807 ProblemDetail, HTTP 404)

```json
{
  "type": "about:blank",
  "title": "Not found",
  "status": 404,
  "detail": "Unknown decision DEC-2026-999999",
  "instance": "/api/v1/decisions/DEC-2026-999999/explain"
}
```
