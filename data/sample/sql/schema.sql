-- Schema of the pricing & promotion simulator: the Flyway migrations concatenated in version order.

-- V1__master_tables.sql
-- V1: master tables — product, cost_record, promotion, promotion_rule, promotion_product, competitor_observation.
--
-- The database enforces STRUCTURE only: primary keys, foreign keys, NOT NULL on structural fields and enum
-- domains (VARCHAR + CHECK, portable across PostgreSQL and H2). Business rules (price >= floor, margins,
-- natural-key uniqueness, date ordering, reconciliation) are enforced by the validator and detector, so that
-- labelled anomalies stay loadable and detectable (PLAN.md §2 trade-offs).
-- Money: NUMERIC(12,2) INR excl. GST. Percentages: NUMERIC(6,4) fractions. Timestamps: UTC.

CREATE TABLE product (
    sku_id                VARCHAR(10)              NOT NULL,
    product_name          VARCHAR(120)             NOT NULL,
    brand                 VARCHAR(40)              NOT NULL,
    category              VARCHAR(20)              NOT NULL,
    subcategory           VARCHAR(40)              NOT NULL,
    uom                   VARCHAR(8)               NOT NULL,
    pack_size             INTEGER                  NOT NULL,
    status                VARCHAR(16)              NOT NULL,
    launch_date           DATE                     NOT NULL,
    discontinued_date     DATE,
    mrp                   NUMERIC(12,2)            NOT NULL,
    map_price             NUMERIC(12,2),
    min_margin_pct        NUMERIC(6,4)             NOT NULL,
    min_promo_margin_pct  NUMERIC(6,4)             NOT NULL,
    price_position        VARCHAR(8)               NOT NULL,
    competitor_tracked    BOOLEAN                  NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_product PRIMARY KEY (sku_id),
    CONSTRAINT ck_product_category CHECK (category IN ('GROCERY', 'PERSONAL_CARE', 'BEAUTY', 'HEALTH', 'HOME_KITCHEN',
                                                       'ELECTRONICS', 'APPAREL', 'TOYS', 'SPORTS', 'STATIONERY')),
    CONSTRAINT ck_product_uom CHECK (uom IN ('EACH', 'PACK')),
    CONSTRAINT ck_product_status CHECK (status IN ('NEW', 'ACTIVE', 'DISCONTINUED')),
    CONSTRAINT ck_product_price_position CHECK (price_position IN ('VALUE', 'MATCH', 'PREMIUM'))
);

CREATE TABLE cost_record (
    cost_record_id  VARCHAR(25)   NOT NULL,
    sku_id          VARCHAR(10)   NOT NULL,
    cost_month      CHAR(7)       NOT NULL,
    effective_from  DATE          NOT NULL,
    effective_to    DATE,
    unit_cost       NUMERIC(12,2) NOT NULL,
    freight_cost    NUMERIC(12,2) NOT NULL,
    landed_cost     NUMERIC(12,2) NOT NULL,
    change_pct      NUMERIC(6,4),
    change_reason   VARCHAR(20)   NOT NULL,
    CONSTRAINT pk_cost_record PRIMARY KEY (cost_record_id),
    CONSTRAINT fk_cost_record_product FOREIGN KEY (sku_id) REFERENCES product (sku_id),
    CONSTRAINT ck_cost_record_change_reason CHECK (change_reason IN ('INITIAL', 'NO_CHANGE', 'SUPPLIER_INCREASE',
                                                                     'SUPPLIER_DECREASE', 'FREIGHT_CHANGE'))
);

CREATE TABLE promotion (
    promotion_id     VARCHAR(17)              NOT NULL,
    promotion_name   VARCHAR(80)              NOT NULL,
    promo_type       VARCHAR(16)              NOT NULL,
    stack_group      VARCHAR(10)              NOT NULL,
    discount_pct     NUMERIC(6,4),
    discount_amount  NUMERIC(12,2),
    coupon_code      VARCHAR(16),
    start_date       DATE                     NOT NULL,
    end_date         DATE                     NOT NULL,
    priority         INTEGER                  NOT NULL,
    channel          VARCHAR(6)               NOT NULL,
    funding_source   VARCHAR(8)               NOT NULL,
    status           VARCHAR(10)              NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_promotion PRIMARY KEY (promotion_id),
    CONSTRAINT ck_promotion_promo_type CHECK (promo_type IN ('MARKDOWN_PCT', 'MARKDOWN_AMOUNT', 'COUPON_PCT', 'COUPON_AMOUNT')),
    CONSTRAINT ck_promotion_stack_group CHECK (stack_group IN ('SITEWIDE', 'CATEGORY', 'SKU', 'COUPON')),
    CONSTRAINT ck_promotion_channel CHECK (channel IN ('ALL', 'ECOM', 'STORE')),
    CONSTRAINT ck_promotion_funding_source CHECK (funding_source IN ('VENDOR', 'RETAILER')),
    CONSTRAINT ck_promotion_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'EXPIRED', 'CANCELLED'))
);

