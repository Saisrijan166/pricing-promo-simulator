# ER diagram

9 core entities, 4 bridge tables (N:M), 3 event-history tables and 3 pipeline output tables, 19 tables in all
(PLAN §4). Field-level detail is in [data_dictionary.md](data_dictionary.md) and ID formats are in
[id_conventions.md](id_conventions.md).

## Data model

Key columns and the main attributes are shown. `PK` = primary key, `FK` = foreign key.

```mermaid
erDiagram
    product ||--|{ cost_record : "has monthly costs"
    product ||--|{ price_history : "priced daily"
    product ||--o{ competitor_observation : "observed (tracked SKUs)"
    product ||--|{ inventory_snapshot : "stocked daily"
    product ||--o{ customer_order : "ordered"
    product ||--|{ price_decision : "priced by"
    product ||--o{ promotion_product : "targeted by"
    promotion ||--o{ promotion_product : "targets"
    promotion ||--|{ promotion_rule : "governed by"
    promotion |o--o{ promotion_rule : "excluded by (target)"
    cost_record ||--o{ price_decision : "cost basis of"
    cost_record ||--o{ price_history : "floor basis of"
    cost_record ||--o{ customer_order : "margin basis of"
    price_decision |o--o{ price_history : "sets price of"
    price_decision |o--o| price_decision : "supersedes"
    price_history ||--o{ customer_order : "prices"
    customer_order ||--o{ order_promotion : "presents"
    promotion ||--o{ order_promotion : "applied/rejected on"
    price_decision ||--o{ price_decision_promotion : "considers"
    promotion ||--o{ price_decision_promotion : "considered by"
    price_decision ||--o{ price_decision_observation : "considers"
    competitor_observation ||--o{ price_decision_observation : "considered by"
    product ||--|{ product_status_event : "lifecycle"
    promotion ||--|{ promotion_status_event : "lifecycle"
    price_decision ||--|{ price_decision_status_event : "lifecycle"

    product {
        varchar sku_id PK "SKU-000001"
        varchar category
        varchar status "NEW|ACTIVE|DISCONTINUED"
        date launch_date
        date discontinued_date
        numeric mrp "hard ceiling"
        numeric map_price
        numeric min_margin_pct
        numeric min_promo_margin_pct
        varchar price_position
        boolean competitor_tracked
    }
    cost_record {
        varchar cost_record_id PK "COST-SKU-000001-2026-01"
        varchar sku_id FK
        char cost_month
        date effective_from
        date effective_to "exclusive"
        numeric landed_cost "unit + freight"
        varchar change_reason
    }
    price_history {
        varchar price_history_id PK "PRC-SKU-000001-20260101"
        varchar sku_id FK
        date price_date
        numeric list_price
        numeric shelf_price
        varchar change_reason "nullable at DB level"
        varchar source_decision_id FK "nullable at DB level"
        varchar cost_record_id FK
    }
    competitor_observation {
        varchar observation_id PK "OBS-2026-0000001"
        varchar sku_id FK
        varchar competitor
        numeric observed_price
        timestamptz source_captured_at "truth time"
        timestamptz ingested_at "system time"
        numeric match_confidence
    }
    inventory_snapshot {
        varchar inventory_snapshot_id PK "INV-SKU-000001-20260101"
        varchar sku_id FK
        date snapshot_date
        int opening_on_hand
        int units_sold
        int closing_on_hand
        int lost_sales_units
        boolean stockout_flag
    }
    promotion {
        varchar promotion_id PK "PROMO-2026-000001"
        varchar promo_type
        varchar stack_group
        varchar coupon_code "CPN-2026-000001"
        date start_date
        date end_date
        int priority
        varchar status
    }
    promotion_rule {
        varchar rule_id PK "RULE-2026-000001"
        varchar promotion_id FK
        varchar rule_type
        varchar target_promotion_id FK "EXCLUDES_PROMOTION only"
        numeric param_numeric
    }
    customer_order {
        varchar order_id PK "ORD-2026-0000001"
        varchar sku_id FK
        varchar price_history_id FK
        varchar cost_record_id FK
        varchar customer_ref "opaque, no FK"
        int quantity
        numeric net_amount
        numeric gross_margin_amount
        varchar order_status
    }
    price_decision {
        varchar decision_id PK "DEC-2026-000001"
        varchar sku_id FK
        varchar previous_decision_id FK "self"
        varchar cost_record_id FK
        timestamptz decision_ts
        date effective_date
        varchar trigger
        numeric floor_price
        numeric ceiling_price
        numeric final_list_price
        numeric margin_pct "net basis"
        varchar primary_reason_code
        text explanation "JSON steps S1-S12"
        varchar status
    }
    promotion_product {
        varchar promotion_id PK, FK
        varchar sku_id PK, FK
    }
    order_promotion {
        varchar order_id PK, FK
        varchar promotion_id PK, FK
        varchar application_status
        varchar rejection_reason
        numeric discount_amount
    }
    price_decision_promotion {
        varchar decision_id PK, FK
        varchar promotion_id PK, FK
        varchar application_status
        varchar reason_code
    }
    price_decision_observation {
        varchar decision_id PK, FK
        varchar observation_id PK, FK
        varchar usage "USED|EXCLUDED_*"
        numeric age_hours
    }
    product_status_event {
        varchar event_id PK "PEV-2026-000001"
        varchar sku_id FK
        varchar from_status
        varchar to_status
        timestamptz event_ts
    }
    promotion_status_event {
        varchar event_id PK "PMEV-2026-000001"
        varchar promotion_id FK
        varchar from_status
        varchar to_status
        timestamptz event_ts
    }
    price_decision_status_event {
        varchar event_id PK "DEV-2026-0000001"
        varchar decision_id FK
        varchar from_status
        varchar to_status
        timestamptz event_ts
    }
```

