# Validation

`validate` runs every check below against the loaded database, PostgreSQL or H2 (PLAN.md §10), and writes `validation_summary.json`, `validation_summary.md` and a row per check in `validation_result`. Code: `com.pricingsim.validation`. Tests: `ValidationRunnerIT`.

## Rules

- **Generated from metadata.** PRIMARY_KEY, FOREIGN_KEY, ENUM, ID_FORMAT and DUPLICATE checks are built from `TableRegistry`, so no table, key, enum or ID column can be missed. The database also enforces PK, FK and enum constraints; these checks prove the data independently, for example when a dataset is loaded without constraints.
- **Same queries, both databases.** Every check is SQL that selects the violating IDs, or Java over streamed rows (coverage arithmetic, recomputations with the engine's own calculators). Counting and sampling happen in the database. The same SQL runs on PostgreSQL and H2: window functions, `TO_CHAR`, `INTERVAL` arithmetic, and a dialect-specific regex match (`~` or `REGEXP_LIKE`).
- **Stable IDs.** IDs are `<prefix>-NNN` in the fixed order below, so they are the same from run to run.
- **Status.** A structural check (every category except BUSINESS_RULE) is **FAIL** if it has any violation, otherwise **PASS**. A BUSINESS_RULE check is **WARN** when it has findings: those are the labelled anomalies, which `evaluate` reconciles with the ground truth.
- **Exit code.** `validate` exits 1 if any structural check fails.
- **Tolerances.** Values stored rounded are compared within their rounding tolerance:
  - `change_pct` within 0.00006;
  - the shelf price derived from a 4-dp markdown within `list × 0.00005 + 0.005`.

  All other arithmetic is compared exactly.
- **Deterministic output.** `validation_summary.*` contains only values derived from the data. The run ID is `VALIDATE-<first 16 hex of the dataset fingerprint>`. The execution time goes to `run_log.json` instead.

## Expected result

For a generated dataset, every structural check reports 0 violations. BR-001..005 each report exactly the number of injected anomalies of their type:
- `--anomalies-per-type 0`: 0 findings;
- starter: 5 findings per type;
- full: 50 findings per type.

## Broken fixtures

Each `src/test/resources/fixtures/broken/*.properties` file is a declarative patch on a freshly generated tiny dataset. It can duplicate a row, copy a row with new values, or set values on the first matching row. Where the database would reject the broken row, the fixture drops that constraint first. `ValidationRunnerIT` asserts that each fixture makes **exactly one** structural check fail, with the expected category and table.

| Fixture | Breaks | Fails |
|---|---|---|
| duplicate_primary_key | a `promotion_product` row twice | PRIMARY_KEY |
| orphan_foreign_key | `promotion_product.sku_id` = SKU-999999 | FOREIGN_KEY |
| invalid_enum | `competitor_observation.source_type` = RUMOUR | ENUM |
| invalid_id_format | a coupon code `COUPON-1` | ID_FORMAT |
| reversed_dates | `ingested_at` before `source_captured_at` | DATE_ORDER |
| illegal_transition | promotion event DRAFT → EXPIRED | LIFECYCLE |
| broken_reconciliation | order `gross_margin_amount` changed | RECONCILIATION |
| duplicate_natural_key | a second observation with the same (SKU, competitor, captured_at) | DUPLICATE |

## Checks (183)

The descriptions are those printed in `validation_summary.md`.

### PRIMARY_KEY (16): `RegistryChecks.primaryKeys` (from `TableRegistry`)

| ID | Table | Check |
|---|---|---|
| PK-001 | product | Primary key (sku_id) is unique and not null |
| PK-002 | product_status_event | Primary key (event_id) is unique and not null |
| PK-003 | cost_record | Primary key (cost_record_id) is unique and not null |
| PK-004 | promotion | Primary key (promotion_id) is unique and not null |
| PK-005 | promotion_rule | Primary key (rule_id) is unique and not null |
| PK-006 | promotion_product | Primary key (promotion_id, sku_id) is unique and not null |
| PK-007 | promotion_status_event | Primary key (event_id) is unique and not null |
| PK-008 | competitor_observation | Primary key (observation_id) is unique and not null |
| PK-009 | price_decision | Primary key (decision_id) is unique and not null |
| PK-010 | price_decision_promotion | Primary key (decision_id, promotion_id) is unique and not null |
| PK-011 | price_decision_observation | Primary key (decision_id, observation_id) is unique and not null |
| PK-012 | price_decision_status_event | Primary key (event_id) is unique and not null |
| PK-013 | price_history | Primary key (price_history_id) is unique and not null |
| PK-014 | inventory_snapshot | Primary key (inventory_snapshot_id) is unique and not null |
| PK-015 | customer_order | Primary key (order_id) is unique and not null |
| PK-016 | order_promotion | Primary key (order_id, promotion_id) is unique and not null |

### FOREIGN_KEY (25): `RegistryChecks.foreignKeys` (from `TableRegistry`)

| ID | Table | Check |
|---|---|---|
| FK-001 | product_status_event | product_status_event.sku_id references product.sku_id |
| FK-002 | cost_record | cost_record.sku_id references product.sku_id |
| FK-003 | promotion_rule | promotion_rule.promotion_id references promotion.promotion_id |
| FK-004 | promotion_rule | promotion_rule.target_promotion_id references promotion.promotion_id |
| FK-005 | promotion_product | promotion_product.promotion_id references promotion.promotion_id |
| FK-006 | promotion_product | promotion_product.sku_id references product.sku_id |
| FK-007 | promotion_status_event | promotion_status_event.promotion_id references promotion.promotion_id |
| FK-008 | competitor_observation | competitor_observation.sku_id references product.sku_id |
| FK-009 | price_decision | price_decision.sku_id references product.sku_id |
| FK-010 | price_decision | price_decision.previous_decision_id references price_decision.decision_id |
| FK-011 | price_decision | price_decision.cost_record_id references cost_record.cost_record_id |
| FK-012 | price_decision_promotion | price_decision_promotion.decision_id references price_decision.decision_id |
| FK-013 | price_decision_promotion | price_decision_promotion.promotion_id references promotion.promotion_id |
| FK-014 | price_decision_observation | price_decision_observation.decision_id references price_decision.decision_id |
| FK-015 | price_decision_observation | price_decision_observation.observation_id references competitor_observation.observation_id |
| FK-016 | price_decision_status_event | price_decision_status_event.decision_id references price_decision.decision_id |
| FK-017 | price_history | price_history.sku_id references product.sku_id |
| FK-018 | price_history | price_history.source_decision_id references price_decision.decision_id |
| FK-019 | price_history | price_history.cost_record_id references cost_record.cost_record_id |
| FK-020 | inventory_snapshot | inventory_snapshot.sku_id references product.sku_id |
| FK-021 | customer_order | customer_order.sku_id references product.sku_id |
| FK-022 | customer_order | customer_order.price_history_id references price_history.price_history_id |
| FK-023 | customer_order | customer_order.cost_record_id references cost_record.cost_record_id |
| FK-024 | order_promotion | order_promotion.order_id references customer_order.order_id |
| FK-025 | order_promotion | order_promotion.promotion_id references promotion.promotion_id |

### ENUM (36): `RegistryChecks.enums` (from `TableRegistry`)

| ID | Table | Check |
|---|---|---|
| EN-001 | product | product.category is one of 10 allowed values |
| EN-002 | product | product.uom is one of 2 allowed values |
| EN-003 | product | product.status is one of 3 allowed values |
| EN-004 | product | product.price_position is one of 3 allowed values |
| EN-005 | product_status_event | product_status_event.from_status is one of 3 allowed values |
| EN-006 | product_status_event | product_status_event.to_status is one of 3 allowed values |
| EN-007 | product_status_event | product_status_event.actor_role is one of 4 allowed values |
| EN-008 | cost_record | cost_record.change_reason is one of 5 allowed values |
| EN-009 | promotion | promotion.promo_type is one of 4 allowed values |
| EN-010 | promotion | promotion.stack_group is one of 4 allowed values |
| EN-011 | promotion | promotion.channel is one of 3 allowed values |
| EN-012 | promotion | promotion.funding_source is one of 2 allowed values |
| EN-013 | promotion | promotion.status is one of 5 allowed values |
| EN-014 | promotion_rule | promotion_rule.rule_type is one of 5 allowed values |
| EN-015 | promotion_status_event | promotion_status_event.from_status is one of 5 allowed values |
| EN-016 | promotion_status_event | promotion_status_event.to_status is one of 5 allowed values |
| EN-017 | promotion_status_event | promotion_status_event.actor_role is one of 4 allowed values |
| EN-018 | competitor_observation | competitor_observation.competitor is one of 3 allowed values |
| EN-019 | competitor_observation | competitor_observation.source_type is one of 3 allowed values |
| EN-020 | price_decision | price_decision.trigger is one of 5 allowed values |
| EN-021 | price_decision | price_decision.decision_source is one of 2 allowed values |
| EN-022 | price_decision | price_decision.decision_type is one of 3 allowed values |
| EN-023 | price_decision | price_decision.primary_reason_code is one of 23 allowed values |
| EN-024 | price_decision | price_decision.status is one of 6 allowed values |
| EN-025 | price_decision_promotion | price_decision_promotion.application_status is one of 2 allowed values |
| EN-026 | price_decision_promotion | price_decision_promotion.reason_code is one of 23 allowed values |
| EN-027 | price_decision_observation | price_decision_observation.usage is one of 4 allowed values |
| EN-028 | price_decision_status_event | price_decision_status_event.from_status is one of 6 allowed values |
| EN-029 | price_decision_status_event | price_decision_status_event.to_status is one of 6 allowed values |
| EN-030 | price_decision_status_event | price_decision_status_event.actor_role is one of 4 allowed values |
| EN-031 | price_history | price_history.change_reason is one of 23 allowed values |
| EN-032 | customer_order | customer_order.customer_segment is one of 3 allowed values |
| EN-033 | customer_order | customer_order.channel is one of 2 allowed values |
| EN-034 | customer_order | customer_order.order_status is one of 2 allowed values |
| EN-035 | order_promotion | order_promotion.application_status is one of 2 allowed values |
| EN-036 | order_promotion | order_promotion.rejection_reason is one of 23 allowed values |

### ID_FORMAT (43): `RegistryChecks.idFormats` (regexes from `IdPatterns` via `TableRegistry`, plus embedded fields)

| ID | Table | Check |
|---|---|---|
| ID-001 | product | product.sku_id matches ^SKU-(\d{6})$ |
| ID-002 | product_status_event | product_status_event.event_id matches ^PEV-(\d{4})-(\d{6})$ |
| ID-003 | product_status_event | product_status_event.sku_id matches ^SKU-(\d{6})$ |
| ID-004 | cost_record | cost_record.cost_record_id matches ^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$ |
| ID-005 | cost_record | cost_record.sku_id matches ^SKU-(\d{6})$ |
| ID-006 | promotion | promotion.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ |
| ID-007 | promotion | promotion.coupon_code matches ^CPN-(\d{4})-(\d{6})$ |
| ID-008 | promotion_rule | promotion_rule.rule_id matches ^RULE-(\d{4})-(\d{6})$ |
| ID-009 | promotion_rule | promotion_rule.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ |
| ID-010 | promotion_rule | promotion_rule.target_promotion_id matches ^PROMO-(\d{4})-(\d{6})$ |
| ID-011 | promotion_product | promotion_product.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ |
| ID-012 | promotion_product | promotion_product.sku_id matches ^SKU-(\d{6})$ |
| ID-013 | promotion_status_event | promotion_status_event.event_id matches ^PMEV-(\d{4})-(\d{6})$ |
| ID-014 | promotion_status_event | promotion_status_event.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ |
| ID-015 | competitor_observation | competitor_observation.observation_id matches ^OBS-(\d{4})-(\d{7})$ |
| ID-016 | competitor_observation | competitor_observation.sku_id matches ^SKU-(\d{6})$ |
| ID-017 | price_decision | price_decision.decision_id matches ^DEC-(\d{4})-(\d{6})$ |
| ID-018 | price_decision | price_decision.sku_id matches ^SKU-(\d{6})$ |
| ID-019 | price_decision | price_decision.previous_decision_id matches ^DEC-(\d{4})-(\d{6})$ |
| ID-020 | price_decision | price_decision.cost_record_id matches ^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$ |
| ID-021 | price_decision_promotion | price_decision_promotion.decision_id matches ^DEC-(\d{4})-(\d{6})$ |
| ID-022 | price_decision_promotion | price_decision_promotion.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ |
| ID-023 | price_decision_observation | price_decision_observation.decision_id matches ^DEC-(\d{4})-(\d{6})$ |
| ID-024 | price_decision_observation | price_decision_observation.observation_id matches ^OBS-(\d{4})-(\d{7})$ |
| ID-025 | price_decision_status_event | price_decision_status_event.event_id matches ^DEV-(\d{4})-(\d{7})$ |
| ID-026 | price_decision_status_event | price_decision_status_event.decision_id matches ^DEC-(\d{4})-(\d{6})$ |
| ID-027 | price_history | price_history.price_history_id matches ^PRC-(SKU-\d{6})-(\d{8})$ |
| ID-028 | price_history | price_history.sku_id matches ^SKU-(\d{6})$ |
| ID-029 | price_history | price_history.source_decision_id matches ^DEC-(\d{4})-(\d{6})$ |
| ID-030 | price_history | price_history.cost_record_id matches ^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$ |
| ID-031 | inventory_snapshot | inventory_snapshot.inventory_snapshot_id matches ^INV-(SKU-\d{6})-(\d{8})$ |
| ID-032 | inventory_snapshot | inventory_snapshot.sku_id matches ^SKU-(\d{6})$ |
| ID-033 | customer_order | customer_order.order_id matches ^ORD-(\d{4})-(\d{7})$ |
| ID-034 | customer_order | customer_order.sku_id matches ^SKU-(\d{6})$ |
| ID-035 | customer_order | customer_order.customer_ref matches ^CUST-(\d{7})$ |
| ID-036 | customer_order | customer_order.price_history_id matches ^PRC-(SKU-\d{6})-(\d{8})$ |
| ID-037 | customer_order | customer_order.cost_record_id matches ^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$ |
| ID-038 | order_promotion | order_promotion.order_id matches ^ORD-(\d{4})-(\d{7})$ |
| ID-039 | order_promotion | order_promotion.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ |
| ID-040 | price_history | PRC id embeds sku_id and price_date |
| ID-041 | inventory_snapshot | INV id embeds sku_id and snapshot_date |
| ID-042 | cost_record | COST id embeds sku_id and cost_month |
| ID-043 | customer_order | Order price_history_id embeds its sku_id and order_date |

### DATE_ORDER (13): `DateOrderChecks`

| ID | Table | Check |
|---|---|---|
| DT-001 | cost_record | Cost periods per SKU have no gap or overlap (effective_to = next effective_from; null only on the latest) |
| DT-002 | cost_record | effective_from < effective_to |
| DT-003 | promotion | Promotion start_date <= end_date |
| DT-004 | promotion_rule | Rule effective window lies inside its promotion window |
| DT-005 | promotion | Promotion created before its start date |
| DT-006 | promotion_product | Promotion SKU added no later than the promotion start |
| DT-007 | product | Product created no later than launch, discontinued after launch |
| DT-008 | competitor_observation | ingested_at >= source_captured_at |
| DT-009 | customer_order | order_date = UTC date of order_ts |
| DT-010 | price_history | price_history.price_date inside the product active window [launch, discontinued) |
| DT-011 | inventory_snapshot | inventory_snapshot.snapshot_date inside the product active window [launch, discontinued) |
| DT-012 | customer_order | customer_order.order_date inside the product active window [launch, discontinued) |
| DT-013 | price_decision | price_decision.effective_date inside the product active window [launch, discontinued) |

### NO_LOOK_AHEAD (4): `NoLookAheadChecks`

| ID | Table | Check |
|---|---|---|
| LA-001 | price_decision_observation | Considered observations were captured and ingested by decision_ts |
| LA-002 | price_decision | decision_ts falls on the effective date (decisions take effect the same day) |
| LA-003 | price_decision | demand_7d / demand_28d = units sold in the 7 / 28 days before effective_date |
| LA-004 | price_decision | on_hand_units = opening + receipts of the effective date |

### RECONCILIATION (18): `ReconciliationChecks`

| ID | Table | Check |
|---|---|---|
| RC-001 | cost_record | landed_cost = unit_cost + freight_cost |
| RC-002 | cost_record | change_pct = landed / previous landed - 1 (4 dp), null on the first record |
| RC-003 | inventory_snapshot | closing = opening + receipts - sold + adjustment; lost = demand - sold; stockout_flag = (closing = 0 or lost > 0); 0 <= sold <= demand; adjustment <= 0 |
| RC-004 | inventory_snapshot | opening_on_hand = previous day's closing_on_hand |
| RC-005 | inventory_snapshot | units_sold = sum of FULFILLED order quantity for the SKU-day |
| RC-006 | inventory_snapshot | days_of_cover = closing / max(average units sold over the trailing 28 rows, 0.1) |
| RC-007 | customer_order | gross = qty x unit_list_price; net = gross - discount > 0; gross_margin = net - qty x unit_landed_cost |
| RC-008 | customer_order | discount_amount = sum of APPLIED order_promotion discounts |
| RC-009 | customer_order | unit_list_price = price_history.list_price; unit_landed_cost = cost_record.landed_cost; same cost record as the day's price |
| RC-010 | order_promotion | Rejected coupons carry a reason and no discount; applied ones no reason |
| RC-011 | price_history | previous_list_price = previous day's list_price (null on the first day) |
| RC-012 | price_history | list and shelf price equal the source decision's final_list_price and expected_net_price; shelf <= list |
| RC-013 | price_history | cost_record_id is the SKU's cost record for the month of price_date |
| RC-014 | price_decision | landed_cost = cost record landed_cost; ceiling = MRP; cost record of the effective month |
| RC-015 | price_decision | competitor fresh / stale counts = USED / EXCLUDED_STALE bridge rows |
| RC-016 | price_decision | expected_net = final x (1 - markdown) within rounding; decision_type matches the sign of final - prev |
| RC-017 | price_decision | required_margin = min_promo_margin when a markdown applied, else min_margin |
| RC-018 | price_decision | floor_price, velocity_ratio and days_of_cover recomputed from cost, product and sales history |

### LIFECYCLE (11): `LifecycleTransitionChecks`

| ID | Table | Check |
|---|---|---|
| LC-001 | product_status_event | product_status_event: allowed transitions, each continuing from the previous event, timestamps increasing |
| LC-002 | promotion_status_event | promotion_status_event: allowed transitions, each continuing from the previous event, timestamps increasing |
| LC-003 | price_decision_status_event | price_decision_status_event: allowed transitions, each continuing from the previous event, timestamps increasing |
| LC-004 | product | product.status equals the last product_status_event.to_status |
| LC-005 | promotion | promotion.status equals the last promotion_status_event.to_status |
| LC-006 | price_decision | price_decision.status equals the last price_decision_status_event.to_status |
| LC-007 | product | status DISCONTINUED exactly when discontinued_date is set |
| LC-008 | order_promotion | No promotion applied to an order at or after its cancellation |
| LC-009 | price_decision_promotion | No markdown applied by a decision at or after its cancellation |
| LC-010 | price_decision | A SUPERSEDED decision has a successor; only the latest per SKU is APPLIED |
| LC-011 | price_decision | previous_decision_id is null exactly for INITIAL decisions and points to the same SKU |

### DUPLICATE (7): `RegistryChecks.duplicates`

| ID | Table | Check |
|---|---|---|
| DU-001 | price_history | Natural key (sku_id, price_date) is unique |
| DU-002 | inventory_snapshot | Natural key (sku_id, snapshot_date) is unique |
| DU-003 | price_decision | Natural key (sku_id, effective_date) is unique |
| DU-004 | competitor_observation | Natural key (sku_id, competitor, source_captured_at) is unique |
| DU-005 | cost_record | Natural key (sku_id, cost_month) is unique |
| DU-006 | promotion | Natural key (coupon_code) is unique |
| DU-007 | promotion_rule | Natural key (promotion_id, rule_type, target_promotion_id) is unique |

### COVERAGE (5): `CoverageChecks`

| ID | Table | Check |
|---|---|---|
| CV-001 | price_history | One price_history row per SKU per active day |
| CV-002 | inventory_snapshot | One inventory_snapshot row per SKU per active day |
| CV-003 | cost_record | One cost record per SKU per active month |
| CV-004 | promotion | Every promotion has at least one rule and one SKU |
| CV-005 | product | Every product has exactly one INITIAL decision, on its first active day |

### BUSINESS_RULE (5): `BusinessRuleChecks` (the `detection.rules` detectors)

| ID | Table | Check |
|---|---|---|
| BR-001 | price_decision | PRICE_BELOW_FLOOR: Decision final_list_price below the floor recomputed from its cost record and product |
| BR-002 | customer_order | PROMO_STACK_VIOLATION: Order's APPLIED promotions break exclusivity, excluded pairs, stack limit, discount cap or min quantity |
| BR-003 | price_decision | STALE_OBSERVATION_AS_CURRENT: Decision observation USED although captured more than 72 h before decision_ts |
| BR-004 | price_decision | MARGIN_BEFORE_DISCOUNT: Stored margin_pct differs from the net-price margin, which is below the required margin |
| BR-005 | price_history | PRICE_CHANGE_WITHOUT_REASON: List price changed from the previous day without a change_reason or a source decision |

Starter result (seed 20261022): 183 checks, 178 passed, 0 failed, 5 warnings. Business-rule findings: PRICE_BELOW_FLOOR 5, PROMO_STACK_VIOLATION 5, STALE_OBSERVATION_AS_CURRENT 5, MARGIN_BEFORE_DISCOUNT 5, PRICE_CHANGE_WITHOUT_REASON 5.
