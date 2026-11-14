-- V2: transaction / decision tables — price_decision and its bridges, price_history, inventory_snapshot,
-- customer_order, order_promotion. Structure only (see V1 header and PLAN.md §2).
-- price_history.change_reason and price_history.source_decision_id are deliberately NULLable at DB level:
-- "price change without reason" is a labelled anomaly that must remain loadable so it can be detected.

CREATE TABLE price_decision (
    decision_id             VARCHAR(15)              NOT NULL,
    sku_id                  VARCHAR(10)              NOT NULL,
    decision_ts             TIMESTAMP WITH TIME ZONE NOT NULL,
    effective_date          DATE                     NOT NULL,
    trigger                 VARCHAR(16)              NOT NULL,
    decision_source         VARCHAR(16)              NOT NULL,
    previous_decision_id    VARCHAR(15),
    cost_record_id          VARCHAR(25)              NOT NULL,
    landed_cost             NUMERIC(12,2)            NOT NULL,
    prev_list_price         NUMERIC(12,2),
    floor_price             NUMERIC(12,2)            NOT NULL,
    ceiling_price           NUMERIC(12,2)            NOT NULL,
    competitor_ref_price    NUMERIC(12,2),
    competitor_fresh_count  INTEGER                  NOT NULL,
    competitor_stale_count  INTEGER                  NOT NULL,
    on_hand_units           INTEGER                  NOT NULL,
    demand_7d_units         INTEGER                  NOT NULL,
    demand_28d_units        INTEGER                  NOT NULL,
    velocity_ratio          NUMERIC(8,4)             NOT NULL,
    days_of_cover           NUMERIC(8,2)             NOT NULL,
    target_price            NUMERIC(12,2)            NOT NULL,
    final_list_price        NUMERIC(12,2)            NOT NULL,
    markdown_discount_pct   NUMERIC(6,4)             NOT NULL,
    expected_net_price      NUMERIC(12,2)            NOT NULL,
    margin_pct              NUMERIC(6,4)             NOT NULL,
    required_margin_pct     NUMERIC(6,4)             NOT NULL,
    decision_type           VARCHAR(8)               NOT NULL,
    primary_reason_code     VARCHAR(32)              NOT NULL,
    reason_codes            VARCHAR(400)             NOT NULL,
    explanation             TEXT                     NOT NULL,
    rule_version            VARCHAR(20)              NOT NULL,
    status                  VARCHAR(10)              NOT NULL,
    CONSTRAINT pk_price_decision PRIMARY KEY (decision_id),
    CONSTRAINT fk_price_decision_product FOREIGN KEY (sku_id) REFERENCES product (sku_id),
    CONSTRAINT fk_price_decision_previous_decision FOREIGN KEY (previous_decision_id) REFERENCES price_decision (decision_id),
    CONSTRAINT fk_price_decision_cost_record FOREIGN KEY (cost_record_id) REFERENCES cost_record (cost_record_id),
    CONSTRAINT ck_price_decision_trigger CHECK (trigger IN ('INITIAL', 'WEEKLY_REVIEW', 'COST_CHANGE', 'STOCKOUT_EVENT', 'MANUAL')),
    CONSTRAINT ck_price_decision_decision_source CHECK (decision_source IN ('ENGINE', 'MANUAL_OVERRIDE')),
    CONSTRAINT ck_price_decision_decision_type CHECK (decision_type IN ('INCREASE', 'DECREASE', 'HOLD')),
    CONSTRAINT ck_price_decision_primary_reason_code CHECK (primary_reason_code IN (
        'INITIAL_PRICE', 'NO_CHANGE', 'COST_CHANGE', 'COMPETITOR_ALIGN', 'COMPETITOR_DATA_STALE', 'OVERSTOCK_MARKDOWN',
        'LOW_COVER_INCREASE', 'STOCKOUT_HOLD', 'FLOOR_ENFORCED', 'CEILING_ENFORCED', 'STEP_LIMIT_APPLIED',
        'PRICE_POINT_ROUNDED', 'PROMO_APPLIED', 'PROMO_REJECTED_EXCLUSIVE', 'PROMO_REJECTED_EXCLUDED_PAIR',
        'PROMO_REJECTED_STACK_LIMIT', 'PROMO_REJECTED_DISCOUNT_CAP', 'PROMO_REJECTED_MARGIN', 'PROMO_REJECTED_MIN_QTY',
        'PROMO_SUPPRESSED_STOCKOUT', 'GUARDRAIL_CONFLICT_ESCALATED', 'LARGE_CHANGE_ESCALATED', 'MANUAL_OVERRIDE')),
    CONSTRAINT ck_price_decision_status CHECK (status IN ('PROPOSED', 'ESCALATED', 'APPROVED', 'REJECTED', 'APPLIED', 'SUPERSEDED'))
);