CREATE TABLE promotion_rule (
    rule_id              VARCHAR(16)   NOT NULL,
    promotion_id         VARCHAR(17)   NOT NULL,
    rule_type            VARCHAR(28)   NOT NULL,
    target_promotion_id  VARCHAR(17),
    param_numeric        NUMERIC(10,4),
    effective_from       DATE          NOT NULL,
    effective_to         DATE          NOT NULL,
    CONSTRAINT pk_promotion_rule PRIMARY KEY (rule_id),
    CONSTRAINT fk_promotion_rule_promotion FOREIGN KEY (promotion_id) REFERENCES promotion (promotion_id),
    CONSTRAINT fk_promotion_rule_target_promotion FOREIGN KEY (target_promotion_id) REFERENCES promotion (promotion_id),
    CONSTRAINT ck_promotion_rule_rule_type CHECK (rule_type IN ('EXCLUSIVE', 'EXCLUDES_PROMOTION', 'MAX_STACK_COUNT',
                                                               'MAX_COMBINED_DISCOUNT_PCT', 'MIN_ORDER_QTY'))
);

CREATE TABLE promotion_product (
    promotion_id  VARCHAR(17)              NOT NULL,
    sku_id        VARCHAR(10)              NOT NULL,
    added_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_promotion_product PRIMARY KEY (promotion_id, sku_id),
    CONSTRAINT fk_promotion_product_promotion FOREIGN KEY (promotion_id) REFERENCES promotion (promotion_id),
    CONSTRAINT fk_promotion_product_product FOREIGN KEY (sku_id) REFERENCES product (sku_id)
);

CREATE TABLE competitor_observation (
    observation_id       VARCHAR(16)              NOT NULL,
    sku_id               VARCHAR(10)              NOT NULL,
    competitor           VARCHAR(12)              NOT NULL,
    observed_price       NUMERIC(12,2)            NOT NULL,
    source_captured_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    ingested_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    source_type          VARCHAR(16)              NOT NULL,
    competitor_in_stock  BOOLEAN                  NOT NULL,
    match_confidence     NUMERIC(4,3)             NOT NULL,
    CONSTRAINT pk_competitor_observation PRIMARY KEY (observation_id),
    CONSTRAINT fk_competitor_observation_product FOREIGN KEY (sku_id) REFERENCES product (sku_id),
    CONSTRAINT ck_competitor_observation_competitor CHECK (competitor IN ('COMP_ALPHA', 'COMP_BETA', 'COMP_GAMMA')),
    CONSTRAINT ck_competitor_observation_source_type CHECK (source_type IN ('SCRAPE_FEED', 'PARTNER_FEED', 'MANUAL_AUDIT'))
);

-- V2__transaction_tables.sql
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

-- V3__event_tables.sql
-- V3: lifecycle event history (PLAN.md §4.3, §5.10). State changes are appended here and never overwritten;
-- the parent's status column equals the last event's to_status (checked by the validator, not the DB).
-- from_status is NULL on the first event of each parent.

CREATE TABLE product_status_event (
    event_id     VARCHAR(15)              NOT NULL,
    sku_id       VARCHAR(10)              NOT NULL,
    from_status  VARCHAR(16),
    to_status    VARCHAR(16)              NOT NULL,
    event_ts     TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_role   VARCHAR(20)              NOT NULL,
    note         VARCHAR(200),
    CONSTRAINT pk_product_status_event PRIMARY KEY (event_id),
    CONSTRAINT fk_product_status_event_product FOREIGN KEY (sku_id) REFERENCES product (sku_id),
    CONSTRAINT ck_product_status_event_from_status CHECK (from_status IN ('NEW', 'ACTIVE', 'DISCONTINUED')),
    CONSTRAINT ck_product_status_event_to_status CHECK (to_status IN ('NEW', 'ACTIVE', 'DISCONTINUED')),
    CONSTRAINT ck_product_status_event_actor_role CHECK (actor_role IN ('SYSTEM', 'MERCH_ANALYST', 'OPS_MANAGER',
                                                                       'WAREHOUSE_OPERATOR'))
);

CREATE TABLE promotion_status_event (
    event_id      VARCHAR(16)              NOT NULL,
    promotion_id  VARCHAR(17)              NOT NULL,
    from_status   VARCHAR(10),
    to_status     VARCHAR(10)              NOT NULL,
    event_ts      TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_role    VARCHAR(20)              NOT NULL,
    note          VARCHAR(200),
    CONSTRAINT pk_promotion_status_event PRIMARY KEY (event_id),
    CONSTRAINT fk_promotion_status_event_promotion FOREIGN KEY (promotion_id) REFERENCES promotion (promotion_id),
    CONSTRAINT ck_promotion_status_event_from_status CHECK (from_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'EXPIRED',
                                                                           'CANCELLED')),
    CONSTRAINT ck_promotion_status_event_to_status CHECK (to_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'EXPIRED',
                                                                       'CANCELLED')),
    CONSTRAINT ck_promotion_status_event_actor_role CHECK (actor_role IN ('SYSTEM', 'MERCH_ANALYST', 'OPS_MANAGER',
                                                                         'WAREHOUSE_OPERATOR'))
);