### Pipeline output tables

```mermaid
erDiagram
    pipeline_run ||--o{ validation_result : "records"
    pipeline_run ||--o{ detected_exception : "finds"
    product |o--o{ detected_exception : "concerns"

    pipeline_run {
        varchar run_id PK
        varchar command
        varchar status
        timestamptz started_at
    }
    validation_result {
        varchar run_id PK, FK
        varchar check_id PK
        varchar category
        bigint violations
        varchar status "PASS|FAIL|WARN"
    }
    detected_exception {
        varchar exception_id PK "EXC-2026-0000001"
        varchar run_id FK
        varchar category "ANOMALY|OPERATIONAL"
        varchar issue_type
        varchar entity_type
        varchar entity_id
        varchar sku_id FK
    }
```

## Relationships and cardinality

"Optional?" describes the child's participation: whether every parent must have children, and whether the FK column may be null.

| # | Parent → Child | Card. | FK column | Optional? | Notes |
|---|---|---|---|---|---|
| 1 | product → cost_record | 1:N | `cost_record.sku_id` | Mandatory (≥1 per active month) | Effective periods do not overlap |
| 2 | product → price_history | 1:N | `price_history.sku_id` | Mandatory (1 per active day) | |
| 3 | product → competitor_observation | 1:N | `competitor_observation.sku_id` | Optional (only `competitor_tracked = true`) | |
| 4 | product → inventory_snapshot | 1:N | `inventory_snapshot.sku_id` | Mandatory (1 per active day) | |
| 5 | product → customer_order | 1:N | `customer_order.sku_id` | Optional | |
| 6 | product → price_decision | 1:N | `price_decision.sku_id` | Mandatory (≥1: INITIAL) | |
| 7 | product ⇄ promotion | N:M | via `promotion_product` | Optional | |
| 8 | promotion → promotion_rule | 1:N | `promotion_rule.promotion_id` | Mandatory (≥1) | |
| 9 | promotion → promotion_rule (target) | 1:N | `promotion_rule.target_promotion_id` | Optional (only `EXCLUDES_PROMOTION`) | Self-relationship via rule |
| 10 | cost_record → price_decision | 1:N | `price_decision.cost_record_id` | FK mandatory | Cost basis used |
| 11 | cost_record → price_history | 1:N | `price_history.cost_record_id` | FK mandatory | For floor reconciliation |
| 12 | cost_record → customer_order | 1:N | `customer_order.cost_record_id` | FK mandatory | Margin basis |
| 13 | price_decision → price_history | 1:N | `price_history.source_decision_id` | Nullable at DB level (null = anomaly) | One decision sets 1..n days |
| 14 | price_decision → price_decision | 1:0..1 | `price_decision.previous_decision_id` | Optional (null for INITIAL) | Supersession chain |
| 15 | price_history → customer_order | 1:N | `customer_order.price_history_id` | FK mandatory | Order price traceable to the day's price |
| 16 | customer_order ⇄ promotion | N:M | via `order_promotion` | Optional | Includes REJECTED coupon attempts |
| 17 | price_decision ⇄ promotion | N:M | via `price_decision_promotion` | Optional | APPLIED / REJECTED with reason |
| 18 | price_decision ⇄ competitor_observation | N:M | via `price_decision_observation` | Optional | USED / EXCLUDED_* with age |
| 19 | product → product_status_event | 1:N | `product_status_event.sku_id` | Mandatory (≥1) | Event history, never overwritten |
| 20 | promotion → promotion_status_event | 1:N | `promotion_status_event.promotion_id` | Mandatory (≥1) | Event history, never overwritten |
| 21 | price_decision → price_decision_status_event | 1:N | `price_decision_status_event.decision_id` | Mandatory (≥1) | Event history, never overwritten |
| 22 | pipeline_run → validation_result | 1:N | `validation_result.run_id` | Optional | Output |
| 23 | pipeline_run → detected_exception | 1:N | `detected_exception.run_id` | Optional | Output |
| 24 | product → detected_exception | 1:N | `detected_exception.sku_id` | Nullable | Output |

