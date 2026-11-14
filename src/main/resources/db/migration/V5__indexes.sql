-- V5: foreign-key and lookup indexes. All NON-unique on purpose: natural-key uniqueness such as (sku_id, price_date)
-- is a validator DUPLICATE check, not a DB constraint (PLAN.md §2, §10). Composite PKs already index their leading column.

-- Master
CREATE INDEX ix_cost_record_sku_month              ON cost_record (sku_id, cost_month);
CREATE INDEX ix_promotion_dates                    ON promotion (start_date, end_date);
CREATE INDEX ix_promotion_coupon_code              ON promotion (coupon_code);
CREATE INDEX ix_promotion_rule_promotion           ON promotion_rule (promotion_id);
CREATE INDEX ix_promotion_rule_target              ON promotion_rule (target_promotion_id);
CREATE INDEX ix_promotion_product_sku              ON promotion_product (sku_id);
CREATE INDEX ix_competitor_observation_sku_comp_ts ON competitor_observation (sku_id, competitor, source_captured_at);

-- Decisions and bridges
CREATE INDEX ix_price_decision_sku_date            ON price_decision (sku_id, effective_date);
CREATE INDEX ix_price_decision_previous            ON price_decision (previous_decision_id);
CREATE INDEX ix_price_decision_cost_record         ON price_decision (cost_record_id);
CREATE INDEX ix_price_decision_promotion_promo     ON price_decision_promotion (promotion_id);
CREATE INDEX ix_price_decision_observation_obs     ON price_decision_observation (observation_id);

-- Daily history
CREATE INDEX ix_price_history_sku_date             ON price_history (sku_id, price_date);
CREATE INDEX ix_price_history_source_decision      ON price_history (source_decision_id);
CREATE INDEX ix_price_history_cost_record          ON price_history (cost_record_id);
CREATE INDEX ix_inventory_snapshot_sku_date        ON inventory_snapshot (sku_id, snapshot_date);

-- Orders
CREATE INDEX ix_customer_order_sku_date            ON customer_order (sku_id, order_date);
CREATE INDEX ix_customer_order_price_history       ON customer_order (price_history_id);
CREATE INDEX ix_customer_order_cost_record         ON customer_order (cost_record_id);
CREATE INDEX ix_order_promotion_promotion          ON order_promotion (promotion_id);

-- Event history
CREATE INDEX ix_product_status_event_sku_ts        ON product_status_event (sku_id, event_ts);
CREATE INDEX ix_promotion_status_event_promo_ts    ON promotion_status_event (promotion_id, event_ts);
CREATE INDEX ix_price_decision_status_event_dec_ts ON price_decision_status_event (decision_id, event_ts);

-- Outputs
CREATE INDEX ix_detected_exception_run             ON detected_exception (run_id);
CREATE INDEX ix_detected_exception_type_date       ON detected_exception (issue_type, event_date);
CREATE INDEX ix_detected_exception_sku             ON detected_exception (sku_id);
CREATE INDEX ix_detected_exception_entity          ON detected_exception (entity_id);