CREATE TABLE price_decision_promotion (
    decision_id             VARCHAR(15)  NOT NULL,
    promotion_id            VARCHAR(17)  NOT NULL,
    application_status      VARCHAR(8)   NOT NULL,
    reason_code             VARCHAR(32)  NOT NULL,
    discount_pct_effective  NUMERIC(6,4) NOT NULL,
    CONSTRAINT pk_price_decision_promotion PRIMARY KEY (decision_id, promotion_id),
    CONSTRAINT fk_price_decision_promotion_decision FOREIGN KEY (decision_id) REFERENCES price_decision (decision_id),
    CONSTRAINT fk_price_decision_promotion_promotion FOREIGN KEY (promotion_id) REFERENCES promotion (promotion_id),
    CONSTRAINT ck_price_decision_promotion_application_status CHECK (application_status IN ('APPLIED', 'REJECTED')),
    CONSTRAINT ck_price_decision_promotion_reason_code CHECK (reason_code IN (
        'INITIAL_PRICE', 'NO_CHANGE', 'COST_CHANGE', 'COMPETITOR_ALIGN', 'COMPETITOR_DATA_STALE', 'OVERSTOCK_MARKDOWN',
        'LOW_COVER_INCREASE', 'STOCKOUT_HOLD', 'FLOOR_ENFORCED', 'CEILING_ENFORCED', 'STEP_LIMIT_APPLIED',
        'PRICE_POINT_ROUNDED', 'PROMO_APPLIED', 'PROMO_REJECTED_EXCLUSIVE', 'PROMO_REJECTED_EXCLUDED_PAIR',
        'PROMO_REJECTED_STACK_LIMIT', 'PROMO_REJECTED_DISCOUNT_CAP', 'PROMO_REJECTED_MARGIN', 'PROMO_REJECTED_MIN_QTY',
        'PROMO_SUPPRESSED_STOCKOUT', 'GUARDRAIL_CONFLICT_ESCALATED', 'LARGE_CHANGE_ESCALATED', 'MANUAL_OVERRIDE'))
);

CREATE TABLE price_decision_observation (
    decision_id     VARCHAR(15)  NOT NULL,
    observation_id  VARCHAR(16)  NOT NULL,
    usage           VARCHAR(24)  NOT NULL,
    age_hours       NUMERIC(8,2) NOT NULL,
    CONSTRAINT pk_price_decision_observation PRIMARY KEY (decision_id, observation_id),
    CONSTRAINT fk_price_decision_observation_decision FOREIGN KEY (decision_id) REFERENCES price_decision (decision_id),
    CONSTRAINT fk_price_decision_observation_observation FOREIGN KEY (observation_id) REFERENCES competitor_observation (observation_id),
    CONSTRAINT ck_price_decision_observation_usage CHECK (usage IN ('USED', 'EXCLUDED_STALE', 'EXCLUDED_LOW_CONFIDENCE',
                                                                   'EXCLUDED_SUPERSEDED'))
);

CREATE TABLE price_history (
    price_history_id     VARCHAR(23)   NOT NULL,
    sku_id               VARCHAR(10)   NOT NULL,
    price_date           DATE          NOT NULL,
    list_price           NUMERIC(12,2) NOT NULL,
    shelf_price          NUMERIC(12,2) NOT NULL,
    previous_list_price  NUMERIC(12,2),
    change_reason        VARCHAR(32),
    source_decision_id   VARCHAR(15),
    cost_record_id       VARCHAR(25)   NOT NULL,
    CONSTRAINT pk_price_history PRIMARY KEY (price_history_id),
    CONSTRAINT fk_price_history_product FOREIGN KEY (sku_id) REFERENCES product (sku_id),
    CONSTRAINT fk_price_history_source_decision FOREIGN KEY (source_decision_id) REFERENCES price_decision (decision_id),
    CONSTRAINT fk_price_history_cost_record FOREIGN KEY (cost_record_id) REFERENCES cost_record (cost_record_id),
    CONSTRAINT ck_price_history_change_reason CHECK (change_reason IN (
        'INITIAL_PRICE', 'NO_CHANGE', 'COST_CHANGE', 'COMPETITOR_ALIGN', 'COMPETITOR_DATA_STALE', 'OVERSTOCK_MARKDOWN',
        'LOW_COVER_INCREASE', 'STOCKOUT_HOLD', 'FLOOR_ENFORCED', 'CEILING_ENFORCED', 'STEP_LIMIT_APPLIED',
        'PRICE_POINT_ROUNDED', 'PROMO_APPLIED', 'PROMO_REJECTED_EXCLUSIVE', 'PROMO_REJECTED_EXCLUDED_PAIR',
        'PROMO_REJECTED_STACK_LIMIT', 'PROMO_REJECTED_DISCOUNT_CAP', 'PROMO_REJECTED_MARGIN', 'PROMO_REJECTED_MIN_QTY',
        'PROMO_SUPPRESSED_STOCKOUT', 'GUARDRAIL_CONFLICT_ESCALATED', 'LARGE_CHANGE_ESCALATED', 'MANUAL_OVERRIDE'))
);