`customer_order.customer_ref` is an opaque synthetic token (`CUST-0000001`). Customer is not one of the 9 entities,
so it has no FK, by design (PLAN §2 assumption 10).

"Every price is explainable" follows this chain:
`price_history.source_decision_id` → `price_decision` (stored inputs + explanation) → `cost_record`;
`price_decision_observation` → `competitor_observation`; `price_decision_promotion` → `promotion` → `promotion_rule`.

## Lifecycle states

State changes are appended to the `*_status_event` tables. The parent's `status` column equals the last event.

```mermaid
stateDiagram-v2
    direction LR
    state "Product" as P {
        [*] --> ACTIVE : launched before window
        [*] --> NEW : launched in window
        NEW --> ACTIVE : after 14 days
        ACTIVE --> DISCONTINUED
        DISCONTINUED --> [*]
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "Promotion" as PR {
        [*] --> DRAFT
        DRAFT --> SCHEDULED
        SCHEDULED --> ACTIVE : start_date 00:00Z
        ACTIVE --> EXPIRED : end_date + 1 00:00Z
        DRAFT --> CANCELLED : never applied
        SCHEDULED --> CANCELLED : never applied
        ACTIVE --> CANCELLED : early stop
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "PriceDecision" as D {
        [*] --> PROPOSED
        PROPOSED --> APPROVED : change within 10 pct, no conflict
        PROPOSED --> ESCALATED : change over 10 pct or guardrail conflict
        ESCALATED --> APPROVED : MERCH_ANALYST / OPS_MANAGER
        PROPOSED --> REJECTED
        ESCALATED --> REJECTED : previous price holds
        APPROVED --> APPLIED
        APPLIED --> SUPERSEDED : next decision applied
    }
```

| Entity | Allowed transitions |
|---|---|
| Product | `∅→ACTIVE`, `∅→NEW`, `NEW→ACTIVE`, `ACTIVE→DISCONTINUED` (terminal). No decisions or orders after `discontinued_date`. |
| Promotion | `∅→DRAFT`, `DRAFT→SCHEDULED`, `SCHEDULED→ACTIVE`, `ACTIVE→EXPIRED`, `DRAFT→CANCELLED`, `SCHEDULED→CANCELLED`, `ACTIVE→CANCELLED`. Nothing is applied after a cancel timestamp. |
| PriceDecision | `∅→PROPOSED`, `PROPOSED→APPROVED`, `PROPOSED→ESCALATED`, `ESCALATED→APPROVED`, `PROPOSED→REJECTED`, `ESCALATED→REJECTED`, `APPROVED→APPLIED`, `APPLIED→SUPERSEDED`. |

## Effective dates

| Table | Fields | Semantics |
|---|---|---|
| cost_record | `effective_from`, `effective_to` | Inclusive / exclusive; monthly; `effective_to` null on the latest record |
| promotion | `start_date`, `end_date` | Both inclusive; markdowns run Monday → Sunday |
| promotion_rule | `effective_from`, `effective_to` | Inside the promotion window |
| product | `launch_date`, `discontinued_date` | Active window |
| price_decision | `decision_ts`, `effective_date` | When computed (06:00Z) / when the price applies (same day) |
| price_history, inventory_snapshot | `price_date`, `snapshot_date` | Daily |
| competitor_observation | `source_captured_at`, `ingested_at` | Truth time / system time (≥ capture); freshness uses capture time |
