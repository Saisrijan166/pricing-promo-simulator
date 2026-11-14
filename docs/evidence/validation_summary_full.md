# Validation summary

| Item | Value |
|---|---|
| Run | `VALIDATE-1a275c7351db1867` |
| Seed / scale | 20261022 / full |
| Dataset fingerprint | `1a275c7351db18670ba27958c174590c1ed76d3d0853fcac19cd25e2b4907334` |
| Checks | 183 (178 structural, 5 business-rule) |
| Passed / failed / warnings | 178 / 0 / 5 |
| **Structural failures** | **0** |
| **Business-rule findings** | **250** |

## Business-rule findings

| Issue type | Findings |
|---|---|
| PRICE_BELOW_FLOOR | 50 |
| PROMO_STACK_VIOLATION | 50 |
| STALE_OBSERVATION_AS_CURRENT | 50 |
| MARGIN_BEFORE_DISCOUNT | 50 |
| PRICE_CHANGE_WITHOUT_REASON | 50 |

## Checks

| ID | Category | Table | Check | Rows | Violations | Status | Sample IDs |
|---|---|---|---|---|---|---|---|
| PK-001 | PRIMARY_KEY | product | Primary key (sku_id) is unique and not null | 5000 | 0 | PASS |  |
| PK-002 | PRIMARY_KEY | product_status_event | Primary key (event_id) is unique and not null | 5379 | 0 | PASS |  |
| PK-003 | PRIMARY_KEY | cost_record | Primary key (cost_record_id) is unique and not null | 29000 | 0 | PASS |  |
| PK-004 | PRIMARY_KEY | promotion | Primary key (promotion_id) is unique and not null | 2000 | 0 | PASS |  |
| PK-005 | PRIMARY_KEY | promotion_rule | Primary key (rule_id) is unique and not null | 5252 | 0 | PASS |  |
| PK-006 | PRIMARY_KEY | promotion_product | Primary key (promotion_id, sku_id) is unique and not null | 74320 | 0 | PASS |  |
| PK-007 | PRIMARY_KEY | promotion_status_event | Primary key (event_id) is unique and not null | 7744 | 0 | PASS |  |
| PK-008 | PRIMARY_KEY | competitor_observation | Primary key (observation_id) is unique and not null | 534705 | 0 | PASS |  |
| PK-009 | PRIMARY_KEY | price_decision | Primary key (decision_id) is unique and not null | 147339 | 0 | PASS |  |
| PK-010 | PRIMARY_KEY | price_decision_promotion | Primary key (decision_id, promotion_id) is unique and not null | 45385 | 0 | PASS |  |
| PK-011 | PRIMARY_KEY | price_decision_observation | Primary key (decision_id, observation_id) is unique and not null | 305541 | 0 | PASS |  |
| PK-012 | PRIMARY_KEY | price_decision_status_event | Primary key (event_id) is unique and not null | 585552 | 0 | PASS |  |
| PK-013 | PRIMARY_KEY | price_history | Primary key (price_history_id) is unique and not null | 864016 | 0 | PASS |  |
| PK-014 | PRIMARY_KEY | inventory_snapshot | Primary key (inventory_snapshot_id) is unique and not null | 864016 | 0 | PASS |  |
| PK-015 | PRIMARY_KEY | customer_order | Primary key (order_id) is unique and not null | 944948 | 0 | PASS |  |
| PK-016 | PRIMARY_KEY | order_promotion | Primary key (order_id, promotion_id) is unique and not null | 242334 | 0 | PASS |  |
| FK-001 | FOREIGN_KEY | product_status_event | product_status_event.sku_id references product.sku_id | 5379 | 0 | PASS |  |
| FK-002 | FOREIGN_KEY | cost_record | cost_record.sku_id references product.sku_id | 29000 | 0 | PASS |  |
| FK-003 | FOREIGN_KEY | promotion_rule | promotion_rule.promotion_id references promotion.promotion_id | 5252 | 0 | PASS |  |
| FK-004 | FOREIGN_KEY | promotion_rule | promotion_rule.target_promotion_id references promotion.promotion_id | 5252 | 0 | PASS |  |
| FK-005 | FOREIGN_KEY | promotion_product | promotion_product.promotion_id references promotion.promotion_id | 74320 | 0 | PASS |  |
| FK-006 | FOREIGN_KEY | promotion_product | promotion_product.sku_id references product.sku_id | 74320 | 0 | PASS |  |
| FK-007 | FOREIGN_KEY | promotion_status_event | promotion_status_event.promotion_id references promotion.promotion_id | 7744 | 0 | PASS |  |
| FK-008 | FOREIGN_KEY | competitor_observation | competitor_observation.sku_id references product.sku_id | 534705 | 0 | PASS |  |
| FK-009 | FOREIGN_KEY | price_decision | price_decision.sku_id references product.sku_id | 147339 | 0 | PASS |  |
| FK-010 | FOREIGN_KEY | price_decision | price_decision.previous_decision_id references price_decision.decision_id | 147339 | 0 | PASS |  |
| FK-011 | FOREIGN_KEY | price_decision | price_decision.cost_record_id references cost_record.cost_record_id | 147339 | 0 | PASS |  |
| FK-012 | FOREIGN_KEY | price_decision_promotion | price_decision_promotion.decision_id references price_decision.decision_id | 45385 | 0 | PASS |  |
| FK-013 | FOREIGN_KEY | price_decision_promotion | price_decision_promotion.promotion_id references promotion.promotion_id | 45385 | 0 | PASS |  |
| FK-014 | FOREIGN_KEY | price_decision_observation | price_decision_observation.decision_id references price_decision.decision_id | 305541 | 0 | PASS |  |
| FK-015 | FOREIGN_KEY | price_decision_observation | price_decision_observation.observation_id references competitor_observation.observation_id | 305541 | 0 | PASS |  |
| FK-016 | FOREIGN_KEY | price_decision_status_event | price_decision_status_event.decision_id references price_decision.decision_id | 585552 | 0 | PASS |  |
| FK-017 | FOREIGN_KEY | price_history | price_history.sku_id references product.sku_id | 864016 | 0 | PASS |  |
| FK-018 | FOREIGN_KEY | price_history | price_history.source_decision_id references price_decision.decision_id | 864016 | 0 | PASS |  |
| FK-019 | FOREIGN_KEY | price_history | price_history.cost_record_id references cost_record.cost_record_id | 864016 | 0 | PASS |  |
| FK-020 | FOREIGN_KEY | inventory_snapshot | inventory_snapshot.sku_id references product.sku_id | 864016 | 0 | PASS |  |
| FK-021 | FOREIGN_KEY | customer_order | customer_order.sku_id references product.sku_id | 944948 | 0 | PASS |  |
| FK-022 | FOREIGN_KEY | customer_order | customer_order.price_history_id references price_history.price_history_id | 944948 | 0 | PASS |  |
| FK-023 | FOREIGN_KEY | customer_order | customer_order.cost_record_id references cost_record.cost_record_id | 944948 | 0 | PASS |  |
| FK-024 | FOREIGN_KEY | order_promotion | order_promotion.order_id references customer_order.order_id | 242334 | 0 | PASS |  |
| FK-025 | FOREIGN_KEY | order_promotion | order_promotion.promotion_id references promotion.promotion_id | 242334 | 0 | PASS |  |
| EN-001 | ENUM | product | product.category is one of 10 allowed values | 5000 | 0 | PASS |  |
| EN-002 | ENUM | product | product.uom is one of 2 allowed values | 5000 | 0 | PASS |  |
| EN-003 | ENUM | product | product.status is one of 3 allowed values | 5000 | 0 | PASS |  |
| EN-004 | ENUM | product | product.price_position is one of 3 allowed values | 5000 | 0 | PASS |  |
| EN-005 | ENUM | product_status_event | product_status_event.from_status is one of 3 allowed values | 5379 | 0 | PASS |  |
| EN-006 | ENUM | product_status_event | product_status_event.to_status is one of 3 allowed values | 5379 | 0 | PASS |  |
| EN-007 | ENUM | product_status_event | product_status_event.actor_role is one of 4 allowed values | 5379 | 0 | PASS |  |
| EN-008 | ENUM | cost_record | cost_record.change_reason is one of 5 allowed values | 29000 | 0 | PASS |  |
| EN-009 | ENUM | promotion | promotion.promo_type is one of 4 allowed values | 2000 | 0 | PASS |  |
| EN-010 | ENUM | promotion | promotion.stack_group is one of 4 allowed values | 2000 | 0 | PASS |  |
| EN-011 | ENUM | promotion | promotion.channel is one of 3 allowed values | 2000 | 0 | PASS |  |
| EN-012 | ENUM | promotion | promotion.funding_source is one of 2 allowed values | 2000 | 0 | PASS |  |
| EN-013 | ENUM | promotion | promotion.status is one of 5 allowed values | 2000 | 0 | PASS |  |
| EN-014 | ENUM | promotion_rule | promotion_rule.rule_type is one of 5 allowed values | 5252 | 0 | PASS |  |
| EN-015 | ENUM | promotion_status_event | promotion_status_event.from_status is one of 5 allowed values | 7744 | 0 | PASS |  |
| EN-016 | ENUM | promotion_status_event | promotion_status_event.to_status is one of 5 allowed values | 7744 | 0 | PASS |  |
| EN-017 | ENUM | promotion_status_event | promotion_status_event.actor_role is one of 4 allowed values | 7744 | 0 | PASS |  |
| EN-018 | ENUM | competitor_observation | competitor_observation.competitor is one of 3 allowed values | 534705 | 0 | PASS |  |
| EN-019 | ENUM | competitor_observation | competitor_observation.source_type is one of 3 allowed values | 534705 | 0 | PASS |  |
| EN-020 | ENUM | price_decision | price_decision.trigger is one of 5 allowed values | 147339 | 0 | PASS |  |
| EN-021 | ENUM | price_decision | price_decision.decision_source is one of 2 allowed values | 147339 | 0 | PASS |  |
| EN-022 | ENUM | price_decision | price_decision.decision_type is one of 3 allowed values | 147339 | 0 | PASS |  |
| EN-023 | ENUM | price_decision | price_decision.primary_reason_code is one of 23 allowed values | 147339 | 0 | PASS |  |
| EN-024 | ENUM | price_decision | price_decision.status is one of 6 allowed values | 147339 | 0 | PASS |  |
| EN-025 | ENUM | price_decision_promotion | price_decision_promotion.application_status is one of 2 allowed values | 45385 | 0 | PASS |  |
| EN-026 | ENUM | price_decision_promotion | price_decision_promotion.reason_code is one of 23 allowed values | 45385 | 0 | PASS |  |
| EN-027 | ENUM | price_decision_observation | price_decision_observation.usage is one of 4 allowed values | 305541 | 0 | PASS |  |
| EN-028 | ENUM | price_decision_status_event | price_decision_status_event.from_status is one of 6 allowed values | 585552 | 0 | PASS |  |
| EN-029 | ENUM | price_decision_status_event | price_decision_status_event.to_status is one of 6 allowed values | 585552 | 0 | PASS |  |
| EN-030 | ENUM | price_decision_status_event | price_decision_status_event.actor_role is one of 4 allowed values | 585552 | 0 | PASS |  |
| EN-031 | ENUM | price_history | price_history.change_reason is one of 23 allowed values | 864016 | 0 | PASS |  |
| EN-032 | ENUM | customer_order | customer_order.customer_segment is one of 3 allowed values | 944948 | 0 | PASS |  |
| EN-033 | ENUM | customer_order | customer_order.channel is one of 2 allowed values | 944948 | 0 | PASS |  |
| EN-034 | ENUM | customer_order | customer_order.order_status is one of 2 allowed values | 944948 | 0 | PASS |  |
| EN-035 | ENUM | order_promotion | order_promotion.application_status is one of 2 allowed values | 242334 | 0 | PASS |  |
| EN-036 | ENUM | order_promotion | order_promotion.rejection_reason is one of 23 allowed values | 242334 | 0 | PASS |  |
| ID-001 | ID_FORMAT | product | product.sku_id matches ^SKU-(\d{6})$ | 5000 | 0 | PASS |  |
| ID-002 | ID_FORMAT | product_status_event | product_status_event.event_id matches ^PEV-(\d{4})-(\d{6})$ | 5379 | 0 | PASS |  |
| ID-003 | ID_FORMAT | product_status_event | product_status_event.sku_id matches ^SKU-(\d{6})$ | 5379 | 0 | PASS |  |
| ID-004 | ID_FORMAT | cost_record | cost_record.cost_record_id matches ^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$ | 29000 | 0 | PASS |  |
| ID-005 | ID_FORMAT | cost_record | cost_record.sku_id matches ^SKU-(\d{6})$ | 29000 | 0 | PASS |  |
| ID-006 | ID_FORMAT | promotion | promotion.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ | 2000 | 0 | PASS |  |
| ID-007 | ID_FORMAT | promotion | promotion.coupon_code matches ^CPN-(\d{4})-(\d{6})$ | 2000 | 0 | PASS |  |
| ID-008 | ID_FORMAT | promotion_rule | promotion_rule.rule_id matches ^RULE-(\d{4})-(\d{6})$ | 5252 | 0 | PASS |  |
| ID-009 | ID_FORMAT | promotion_rule | promotion_rule.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ | 5252 | 0 | PASS |  |
| ID-010 | ID_FORMAT | promotion_rule | promotion_rule.target_promotion_id matches ^PROMO-(\d{4})-(\d{6})$ | 5252 | 0 | PASS |  |
| ID-011 | ID_FORMAT | promotion_product | promotion_product.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ | 74320 | 0 | PASS |  |
| ID-012 | ID_FORMAT | promotion_product | promotion_product.sku_id matches ^SKU-(\d{6})$ | 74320 | 0 | PASS |  |
| ID-013 | ID_FORMAT | promotion_status_event | promotion_status_event.event_id matches ^PMEV-(\d{4})-(\d{6})$ | 7744 | 0 | PASS |  |
| ID-014 | ID_FORMAT | promotion_status_event | promotion_status_event.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ | 7744 | 0 | PASS |  |
| ID-015 | ID_FORMAT | competitor_observation | competitor_observation.observation_id matches ^OBS-(\d{4})-(\d{7})$ | 534705 | 0 | PASS |  |
| ID-016 | ID_FORMAT | competitor_observation | competitor_observation.sku_id matches ^SKU-(\d{6})$ | 534705 | 0 | PASS |  |
| ID-017 | ID_FORMAT | price_decision | price_decision.decision_id matches ^DEC-(\d{4})-(\d{6})$ | 147339 | 0 | PASS |  |
| ID-018 | ID_FORMAT | price_decision | price_decision.sku_id matches ^SKU-(\d{6})$ | 147339 | 0 | PASS |  |
| ID-019 | ID_FORMAT | price_decision | price_decision.previous_decision_id matches ^DEC-(\d{4})-(\d{6})$ | 147339 | 0 | PASS |  |
| ID-020 | ID_FORMAT | price_decision | price_decision.cost_record_id matches ^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$ | 147339 | 0 | PASS |  |
| ID-021 | ID_FORMAT | price_decision_promotion | price_decision_promotion.decision_id matches ^DEC-(\d{4})-(\d{6})$ | 45385 | 0 | PASS |  |
| ID-022 | ID_FORMAT | price_decision_promotion | price_decision_promotion.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ | 45385 | 0 | PASS |  |
| ID-023 | ID_FORMAT | price_decision_observation | price_decision_observation.decision_id matches ^DEC-(\d{4})-(\d{6})$ | 305541 | 0 | PASS |  |
| ID-024 | ID_FORMAT | price_decision_observation | price_decision_observation.observation_id matches ^OBS-(\d{4})-(\d{7})$ | 305541 | 0 | PASS |  |
| ID-025 | ID_FORMAT | price_decision_status_event | price_decision_status_event.event_id matches ^DEV-(\d{4})-(\d{7})$ | 585552 | 0 | PASS |  |
| ID-026 | ID_FORMAT | price_decision_status_event | price_decision_status_event.decision_id matches ^DEC-(\d{4})-(\d{6})$ | 585552 | 0 | PASS |  |
| ID-027 | ID_FORMAT | price_history | price_history.price_history_id matches ^PRC-(SKU-\d{6})-(\d{8})$ | 864016 | 0 | PASS |  |
| ID-028 | ID_FORMAT | price_history | price_history.sku_id matches ^SKU-(\d{6})$ | 864016 | 0 | PASS |  |
| ID-029 | ID_FORMAT | price_history | price_history.source_decision_id matches ^DEC-(\d{4})-(\d{6})$ | 864016 | 0 | PASS |  |
| ID-030 | ID_FORMAT | price_history | price_history.cost_record_id matches ^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$ | 864016 | 0 | PASS |  |
| ID-031 | ID_FORMAT | inventory_snapshot | inventory_snapshot.inventory_snapshot_id matches ^INV-(SKU-\d{6})-(\d{8})$ | 864016 | 0 | PASS |  |
| ID-032 | ID_FORMAT | inventory_snapshot | inventory_snapshot.sku_id matches ^SKU-(\d{6})$ | 864016 | 0 | PASS |  |
| ID-033 | ID_FORMAT | customer_order | customer_order.order_id matches ^ORD-(\d{4})-(\d{7})$ | 944948 | 0 | PASS |  |
| ID-034 | ID_FORMAT | customer_order | customer_order.sku_id matches ^SKU-(\d{6})$ | 944948 | 0 | PASS |  |
| ID-035 | ID_FORMAT | customer_order | customer_order.customer_ref matches ^CUST-(\d{7})$ | 944948 | 0 | PASS |  |
| ID-036 | ID_FORMAT | customer_order | customer_order.price_history_id matches ^PRC-(SKU-\d{6})-(\d{8})$ | 944948 | 0 | PASS |  |
| ID-037 | ID_FORMAT | customer_order | customer_order.cost_record_id matches ^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$ | 944948 | 0 | PASS |  |
| ID-038 | ID_FORMAT | order_promotion | order_promotion.order_id matches ^ORD-(\d{4})-(\d{7})$ | 242334 | 0 | PASS |  |
| ID-039 | ID_FORMAT | order_promotion | order_promotion.promotion_id matches ^PROMO-(\d{4})-(\d{6})$ | 242334 | 0 | PASS |  |
| ID-040 | ID_FORMAT | price_history | PRC id embeds sku_id and price_date | 864016 | 0 | PASS |  |
| ID-041 | ID_FORMAT | inventory_snapshot | INV id embeds sku_id and snapshot_date | 864016 | 0 | PASS |  |
| ID-042 | ID_FORMAT | cost_record | COST id embeds sku_id and cost_month | 29000 | 0 | PASS |  |
| ID-043 | ID_FORMAT | customer_order | Order price_history_id embeds its sku_id and order_date | 944948 | 0 | PASS |  |
| DT-001 | DATE_ORDER | cost_record | Cost periods per SKU have no gap or overlap (effective_to = next effective_from; null only on the latest) | 29000 | 0 | PASS |  |
| DT-002 | DATE_ORDER | cost_record | effective_from < effective_to | 29000 | 0 | PASS |  |
| DT-003 | DATE_ORDER | promotion | Promotion start_date <= end_date | 2000 | 0 | PASS |  |
| DT-004 | DATE_ORDER | promotion_rule | Rule effective window lies inside its promotion window | 5252 | 0 | PASS |  |
| DT-005 | DATE_ORDER | promotion | Promotion created before its start date | 2000 | 0 | PASS |  |
| DT-006 | DATE_ORDER | promotion_product | Promotion SKU added no later than the promotion start | 74320 | 0 | PASS |  |
| DT-007 | DATE_ORDER | product | Product created no later than launch, discontinued after launch | 5000 | 0 | PASS |  |
| DT-008 | DATE_ORDER | competitor_observation | ingested_at >= source_captured_at | 534705 | 0 | PASS |  |
| DT-009 | DATE_ORDER | customer_order | order_date = UTC date of order_ts | 944948 | 0 | PASS |  |
| DT-010 | DATE_ORDER | price_history | price_history.price_date inside the product active window [launch, discontinued) | 864016 | 0 | PASS |  |
| DT-011 | DATE_ORDER | inventory_snapshot | inventory_snapshot.snapshot_date inside the product active window [launch, discontinued) | 864016 | 0 | PASS |  |
| DT-012 | DATE_ORDER | customer_order | customer_order.order_date inside the product active window [launch, discontinued) | 944948 | 0 | PASS |  |
| DT-013 | DATE_ORDER | price_decision | price_decision.effective_date inside the product active window [launch, discontinued) | 147339 | 0 | PASS |  |
| LA-001 | NO_LOOK_AHEAD | price_decision_observation | Considered observations were captured and ingested by decision_ts | 305541 | 0 | PASS |  |
| LA-002 | NO_LOOK_AHEAD | price_decision | decision_ts falls on the effective date (decisions take effect the same day) | 147339 | 0 | PASS |  |
| LA-003 | NO_LOOK_AHEAD | price_decision | demand_7d / demand_28d = units sold in the 7 / 28 days before effective_date | 147339 | 0 | PASS |  |
| LA-004 | NO_LOOK_AHEAD | price_decision | on_hand_units = opening + receipts of the effective date | 147339 | 0 | PASS |  |
| RC-001 | RECONCILIATION | cost_record | landed_cost = unit_cost + freight_cost | 29000 | 0 | PASS |  |
| RC-002 | RECONCILIATION | cost_record | change_pct = landed / previous landed - 1 (4 dp), null on the first record | 29000 | 0 | PASS |  |
| RC-003 | RECONCILIATION | inventory_snapshot | closing = opening + receipts - sold + adjustment; lost = demand - sold; stockout_flag = (closing = 0 or lost > 0); 0 <= sold <= demand; adjustment <= 0 | 864016 | 0 | PASS |  |
| RC-004 | RECONCILIATION | inventory_snapshot | opening_on_hand = previous day's closing_on_hand | 864016 | 0 | PASS |  |
| RC-005 | RECONCILIATION | inventory_snapshot | units_sold = sum of FULFILLED order quantity for the SKU-day | 864016 | 0 | PASS |  |
| RC-006 | RECONCILIATION | inventory_snapshot | days_of_cover = closing / max(average units sold over the trailing 28 rows, 0.1) | 864016 | 0 | PASS |  |
| RC-007 | RECONCILIATION | customer_order | gross = qty x unit_list_price; net = gross - discount > 0; gross_margin = net - qty x unit_landed_cost | 944948 | 0 | PASS |  |
| RC-008 | RECONCILIATION | customer_order | discount_amount = sum of APPLIED order_promotion discounts | 944948 | 0 | PASS |  |
| RC-009 | RECONCILIATION | customer_order | unit_list_price = price_history.list_price; unit_landed_cost = cost_record.landed_cost; same cost record as the day's price | 944948 | 0 | PASS |  |
| RC-010 | RECONCILIATION | order_promotion | Rejected coupons carry a reason and no discount; applied ones no reason | 242334 | 0 | PASS |  |
| RC-011 | RECONCILIATION | price_history | previous_list_price = previous day's list_price (null on the first day) | 864016 | 0 | PASS |  |
| RC-012 | RECONCILIATION | price_history | list and shelf price equal the source decision's final_list_price and expected_net_price; shelf <= list | 864016 | 0 | PASS |  |
| RC-013 | RECONCILIATION | price_history | cost_record_id is the SKU's cost record for the month of price_date | 864016 | 0 | PASS |  |
| RC-014 | RECONCILIATION | price_decision | landed_cost = cost record landed_cost; ceiling = MRP; cost record of the effective month | 147339 | 0 | PASS |  |
| RC-015 | RECONCILIATION | price_decision | competitor fresh / stale counts = USED / EXCLUDED_STALE bridge rows | 147339 | 0 | PASS |  |
| RC-016 | RECONCILIATION | price_decision | expected_net = final x (1 - markdown) within rounding; decision_type matches the sign of final - prev | 147339 | 0 | PASS |  |
| RC-017 | RECONCILIATION | price_decision | required_margin = min_promo_margin when a markdown applied, else min_margin | 147339 | 0 | PASS |  |
| RC-018 | RECONCILIATION | price_decision | floor_price, velocity_ratio and days_of_cover recomputed from cost, product and sales history | 147339 | 0 | PASS |  |
| LC-001 | LIFECYCLE | product_status_event | product_status_event: allowed transitions, each continuing from the previous event, timestamps increasing | 5379 | 0 | PASS |  |
| LC-002 | LIFECYCLE | promotion_status_event | promotion_status_event: allowed transitions, each continuing from the previous event, timestamps increasing | 7744 | 0 | PASS |  |
| LC-003 | LIFECYCLE | price_decision_status_event | price_decision_status_event: allowed transitions, each continuing from the previous event, timestamps increasing | 585552 | 0 | PASS |  |
| LC-004 | LIFECYCLE | product | product.status equals the last product_status_event.to_status | 5000 | 0 | PASS |  |
| LC-005 | LIFECYCLE | promotion | promotion.status equals the last promotion_status_event.to_status | 2000 | 0 | PASS |  |
| LC-006 | LIFECYCLE | price_decision | price_decision.status equals the last price_decision_status_event.to_status | 147339 | 0 | PASS |  |
| LC-007 | LIFECYCLE | product | status DISCONTINUED exactly when discontinued_date is set | 5000 | 0 | PASS |  |
| LC-008 | LIFECYCLE | order_promotion | No promotion applied to an order at or after its cancellation | 242334 | 0 | PASS |  |
| LC-009 | LIFECYCLE | price_decision_promotion | No markdown applied by a decision at or after its cancellation | 45385 | 0 | PASS |  |
| LC-010 | LIFECYCLE | price_decision | A SUPERSEDED decision has a successor; only the latest per SKU is APPLIED | 147339 | 0 | PASS |  |
| LC-011 | LIFECYCLE | price_decision | previous_decision_id is null exactly for INITIAL decisions and points to the same SKU | 147339 | 0 | PASS |  |
| DU-001 | DUPLICATE | price_history | Natural key (sku_id, price_date) is unique | 864016 | 0 | PASS |  |
| DU-002 | DUPLICATE | inventory_snapshot | Natural key (sku_id, snapshot_date) is unique | 864016 | 0 | PASS |  |
| DU-003 | DUPLICATE | price_decision | Natural key (sku_id, effective_date) is unique | 147339 | 0 | PASS |  |
| DU-004 | DUPLICATE | competitor_observation | Natural key (sku_id, competitor, source_captured_at) is unique | 534705 | 0 | PASS |  |
| DU-005 | DUPLICATE | cost_record | Natural key (sku_id, cost_month) is unique | 29000 | 0 | PASS |  |
| DU-006 | DUPLICATE | promotion | Natural key (coupon_code) is unique | 2000 | 0 | PASS |  |
| DU-007 | DUPLICATE | promotion_rule | Natural key (promotion_id, rule_type, target_promotion_id) is unique | 5252 | 0 | PASS |  |
| CV-001 | COVERAGE | price_history | One price_history row per SKU per active day | 864016 | 0 | PASS |  |
| CV-002 | COVERAGE | inventory_snapshot | One inventory_snapshot row per SKU per active day | 864016 | 0 | PASS |  |
| CV-003 | COVERAGE | cost_record | One cost record per SKU per active month | 29000 | 0 | PASS |  |
| CV-004 | COVERAGE | promotion | Every promotion has at least one rule and one SKU | 2000 | 0 | PASS |  |
| CV-005 | COVERAGE | product | Every product has exactly one INITIAL decision, on its first active day | 5000 | 0 | PASS |  |
| BR-001 | BUSINESS_RULE | price_decision | PRICE_BELOW_FLOOR: Decision final_list_price below the floor recomputed from its cost record and product | 147339 | 50 | WARN | DEC-2026-006709, DEC-2026-006787, DEC-2026-007213, DEC-2026-007684, DEC-2026-007796 |
| BR-002 | BUSINESS_RULE | customer_order | PROMO_STACK_VIOLATION: Order's APPLIED promotions break exclusivity, excluded pairs, stack limit, discount cap or min quantity | 944948 | 50 | WARN | ORD-2026-0036271, ORD-2026-0054312, ORD-2026-0063359, ORD-2026-0091168, ORD-2026-0112478 |
| BR-003 | BUSINESS_RULE | price_decision | STALE_OBSERVATION_AS_CURRENT: Decision observation USED although captured more than 72 h before decision_ts | 147339 | 50 | WARN | DEC-2026-011895, DEC-2026-013367, DEC-2026-017269, DEC-2026-018919, DEC-2026-020234 |
| BR-004 | BUSINESS_RULE | price_decision | MARGIN_BEFORE_DISCOUNT: Stored margin_pct differs from the net-price margin, which is below the required margin | 147339 | 50 | WARN | DEC-2026-005401, DEC-2026-010998, DEC-2026-011501, DEC-2026-011650, DEC-2026-016758 |
| BR-005 | BUSINESS_RULE | price_history | PRICE_CHANGE_WITHOUT_REASON: List price changed from the previous day without a change_reason or a source decision | 864016 | 50 | WARN | PRC-SKU-000055-20260219, PRC-SKU-000071-20260421, PRC-SKU-000090-20260107, PRC-SKU-000191-20260211, PRC-SKU-000367-20260423 |
