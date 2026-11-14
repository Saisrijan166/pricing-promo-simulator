# Data dictionary

Every table and field in the pricing & promotion simulator. Source of truth for the Flyway migrations
(`src/main/resources/db/migration/V1–V5`) and `io/TableRegistry`. `TableRegistryTest` checks the registry against
the migrated schema, so this document, the DDL and the code stay in step.

## Conventions

| Item | Convention |
|---|---|
| **R / O** | R = required, O = optional. R fields are `NOT NULL` in the DDL unless marked **O (DB)**. |
| **U** | Uniqueness. **PK** is enforced by the database. Other uniqueness (natural keys, `U(...)`) is checked by the validator's DUPLICATE checks (PLAN §10), not by DB constraints. |
| **Enum** | Stored as `VARCHAR` with a `CHECK (col IN (...))` constraint named `ck_<table>_<column>`. The same scripts run on PostgreSQL and H2. Values are listed in [Enumerations](#enumerations). |
| **Money** | INR, `NUMERIC(12,2)`, excluding GST (tax out of scope). |
| **Percentages** | Decimal fractions, `NUMERIC(6,4)`. `0.2500` = 25%. |
| **Dates / timestamps** | `DATE`, `TIMESTAMP WITH TIME ZONE` (TIMESTAMPTZ), always UTC. Business date = UTC date. In files: ISO-8601 (`2026-01-01`, `2026-01-01T06:00:00Z`). |
| **Simulation window** | 2026-01-01 to 2026-06-29 inclusive (180 days) at full scale. |
| **Active window** | A product is active from `max(launch_date, window start)` to `min(discontinued_date − 1, window end)`, both inclusive. The discontinuation day itself is not active, because the DISCONTINUED event is at 00:00Z that day. `price_history` and `inventory_snapshot` have exactly one row per active day. |
| **Margin basis** | `margin_pct = (net_unit_price − landed_cost) / net_unit_price`. Net is after all applied discounts. `landed_cost = unit_cost + freight_cost` from the cost record effective on that date. One basis everywhere. |

### What the database enforces vs. what the validator enforces

The database enforces **structure only**: primary keys, foreign keys, `NOT NULL` on structural fields and enum
domains. Everything in the **Validation** column below that is a business rule is enforced by the validator and
detector, not by the DB. Examples: `list_price ≥ floor`, margins, reconciliation, natural-key uniqueness, date order
and lifecycle. This is deliberate. The labelled anomalies (PLAN §9) must stay loadable so they can be detected (PLAN §2 trade-offs).

---

## 1. Core entities

### 1.1 `product` — master

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| sku_id | VARCHAR(10) | R | `^SKU-\d{6}$` | PK | |
| product_name | VARCHAR(120) | R | synthetic "Brand Subcategory Variant" | | not blank |
| brand | VARCHAR(40) | R | fictional brand list | | |
| category | VARCHAR(20) | R | `Category` enum | | enum |
| subcategory | VARCHAR(40) | R | per-category list | | belongs to category |
| uom | VARCHAR(8) | R | `Uom`: EACH, PACK | | enum |
| pack_size | INTEGER | R | units | | ≥ 1 |
| status | VARCHAR(16) | R | `ProductStatus`: NEW, ACTIVE, DISCONTINUED | | = last `product_status_event.to_status` |
| launch_date | DATE | R | | | ≤ window end |
| discontinued_date | DATE | O | | | > launch_date; required iff status = DISCONTINUED |
| mrp | NUMERIC(12,2) | R | INR | | > 0; > first landed cost. **Hard price ceiling** |
| map_price | NUMERIC(12,2) | O | INR (brand minimum advertised price) | | 0 < map < mrp; feeds the floor |
| min_margin_pct | NUMERIC(6,4) | R | fraction | | 0.05–0.60 |
| min_promo_margin_pct | NUMERIC(6,4) | R | fraction | | 0 < x ≤ min_margin_pct |
| price_position | VARCHAR(8) | R | `PricePosition`: VALUE, MATCH, PREMIUM | | enum |
| competitor_tracked | BOOLEAN | R | | | |
| created_at | TIMESTAMPTZ | R | UTC | | ≤ launch_date |

### 1.2 `cost_record` — master, effective-dated (monthly)

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| cost_record_id | VARCHAR(25) | R | `^COST-SKU-\d{6}-\d{4}-(0[1-9]\|1[0-2])$` | PK | embedded SKU and month = `sku_id`, `cost_month` |
| sku_id | VARCHAR(10) | R | | FK → product | |
| cost_month | CHAR(7) | R | `YYYY-MM` | U(sku_id, cost_month) | |
| effective_from | DATE | R | inclusive | | first of month, or launch_date if later |
| effective_to | DATE | O | exclusive; null on latest record | | > effective_from; no overlap or gap per SKU |
| unit_cost | NUMERIC(12,2) | R | INR | | > 0 |
| freight_cost | NUMERIC(12,2) | R | INR | | ≥ 0 |
| landed_cost | NUMERIC(12,2) | R | INR | | = unit_cost + freight_cost |
| change_pct | NUMERIC(6,4) | O | fraction | | = landed / prev_landed − 1; null on first record |
| change_reason | VARCHAR(20) | R | `CostChangeReason`: INITIAL, NO_CHANGE, SUPPLIER_INCREASE, SUPPLIER_DECREASE, FREIGHT_CHANGE | | consistent with sign of change_pct |

### 1.3 `price_history` — daily event history

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| price_history_id | VARCHAR(23) | R | `^PRC-SKU-\d{6}-\d{8}$` | PK | embedded SKU and date = `sku_id`, `price_date` |
| sku_id | VARCHAR(10) | R | | FK → product | |
| price_date | DATE | R | | U(sku_id, price_date) | inside the product's active window |
| list_price | NUMERIC(12,2) | R | INR | | floor ≤ list ≤ mrp (business rule) |
| shelf_price | NUMERIC(12,2) | R | INR | | ≤ list_price; = list after applied markdowns |
| previous_list_price | NUMERIC(12,2) | O | INR | | = prior day's list_price; null on first day |
| change_reason | VARCHAR(32) | **O (DB)** | `ReasonCode` (NO_CHANGE when unchanged) | | required when the price changed (business rule) |
| source_decision_id | VARCHAR(15) | **O (DB)** | | FK → price_decision | list_price = decision.final_list_price |
| cost_record_id | VARCHAR(25) | R | | FK → cost_record | month(price_date) = cost_month |

`change_reason` and `source_decision_id` are nullable at DB level only so that the `PRICE_CHANGE_WITHOUT_REASON`
anomaly can be loaded and detected. In the valid baseline both are always set.

### 1.4 `competitor_observation` — event

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| observation_id | VARCHAR(16) | R | `^OBS-\d{4}-\d{7}$` | PK | |
| sku_id | VARCHAR(10) | R | | FK → product | product.competitor_tracked = true |
| competitor | VARCHAR(12) | R | `Competitor`: COMP_ALPHA, COMP_BETA, COMP_GAMMA (fictional) | U(sku_id, competitor, source_captured_at) | |
| observed_price | NUMERIC(12,2) | R | INR | | > 0; within 0.5×–1.5× mrp |
| source_captured_at | TIMESTAMPTZ | R | UTC; truth time | | inside the window |
| ingested_at | TIMESTAMPTZ | R | UTC; system time | | ≥ source_captured_at |
| source_type | VARCHAR(16) | R | `ObservationSource`: SCRAPE_FEED, PARTNER_FEED, MANUAL_AUDIT | | enum |
| competitor_in_stock | BOOLEAN | R | | | |
| match_confidence | NUMERIC(4,3) | R | 0–1 | | |

Freshness: an observation is *fresh* at a decision only if `decision_ts − source_captured_at ≤ 72 h` **and**
`ingested_at ≤ decision_ts`. Freshness uses capture time, so a late-arriving feed is still stale.

### 1.5 `inventory_snapshot` — daily snapshot (network level)

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| inventory_snapshot_id | VARCHAR(23) | R | `^INV-SKU-\d{6}-\d{8}$` | PK | embedded SKU and date = `sku_id`, `snapshot_date` |
| sku_id | VARCHAR(10) | R | | FK → product | |
| snapshot_date | DATE | R | | U(sku_id, snapshot_date) | inside the product's active window |
| opening_on_hand | INTEGER | R | units | | ≥ 0; = previous day's closing |
| receipts_units | INTEGER | R | units | | ≥ 0 |
| units_sold | INTEGER | R | units | | = Σ FULFILLED order quantity (sku, date) |
| adjustment_units | INTEGER | R | units | | ≤ 0 (shrinkage) |
| closing_on_hand | INTEGER | R | units | | = opening + receipts − sold + adjustment ≥ 0 |
| on_order_units | INTEGER | R | units | | ≥ 0 |
| demand_units | INTEGER | R | units | | ≥ units_sold |
| lost_sales_units | INTEGER | R | units | | = demand − sold |
| stockout_flag | BOOLEAN | R | | | = (closing = 0 OR lost_sales > 0) |
| days_of_cover | NUMERIC(8,2) | R | days | | = closing / max(avg 28-day units sold, 0.1) |

`days_of_cover` uses the SKU's last 28 snapshot rows up to and including this row (fewer at the start of its
history). The average is their total `units_sold` divided by the number of rows. The quotient is rounded HALF_UP to
2 dp, computed exactly as `closing × rows / Σ units_sold`, or as `closing / 0.1` when the average is ≤ 0.1. A
recomputation that first rounds the average can differ by 0.01.

