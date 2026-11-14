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
