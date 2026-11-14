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