### 1.6 `promotion` — master, effective-dated

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| promotion_id | VARCHAR(17) | R | `^PROMO-\d{4}-\d{6}$` | PK | |
| promotion_name | VARCHAR(80) | R | | | |
| promo_type | VARCHAR(16) | R | `PromoType`: MARKDOWN_PCT, MARKDOWN_AMOUNT, COUPON_PCT, COUPON_AMOUNT | | enum |
| stack_group | VARCHAR(10) | R | `StackGroup`: SITEWIDE, CATEGORY, SKU, COUPON | | COUPON iff promo_type is COUPON_* |
| discount_pct | NUMERIC(6,4) | O | fraction | | required iff *_PCT; 0.05–0.50 |
| discount_amount | NUMERIC(12,2) | O | INR | | required iff *_AMOUNT; > 0 |
| coupon_code | VARCHAR(16) | O | `^CPN-\d{4}-\d{6}$` | U | required iff COUPON_* |
| start_date | DATE | R | inclusive | | start ≤ end; MARKDOWN starts on a Monday |
| end_date | DATE | R | inclusive | | MARKDOWN ends on a Sunday |
| priority | INTEGER | R | 1 (highest)–100 | | |
| channel | VARCHAR(6) | R | `Channel`: ALL, ECOM, STORE | | enum |
| funding_source | VARCHAR(8) | R | `FundingSource`: VENDOR, RETAILER | | enum |
| status | VARCHAR(10) | R | `PromotionStatus`: DRAFT, SCHEDULED, ACTIVE, EXPIRED, CANCELLED | | as-of status on the last simulated day; = last `promotion_status_event.to_status` |
| created_at | TIMESTAMPTZ | R | UTC | | < start_date |

