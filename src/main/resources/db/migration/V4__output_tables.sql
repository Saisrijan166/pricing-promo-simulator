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