CREATE TABLE inventory_snapshot (
    inventory_snapshot_id  VARCHAR(23)  NOT NULL,
    sku_id                 VARCHAR(10)  NOT NULL,
    snapshot_date          DATE         NOT NULL,
    opening_on_hand        INTEGER      NOT NULL,
    receipts_units         INTEGER      NOT NULL,
    units_sold             INTEGER      NOT NULL,
    adjustment_units       INTEGER      NOT NULL,
    closing_on_hand        INTEGER      NOT NULL,
    on_order_units         INTEGER      NOT NULL,
    demand_units           INTEGER      NOT NULL,
    lost_sales_units       INTEGER      NOT NULL,
    stockout_flag          BOOLEAN      NOT NULL,
    days_of_cover          NUMERIC(8,2) NOT NULL,
    CONSTRAINT pk_inventory_snapshot PRIMARY KEY (inventory_snapshot_id),
    CONSTRAINT fk_inventory_snapshot_product FOREIGN KEY (sku_id) REFERENCES product (sku_id)
);

CREATE TABLE customer_order (
    order_id             VARCHAR(16)              NOT NULL,
    order_ts             TIMESTAMP WITH TIME ZONE NOT NULL,
    order_date           DATE                     NOT NULL,
    sku_id               VARCHAR(10)              NOT NULL,
    customer_ref         VARCHAR(13)              NOT NULL,
    customer_segment     VARCHAR(8)               NOT NULL,
    channel              VARCHAR(6)               NOT NULL,
    quantity             INTEGER                  NOT NULL,
    price_history_id     VARCHAR(23)              NOT NULL,
    cost_record_id       VARCHAR(25)              NOT NULL,
    unit_list_price      NUMERIC(12,2)            NOT NULL,
    gross_amount         NUMERIC(12,2)            NOT NULL,
    discount_amount      NUMERIC(12,2)            NOT NULL,
    net_amount           NUMERIC(12,2)            NOT NULL,
    unit_landed_cost     NUMERIC(12,2)            NOT NULL,
    gross_margin_amount  NUMERIC(12,2)            NOT NULL,
    order_status         VARCHAR(10)              NOT NULL,
    CONSTRAINT pk_customer_order PRIMARY KEY (order_id),
    CONSTRAINT fk_customer_order_product FOREIGN KEY (sku_id) REFERENCES product (sku_id),
    CONSTRAINT fk_customer_order_price_history FOREIGN KEY (price_history_id) REFERENCES price_history (price_history_id),
    CONSTRAINT fk_customer_order_cost_record FOREIGN KEY (cost_record_id) REFERENCES cost_record (cost_record_id),
    CONSTRAINT ck_customer_order_customer_segment CHECK (customer_segment IN ('NEW', 'REGULAR', 'LOYALTY')),
    CONSTRAINT ck_customer_order_channel CHECK (channel IN ('ECOM', 'STORE')),
    CONSTRAINT ck_customer_order_order_status CHECK (order_status IN ('FULFILLED', 'CANCELLED'))
);

CREATE TABLE order_promotion (
    order_id            VARCHAR(16)   NOT NULL,
    promotion_id        VARCHAR(17)   NOT NULL,
    sequence_no         INTEGER       NOT NULL,
    coupon_presented    BOOLEAN       NOT NULL,
    application_status  VARCHAR(8)    NOT NULL,
    rejection_reason    VARCHAR(32),
    discount_amount     NUMERIC(12,2) NOT NULL,
    CONSTRAINT pk_order_promotion PRIMARY KEY (order_id, promotion_id),
    CONSTRAINT fk_order_promotion_order FOREIGN KEY (order_id) REFERENCES customer_order (order_id),
    CONSTRAINT fk_order_promotion_promotion FOREIGN KEY (promotion_id) REFERENCES promotion (promotion_id),
    CONSTRAINT ck_order_promotion_application_status CHECK (application_status IN ('APPLIED', 'REJECTED')),
    CONSTRAINT ck_order_promotion_rejection_reason CHECK (rejection_reason IN (
        'INITIAL_PRICE', 'NO_CHANGE', 'COST_CHANGE', 'COMPETITOR_ALIGN', 'COMPETITOR_DATA_STALE', 'OVERSTOCK_MARKDOWN',
        'LOW_COVER_INCREASE', 'STOCKOUT_HOLD', 'FLOOR_ENFORCED', 'CEILING_ENFORCED', 'STEP_LIMIT_APPLIED',
        'PRICE_POINT_ROUNDED', 'PROMO_APPLIED', 'PROMO_REJECTED_EXCLUSIVE', 'PROMO_REJECTED_EXCLUDED_PAIR',
        'PROMO_REJECTED_STACK_LIMIT', 'PROMO_REJECTED_DISCOUNT_CAP', 'PROMO_REJECTED_MARGIN', 'PROMO_REJECTED_MIN_QTY',
        'PROMO_SUPPRESSED_STOCKOUT', 'GUARDRAIL_CONFLICT_ESCALATED', 'LARGE_CHANGE_ESCALATED', 'MANUAL_OVERRIDE'))
);