### 1.7 `promotion_rule` — master

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| rule_id | VARCHAR(16) | R | `^RULE-\d{4}-\d{6}$` | PK | |
| promotion_id | VARCHAR(17) | R | | FK → promotion | |
| rule_type | VARCHAR(28) | R | `RuleType`: EXCLUSIVE, EXCLUDES_PROMOTION, MAX_STACK_COUNT, MAX_COMBINED_DISCOUNT_PCT, MIN_ORDER_QTY | U(promotion_id, rule_type, target_promotion_id) | |
| target_promotion_id | VARCHAR(17) | O | | FK → promotion (self-relationship) | required iff EXCLUDES_PROMOTION; ≠ promotion_id; windows overlap |
| param_numeric | NUMERIC(10,4) | O | count / fraction / units | | required for MAX_* and MIN_ORDER_QTY |
| effective_from | DATE | R | | | inside the promotion window |
| effective_to | DATE | R | | | inside the promotion window; ≥ effective_from |

### 1.8 `customer_order` — transaction (single-SKU line)

`order` is a SQL reserved word, so the table is `customer_order`.

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| order_id | VARCHAR(16) | R | `^ORD-\d{4}-\d{7}$` | PK | |
| order_ts | TIMESTAMPTZ | R | UTC | | |
| order_date | DATE | R | | | = date(order_ts) |
| sku_id | VARCHAR(10) | R | | FK → product | product active; not discontinued |
| customer_ref | VARCHAR(13) | R | `^CUST-\d{7}$`, an opaque synthetic token with no PII | | not an FK (customer is not one of the 9 entities) |
| customer_segment | VARCHAR(8) | R | `CustomerSegment`: NEW, REGULAR, LOYALTY | | enum |
| channel | VARCHAR(6) | R | `Channel` subset: ECOM, STORE | | enum |
| quantity | INTEGER | R | units | | 1–10 |
| price_history_id | VARCHAR(23) | R | | FK → price_history | its SKU and date = the order's sku_id and order_date |
| cost_record_id | VARCHAR(25) | R | | FK → cost_record | effective on order_date |
| unit_list_price | NUMERIC(12,2) | R | INR | | = price_history.list_price |
| gross_amount | NUMERIC(12,2) | R | INR | | = quantity × unit_list_price |
| discount_amount | NUMERIC(12,2) | R | INR | | = Σ APPLIED order_promotion.discount_amount |
| net_amount | NUMERIC(12,2) | R | INR | | = gross − discount > 0 |
| unit_landed_cost | NUMERIC(12,2) | R | INR | | = cost_record.landed_cost |
| gross_margin_amount | NUMERIC(12,2) | R | INR | | = net − quantity × unit_landed_cost |
| order_status | VARCHAR(10) | R | `OrderStatus`: FULFILLED, CANCELLED | | CANCELLED is excluded from units_sold |