CREATE TABLE price_decision_status_event (
    event_id     VARCHAR(16)              NOT NULL,
    decision_id  VARCHAR(15)              NOT NULL,
    from_status  VARCHAR(10),
    to_status    VARCHAR(10)              NOT NULL,
    event_ts     TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_role   VARCHAR(20)              NOT NULL,
    note         VARCHAR(200),
    CONSTRAINT pk_price_decision_status_event PRIMARY KEY (event_id),
    CONSTRAINT fk_price_decision_status_event_decision FOREIGN KEY (decision_id) REFERENCES price_decision (decision_id),
    CONSTRAINT ck_price_decision_status_event_from_status CHECK (from_status IN ('PROPOSED', 'ESCALATED', 'APPROVED',
                                                                                'REJECTED', 'APPLIED', 'SUPERSEDED')),
    CONSTRAINT ck_price_decision_status_event_to_status CHECK (to_status IN ('PROPOSED', 'ESCALATED', 'APPROVED',
                                                                            'REJECTED', 'APPLIED', 'SUPERSEDED')),
    CONSTRAINT ck_price_decision_status_event_actor_role CHECK (actor_role IN ('SYSTEM', 'MERCH_ANALYST', 'OPS_MANAGER',
                                                                              'WAREHOUSE_OPERATOR'))
);

-- V4__output_tables.sql
-- V4: pipeline output tables (PLAN.md §4.1 O1–O3, §10). These are written by validate/detect/evaluate,
-- never by the generator, and never contain ground-truth labels.

CREATE TABLE pipeline_run (
    run_id               VARCHAR(40)              NOT NULL,
    command              VARCHAR(16)              NOT NULL,
    seed                 BIGINT,
    scale                VARCHAR(16),
    dataset_fingerprint  VARCHAR(64),
    input_path           VARCHAR(500),
    status               VARCHAR(10)              NOT NULL,
    started_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at          TIMESTAMP WITH TIME ZONE,
    summary              TEXT,
    CONSTRAINT pk_pipeline_run PRIMARY KEY (run_id),
    CONSTRAINT ck_pipeline_run_command CHECK (command IN ('LOAD', 'VALIDATE', 'DETECT', 'EVALUATE', 'RUN_ALL')),
    CONSTRAINT ck_pipeline_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE TABLE validation_result (
    run_id        VARCHAR(40)   NOT NULL,
    check_id      VARCHAR(12)   NOT NULL,
    category      VARCHAR(16)   NOT NULL,
    table_name    VARCHAR(40),
    description   VARCHAR(400)  NOT NULL,
    rows_checked  BIGINT        NOT NULL,
    violations    BIGINT        NOT NULL,
    status        VARCHAR(4)    NOT NULL,
    sample_ids    VARCHAR(1000),
    CONSTRAINT pk_validation_result PRIMARY KEY (run_id, check_id),
    CONSTRAINT fk_validation_result_run FOREIGN KEY (run_id) REFERENCES pipeline_run (run_id),
    CONSTRAINT ck_validation_result_category CHECK (category IN ('PRIMARY_KEY', 'FOREIGN_KEY', 'ENUM', 'ID_FORMAT',
                                                                 'DATE_ORDER', 'NO_LOOK_AHEAD', 'RECONCILIATION',
                                                                 'LIFECYCLE', 'DUPLICATE', 'COVERAGE', 'BUSINESS_RULE')),
    CONSTRAINT ck_validation_result_status CHECK (status IN ('PASS', 'FAIL', 'WARN'))
);

CREATE TABLE detected_exception (
    exception_id        VARCHAR(16)   NOT NULL,
    run_id              VARCHAR(40)   NOT NULL,
    category            VARCHAR(12)   NOT NULL,
    issue_type          VARCHAR(40)   NOT NULL,
    entity_type         VARCHAR(40)   NOT NULL,
    entity_id           VARCHAR(40)   NOT NULL,
    sku_id              VARCHAR(10),
    event_date          DATE,
    related_ids         VARCHAR(1000),
    violated_rule       VARCHAR(40),
    observed_value      VARCHAR(64),
    expected_value      VARCHAR(64),
    recommended_action  VARCHAR(32),
    detail              VARCHAR(1000),
    CONSTRAINT pk_detected_exception PRIMARY KEY (exception_id),
    CONSTRAINT fk_detected_exception_run FOREIGN KEY (run_id) REFERENCES pipeline_run (run_id),
    CONSTRAINT fk_detected_exception_product FOREIGN KEY (sku_id) REFERENCES product (sku_id),
    CONSTRAINT ck_detected_exception_category CHECK (category IN ('ANOMALY', 'OPERATIONAL')),
    CONSTRAINT ck_detected_exception_issue_type CHECK (issue_type IN (
        'PRICE_BELOW_FLOOR', 'PROMO_STACK_VIOLATION', 'STALE_OBSERVATION_AS_CURRENT', 'MARGIN_BEFORE_DISCOUNT',
        'PRICE_CHANGE_WITHOUT_REASON', 'COMPETITOR_DATA_STALE', 'GUARDRAIL_CONFLICT_ESCALATED', 'STOCKOUT',
        'COUPON_REJECTED'))
);

-- V5__indexes.sql
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