### 1.9 `price_decision` — decision record (stored inputs + outcome)

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| decision_id | VARCHAR(15) | R | `^DEC-\d{4}-\d{6}$` | PK | |
| sku_id | VARCHAR(10) | R | | FK → product | |
| decision_ts | TIMESTAMPTZ | R | 06:00Z on the decision date | | |
| effective_date | DATE | R | | U(sku_id, effective_date) | ≥ date(decision_ts) |
| trigger | VARCHAR(16) | R | `DecisionTrigger`: INITIAL, WEEKLY_REVIEW, COST_CHANGE, STOCKOUT_EVENT, MANUAL | | enum |
| decision_source | VARCHAR(16) | R | `DecisionSource`: ENGINE, MANUAL_OVERRIDE | | enum |
| previous_decision_id | VARCHAR(15) | O | | FK → price_decision (self) | null iff trigger = INITIAL |
| cost_record_id | VARCHAR(25) | R | | FK → cost_record | effective on effective_date |
| landed_cost | NUMERIC(12,2) | R | INR | | = cost_record.landed_cost |
| prev_list_price | NUMERIC(12,2) | O | INR | | null for INITIAL |
| floor_price | NUMERIC(12,2) | R | INR | | recomputable: roundUp(max(landed / (1 − min_margin), map ?? 0)) |
| ceiling_price | NUMERIC(12,2) | R | INR | | = mrp |
| competitor_ref_price | NUMERIC(12,2) | O | INR | | median of USED observations; null if none used |
| competitor_fresh_count | INTEGER | R | count | | = count of USED bridge rows |
| competitor_stale_count | INTEGER | R | count | | = count of EXCLUDED_STALE bridge rows |
| on_hand_units | INTEGER | R | units | | = previous day's closing_on_hand |
| demand_7d_units | INTEGER | R | units | | = Σ units_sold over the 7 days before effective_date |
| demand_28d_units | INTEGER | R | units | | = Σ units_sold over the 28 days before effective_date |
| velocity_ratio | NUMERIC(8,4) | R | ratio | | = (d7 / 7) / (d28 / 28), safe when d28 = 0 |
| days_of_cover | NUMERIC(8,2) | R | days | | |
| target_price | NUMERIC(12,2) | R | INR | | engine step S5–S6 output |
| final_list_price | NUMERIC(12,2) | R | INR | | in [floor, ceiling] (business rule) |
| markdown_discount_pct | NUMERIC(6,4) | R | fraction | | combined applied markdown |
| expected_net_price | NUMERIC(12,2) | R | INR | | = final_list × (1 − markdown) |
| margin_pct | NUMERIC(6,4) | R | fraction | | = (net − landed) / net (business rule: net basis) |
| required_margin_pct | NUMERIC(6,4) | R | fraction | | min_promo_margin_pct if any promotion applied, else min_margin_pct |
| decision_type | VARCHAR(8) | R | `DecisionType`: INCREASE, DECREASE, HOLD | | from the sign of final − prev |
| primary_reason_code | VARCHAR(32) | R | `ReasonCode` | | |
| reason_codes | VARCHAR(400) | R | pipe-delimited `ReasonCode`s | | non-empty; every token is a ReasonCode |
| explanation | TEXT | R | JSON array of explanation steps (S1–S12) | | parseable JSON |
| rule_version | VARCHAR(20) | R | `pricing-rules-v1` | | |
| status | VARCHAR(10) | R | `DecisionStatus`: PROPOSED, ESCALATED, APPROVED, REJECTED, APPLIED, SUPERSEDED | | = last `price_decision_status_event.to_status` |

---

## 2. Bridge tables (N:M)

### 2.1 `promotion_product` — Promotion ⇄ Product

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| promotion_id | VARCHAR(17) | R | | PK (1/2), FK → promotion | |
| sku_id | VARCHAR(10) | R | | PK (2/2), FK → product | |
| added_at | TIMESTAMPTZ | R | UTC | | ≤ promotion.start_date |

### 2.2 `order_promotion` — Order ⇄ Promotion (coupon attempts)

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| order_id | VARCHAR(16) | R | | PK (1/2), FK → customer_order | |
| promotion_id | VARCHAR(17) | R | | PK (2/2), FK → promotion | |
| sequence_no | INTEGER | R | 1, 2, … | | order in which the stack resolver evaluated it |
| coupon_presented | BOOLEAN | R | | | true when the customer presented the coupon |
| application_status | VARCHAR(8) | R | `ApplicationStatus`: APPLIED, REJECTED | | enum |
| rejection_reason | VARCHAR(32) | O | `ReasonCode` (PROMO_REJECTED_*) | | required iff REJECTED |
| discount_amount | NUMERIC(12,2) | R | INR | | 0 if REJECTED |

### 2.3 `price_decision_promotion` — PriceDecision ⇄ Promotion (markdowns considered)

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| decision_id | VARCHAR(15) | R | | PK (1/2), FK → price_decision | |
| promotion_id | VARCHAR(17) | R | | PK (2/2), FK → promotion | |
| application_status | VARCHAR(8) | R | `ApplicationStatus`: APPLIED, REJECTED | | enum |
| reason_code | VARCHAR(32) | R | `ReasonCode` (PROMO_APPLIED, PROMO_REJECTED_*, PROMO_SUPPRESSED_STOCKOUT) | | consistent with application_status |
| discount_pct_effective | NUMERIC(6,4) | R | fraction | | effective discount contributed; 0 if REJECTED |

### 2.4 `price_decision_observation` — PriceDecision ⇄ CompetitorObservation (observations considered)

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| decision_id | VARCHAR(15) | R | | PK (1/2), FK → price_decision | |
| observation_id | VARCHAR(16) | R | | PK (2/2), FK → competitor_observation | captured and ingested ≤ decision_ts (no look-ahead) |
| usage | VARCHAR(24) | R | `ObservationUsage`: USED, EXCLUDED_STALE, EXCLUDED_LOW_CONFIDENCE, EXCLUDED_SUPERSEDED | | USED ⇒ age_hours ≤ 72 and confidence ≥ 0.80 |
| age_hours | NUMERIC(8,2) | R | hours | | = (decision_ts − source_captured_at) in hours |

---

## 3. Event history tables (append-only lifecycle)

All three share one shape. The parent's `status` equals the last event's `to_status`. Events are never overwritten.
Allowed transitions are in PLAN §4.3 and [er_diagram.md](er_diagram.md#lifecycle-states).

| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| event_id | VARCHAR(15/16) | R | `^PEV-\d{4}-\d{6}$` / `^PMEV-\d{4}-\d{6}$` / `^DEV-\d{4}-\d{7}$` | PK | |
| sku_id / promotion_id / decision_id | as parent PK | R | | FK → product / promotion / price_decision | |
| from_status | VARCHAR(16/10/10) | O | parent status enum | | null only on the parent's first event; = previous event's to_status |
| to_status | VARCHAR(16/10/10) | R | parent status enum | | (from, to) is an allowed transition |
| event_ts | TIMESTAMPTZ | R | UTC | | monotonic per parent |
| actor_role | VARCHAR(20) | R | `ActorRole`: SYSTEM, MERCH_ANALYST, OPS_MANAGER, WAREHOUSE_OPERATOR | | escalation approvals by MERCH_ANALYST or OPS_MANAGER |
| note | VARCHAR(200) | O | free text | | |

| Table | Parent FK | Status enum | event_id |
|---|---|---|---|
| `product_status_event` | sku_id → product | `ProductStatus` | `PEV-2026-000001` |
| `promotion_status_event` | promotion_id → promotion | `PromotionStatus` | `PMEV-2026-000001` |
| `price_decision_status_event` | decision_id → price_decision | `DecisionStatus` | `DEV-2026-0000001` |

---

## 4. Pipeline output tables

Written by `validate`, `detect` and `evaluate`, never by the generator. They never contain ground-truth labels.
They are not in `TableRegistry`, which lists generator data tables only.

### 4.1 `pipeline_run`

| Field | Type | R/O | Allowed / Unit | Validation |
|---|---|---|---|---|
| run_id | VARCHAR(40) | R | PK | |
| command | VARCHAR(16) | R | LOAD, VALIDATE, DETECT, EVALUATE, RUN_ALL | enum |
| seed | BIGINT | O | generator seed of the dataset | |
| scale | VARCHAR(16) | O | starter / full / tiny / custom | |
| dataset_fingerprint | VARCHAR(64) | O | SHA-256 hex from manifest.json | |
| input_path | VARCHAR(500) | O | dataset directory | |
| status | VARCHAR(10) | R | RUNNING, SUCCEEDED, FAILED | enum |
| started_at | TIMESTAMPTZ | R | UTC | |
| finished_at | TIMESTAMPTZ | O | UTC | ≥ started_at |
| summary | TEXT | O | JSON | |

### 4.2 `validation_result`

| Field | Type | R/O | Allowed / Unit | Validation |
|---|---|---|---|---|
| run_id | VARCHAR(40) | R | PK (1/2), FK → pipeline_run | |
| check_id | VARCHAR(12) | R | PK (2/2), e.g. `PK-001`, `FK-012`, `BR-003` | |
| category | VARCHAR(16) | R | PRIMARY_KEY, FOREIGN_KEY, ENUM, ID_FORMAT, DATE_ORDER, NO_LOOK_AHEAD, RECONCILIATION, LIFECYCLE, DUPLICATE, COVERAGE, BUSINESS_RULE | enum |
| table_name | VARCHAR(40) | O | checked table | |
| description | VARCHAR(400) | R | | |
| rows_checked | BIGINT | R | count | ≥ 0 |
| violations | BIGINT | R | count | ≥ 0 |
| status | VARCHAR(4) | R | PASS, FAIL, WARN | enum |
| sample_ids | VARCHAR(1000) | O | up to 5 offending IDs, pipe-delimited | |

### 4.3 `detected_exception`

| Field | Type | R/O | Allowed / Unit | Validation |
|---|---|---|---|---|
| exception_id | VARCHAR(16) | R | PK, `^EXC-\d{4}-\d{7}$` | |
| run_id | VARCHAR(40) | R | FK → pipeline_run | |
| category | VARCHAR(12) | R | `ExceptionCategory`: ANOMALY, OPERATIONAL | = issue_type's category |
| issue_type | VARCHAR(40) | R | `IssueType` (5 anomaly + 4 operational, see below) | enum |
| entity_type | VARCHAR(40) | R | table name of the flagged entity, e.g. `price_decision` | |
| entity_id | VARCHAR(40) | R | ID of the flagged row | exists in entity_type |
| sku_id | VARCHAR(10) | O | FK → product | |
| event_date | DATE | O | business date of the issue | |
| related_ids | VARCHAR(1000) | O | pipe-delimited related IDs | |
| violated_rule | VARCHAR(40) | O | rule identifier, e.g. `LIST_PRICE_GTE_FLOOR` | |
| observed_value | VARCHAR(64) | O | | |
| expected_value | VARCHAR(64) | O | | |
| recommended_action | VARCHAR(32) | O | e.g. BLOCK_PRICE, REJECT_PROMOTION | |
| detail | VARCHAR(1000) | O | human-readable explanation | |

---

## Enumerations

| Enum (Java) | Used by | Values |
|---|---|---|
| `Category` | product.category | GROCERY, PERSONAL_CARE, BEAUTY, HEALTH, HOME_KITCHEN, ELECTRONICS, APPAREL, TOYS, SPORTS, STATIONERY |
| `Uom` | product.uom | EACH, PACK |
| `ProductStatus` | product.status, product_status_event | NEW, ACTIVE, DISCONTINUED |
| `PricePosition` | product.price_position | VALUE (−3%), MATCH (0), PREMIUM (+5%) |
| `CostChangeReason` | cost_record.change_reason | INITIAL, NO_CHANGE, SUPPLIER_INCREASE, SUPPLIER_DECREASE, FREIGHT_CHANGE |
| `Competitor` | competitor_observation.competitor | COMP_ALPHA, COMP_BETA, COMP_GAMMA |
| `ObservationSource` | competitor_observation.source_type | SCRAPE_FEED, PARTNER_FEED, MANUAL_AUDIT |
| `PromoType` | promotion.promo_type | MARKDOWN_PCT, MARKDOWN_AMOUNT, COUPON_PCT, COUPON_AMOUNT |
| `StackGroup` | promotion.stack_group | SITEWIDE, CATEGORY, SKU, COUPON |
| `PromotionStatus` | promotion.status, promotion_status_event | DRAFT, SCHEDULED, ACTIVE, EXPIRED, CANCELLED |
| `RuleType` | promotion_rule.rule_type | EXCLUSIVE, EXCLUDES_PROMOTION, MAX_STACK_COUNT, MAX_COMBINED_DISCOUNT_PCT, MIN_ORDER_QTY |
| `FundingSource` | promotion.funding_source | VENDOR, RETAILER |
| `Channel` | promotion.channel (all), customer_order.channel (ECOM, STORE only) | ALL, ECOM, STORE |
| `CustomerSegment` | customer_order.customer_segment | NEW, REGULAR, LOYALTY |
| `OrderStatus` | customer_order.order_status | FULFILLED, CANCELLED |
| `DecisionTrigger` | price_decision.trigger | INITIAL, WEEKLY_REVIEW, COST_CHANGE, STOCKOUT_EVENT, MANUAL |
| `DecisionSource` | price_decision.decision_source | ENGINE, MANUAL_OVERRIDE |
| `DecisionType` | price_decision.decision_type | INCREASE, DECREASE, HOLD |
| `DecisionStatus` | price_decision.status, price_decision_status_event | PROPOSED, ESCALATED, APPROVED, REJECTED, APPLIED, SUPERSEDED |
| `ReasonCode` | price_decision.primary_reason_code / reason_codes, price_history.change_reason, order_promotion.rejection_reason, price_decision_promotion.reason_code | INITIAL_PRICE, NO_CHANGE, COST_CHANGE, COMPETITOR_ALIGN, COMPETITOR_DATA_STALE, OVERSTOCK_MARKDOWN, LOW_COVER_INCREASE, STOCKOUT_HOLD, FLOOR_ENFORCED, CEILING_ENFORCED, STEP_LIMIT_APPLIED, PRICE_POINT_ROUNDED, PROMO_APPLIED, PROMO_REJECTED_EXCLUSIVE, PROMO_REJECTED_EXCLUDED_PAIR, PROMO_REJECTED_STACK_LIMIT, PROMO_REJECTED_DISCOUNT_CAP, PROMO_REJECTED_MARGIN, PROMO_REJECTED_MIN_QTY, PROMO_SUPPRESSED_STOCKOUT, GUARDRAIL_CONFLICT_ESCALATED, LARGE_CHANGE_ESCALATED, MANUAL_OVERRIDE |
| `ApplicationStatus` | order_promotion, price_decision_promotion | APPLIED, REJECTED |
| `ObservationUsage` | price_decision_observation.usage | USED, EXCLUDED_STALE, EXCLUDED_LOW_CONFIDENCE, EXCLUDED_SUPERSEDED |
| `ActorRole` | *_status_event.actor_role | SYSTEM, MERCH_ANALYST, OPS_MANAGER, WAREHOUSE_OPERATOR |
| `ExceptionCategory` | detected_exception.category | ANOMALY, OPERATIONAL |
| `IssueType` | detected_exception.issue_type, ground truth | ANOMALY: PRICE_BELOW_FLOOR, PROMO_STACK_VIOLATION, STALE_OBSERVATION_AS_CURRENT, MARGIN_BEFORE_DISCOUNT, PRICE_CHANGE_WITHOUT_REASON. OPERATIONAL: COMPETITOR_DATA_STALE, GUARDRAIL_CONFLICT_ESCALATED, STOCKOUT, COUPON_REJECTED |

Ground-truth labels only use the five ANOMALY issue types (PLAN §9). The four OPERATIONAL types are naturally
occurring exceptions reported to the operations queue (PLAN §10). They are never injected or labelled.

Latent simulation parameters (price elasticity, base demand, supply fragility) are **not** columns anywhere. They stay
inside the generator so that the data-generating truth does not leak into features (PLAN §2 assumption 13).
