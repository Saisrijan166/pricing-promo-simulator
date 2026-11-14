# PLAN.md — Pricing & Promotion Simulator (YottaFlex Candidate Project)

> Start-to-finish build, run and submission plan. No code is written until this plan is agreed.
> Dates in prose use DD-MM-YYYY. **All dates/timestamps inside data files use ISO-8601** (`2026-01-01`, `2026-01-01T06:00:00Z`) because that is what parsers, SQL and reviewers expect.

**Hard deadline:** submit the public GitHub URL on the YottaFlex portal **before Sun 15-11-2026 12:02 UTC**. There is **one submission only**.
**Internal deadlines:** code freeze at **15-11-2026 08:00 UTC**, then a fresh-clone test, then submit by **10:00 UTC** at the latest. That leaves a 2-hour buffer.

---

## 0. Time budget and cut line

The 72-hour window ends 15-11-2026 12:02 UTC, and artifact and commit timestamps will be checked against it. This is the critical path, in order:

| # | Phase | Budget | Output |
|---|-------|--------|--------|
| P0 | Repo bootstrap, docs move, PLAN.md, skeleton, Docker, Flyway V1 | 1.0 h | App boots, DB up |
| P1 | Domain records, enums, IdFactory, data dictionary + ER doc | 1.5 h | `docs/data_dictionary.md`, `docs/er_diagram.md` |
| P2 | Pricing engine (pure Java) + unit tests | 2.5 h | Engine green |
| P3 | Generator: master data + market day-loop simulator | 3.5 h | Starter dataset generated |
| P4 | Anomaly injection + ground truth + leakage tests | 1.5 h | `labels/ground_truth.csv` |
| P5 | CSV/manifest/checksums, bulk loader, repro check | 1.5 h | Repro proof |
| P6 | Validation + detector + evaluation metrics | 2.0 h | `validation_summary.*`, `evaluation_metrics.json` |
| P7 | REST API (3 core endpoints) + MockMvc tests | 1.5 h | Swagger UI works |
| P8 | Full-scale run, evidence, README, PROMPT_HISTORY, AI_USAGE | 2.0 h | Docs complete |
| P9 | Fresh-clone test, fixes, tag, submit | 1.0 h | URL submitted |

**Cut line:** if P6 is not green by **15-11-2026 03:00 UTC**, drop these nice-to-haves in this order:
1. JSON export for large tables. Keep JSON for master tables and labels only.
2. SQL seed for full scale. Keep the starter only.
3. The decision approval/escalation lifecycle. Auto-approve everything, keep the status column and document the gap.
4. Extra API endpoints beyond simulate, explain and exceptions.

**Never cut:** fixed-seed reproducibility, ground truth separation, validation summary, tests, README run steps, PROMPT_HISTORY.md and AI_USAGE.md.

---

## 1. Project summary and what reviewers evaluate

**What we build:** a Java 21 / Spring Boot 3.x application named `pricing-promo-simulator`. It has six parts:

1. **Synthetic data generator.** Deterministic and parameterised. It produces a 180-day retail pricing world for 5,000 SKUs and 2,000 promotions. The world contains products, monthly cost histories, competitor price observations with source timestamps, daily inventory and demand, orders with coupons, and engine-made price decisions.
2. **Pricing engine.** It computes candidate prices under these guardrails: floor from landed cost and minimum margin, MAP; ceiling at MRP; competitor freshness; step limits; demand and inventory signals; promotion stacking and exclusion; margin checked on the net (post-discount) price. Every decision is stored with its full inputs, reason codes and explanation steps.
3. **Anomaly injector.** It injects 5 labelled edge-case types into a valid baseline. The labels go to a separate `ground_truth.csv/json` and never appear in the feature tables.
4. **Validator and detector.** The validator checks PK, FK, enums, ID formats, date order, arithmetic reconciliation, lifecycle transitions, duplicates and coverage, and writes `validation_summary.json/.md`. A rule-based exception detector finds business-rule violations without reading the ground truth.
5. **Evaluator.** It compares detected exceptions with the ground truth and reports precision, recall and F1 per issue type. It also reports dataset realism metrics.
6. **REST API (springdoc).** Endpoints to simulate a price, explain a stored decision and list exceptions.

**Actors mapped to the system:**

| Actor | Where they appear |
|---|---|
| Customer | `customer_order` rows, coupons presented (`order_promotion`), demand |
| Store/warehouse operator | `inventory_snapshot` receipts and adjustments (shrinkage), stockouts, replenishment |
| Merchandising/e-commerce analyst | Promotions and rules, manual overrides, approval of escalated decisions |
| Operations manager | Exception queue (`/api/v1/exceptions`), guardrail escalations, validation summary |

**What reviewers evaluate.** These come from the brief and the submission guidelines, and each maps to where we address it:

| Criterion | Where addressed |
|---|---|
| Working, executable code with clear install/configure/run/verify steps | README §Quick start, `scripts/run_all.sh`, fresh-clone test |
| Public repo with meaningful commit evolution | §16 commit strategy |
| Transparent LLM use (prompt history required) | `PROMPT_HISTORY.md`, `AI_USAGE.md` |
| Visible reasoning: assumptions, decisions, trade-offs, testing, limitations, proof it works | README, `docs/design_decisions.md`, `docs/evidence/` |
| (a) Realistic distributions and variation | §7 distributions, `evaluation_metrics.json` realism bands |
| (b) Consistent IDs and valid joins | §6 ID rules, FK checks, ID-embedded-field checks |
| (c) Dependency and lifecycle consistency | Lifecycle checks, no-look-ahead check |
| (d) Correct derived values and reconciliation | Reconciliation checks (inventory, order totals, margins, floors) |
| (e) Normal, boundary, failure and exception coverage | Natural exceptions plus 5 injected anomalies plus boundary unit tests |
| (f) Separate ground truth | `labels/` folder, leakage tests |
| (g) Fixed-seed reproducibility, docs, lawful provenance | `repro_check.sh`, manifest, `docs/provenance.md` |
| Acceptance: every price is explainable | Every `price_history` row links to a decision. The explain endpoint shows the chain. |
| Acceptance: guardrails enforced | Engine clamps. The validator recomputes floors and ceilings. |
| Acceptance: consistent margin basis | Single `MarginCalculator`, always net of discount, on landed cost |
| Acceptance: reproducible promotion eligibility | Deterministic `PromotionStackResolver` with a total ordering on priority, then promotion ID |

---

## 2. Assumptions, trade-offs and limitations

### Assumptions
1. **Simulation window:** 01-01-2026 to 29-06-2026 inclusive (180 days). Business date equals the UTC date, and all timestamps are UTC (`TIMESTAMP WITH TIME ZONE`).
2. **Currency:** INR. Money is `NUMERIC(12,2)`. Amounts exclude GST, and tax is out of scope.
3. **Percentages** are stored as decimal fractions (`0.2500` = 25%), `NUMERIC(6,4)`.
4. **MRP is the hard ceiling.** In India, retail price cannot exceed the Maximum Retail Price. `map_price` (brand-mandated minimum) is optional and feeds the floor.
5. **Margin definition:** `margin_pct = (net_unit_price − landed_cost) / net_unit_price`. Here `net_unit_price` is the price after all applied discounts, and `landed_cost = unit_cost + freight_cost` from the cost record effective on that date. This single basis is used everywhere.
6. **Cost** changes only at month boundaries. There is one `CostRecord` per SKU per active month, which matches the `COST-<sku>-2026-01` ID scheme.
7. **Prices change only through a `PriceDecision`.** Decisions run at these times:
   - `INITIAL` on the first active day.
   - `WEEKLY_REVIEW` every Monday.
   - `COST_CHANGE` on the 1st of a month where landed cost changed.
   - `STOCKOUT_EVENT` on the day after closing stock hits 0.

   The decision's `effective_date` is its decision date, so decisions take effect the same day.
8. **Promotions:**
   - Markdown promotions (automatic, shelf-level) run Monday to Sunday in whole weeks, following the weekly ad-cycle convention. The weekly review decision resolves their stacking.
   - Coupon promotions are presented by customers at order time with any date window. They are resolved per order.
9. **Competitor freshness:** an observation is *fresh* only if `decision_ts − source_captured_at ≤ 72 h`. The check uses capture time, not ingestion time, so a late-arriving feed is still stale.
10. **Orders:** an order is a single-SKU line, so there is no basket table. `customer_ref` is an opaque synthetic token (`CUST-0000001`) holding no PII. Customer is not one of the 9 entities, so it has no FK by design.
11. **Inventory** is held at a single fulfilment network level, with no per-store split.
12. **Competitors are fictional** (`COMP_ALPHA`, `COMP_BETA`, `COMP_GAMMA`). No external data is used, so the data is 100% synthetic.
13. **Latent simulation parameters** (price elasticity, base demand, supply fragility) stay inside the generator. They are **not exported**, because exporting them would leak the data-generating truth into features.

### Trade-offs

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Enum storage | `VARCHAR` + `CHECK` constraints | PostgreSQL `ENUM` types | Same Flyway scripts run on H2 (tests) and Postgres |
| Business-rule enforcement | Validator and detector, not DB constraints | `CHECK (list_price >= floor)` | Anomalies must stay loadable to be detected. The DB enforces structure (PK, FK, enum, not-null on structural fields) only. |
| Bulk load | `JdbcTemplate.batchUpdate`, batch 5,000, `reWriteBatchedInserts=true` | `COPY` | Fixed stack. Fast enough for about 3.5M rows. |
| Canonical output | CSV (checksummed). JSON/SQL produced by `export`. | Everything in every format | The full dataset is about 400 MB of CSV. JSON for all of it adds little and costs time and space. |
| Committed data | Starter dataset only (`data/sample/`), plus full-run manifest and evidence | Commit the full dataset | GitHub's 100 MB per-file limit. The full dataset is reproducible from the seed. |
| Simulation | Single-threaded day loop with per-SKU RNG streams | Parallel | Global sequence IDs need a deterministic order. Single-threaded is simpler to prove reproducible. |
| Detector | Rule-based mirror of the engine's guardrails | ML | The labels are rule-defined. The detector proves the injected anomalies are labelled exactly and that the baseline contains no unlabelled violations. |
| Modules | Single Maven module | Multi-module | 72-hour window. Packages give enough separation. |
| CLI | Picocli inside the Spring Boot jar | Separate jar | One artefact. The `generate` command runs with an `offline` profile and needs no DB. |
| Random | `java.util.Random` (algorithm fixed by the Java spec) + `StrictMath` | `SplittableRandom`, `Math` | Bit-for-bit identical across JVMs and platforms |

### Limitations (to state honestly in the README)
- Demand uses a constant-elasticity model. Competitors do not react to our prices. There are no cross-SKU cannibalisation effects.
- There are no returns, baskets, taxes, multi-location stock or supplier entities.
- Escalation approvals are simulated (approved or rejected the same day by role), not a real workflow.
- The detector uses the same rule definitions as the engine, so precision and recall near 1.0 is expected. The metric shows label integrity, not detector intelligence. This is called out explicitly.
- H2 (PostgreSQL mode) is used in tests. Postgres-specific behaviour is verified manually through the Docker run, and the steps are documented.
- Sequence counters have fixed width (for example `DEC-2026-` has 6 digits, so at most 999,999 per year). The generator fails fast if a scale would overflow a counter.

---

## 3. Final folder and package structure

Repo name: **`pricing-promo-simulator`**. Base package: **`com.pricingsim`**.

```
pricing-promo-simulator/
├── README.md                       # Install, configure, run, verify, design summary, evidence links
├── PLAN.md                         # This plan
├── PROMPT_HISTORY.md               # Chronological AI prompt log (required by guidelines)
├── AI_USAGE.md                     # Where AI helped / relied on / what I reviewed, changed, validated
├── LICENSE                         # MIT
├── .gitignore                      # target/, out/, .env, *.log, IDE files
├── .gitattributes                  # *.csv *.sql *.json *.sh text eol=lf (keeps checksums stable on Windows)
├── .editorconfig                   # UTF-8, LF, 4-space Java
├── .env.example                    # POSTGRES_DB/USER/PASSWORD/PORT, DB_URL defaults
├── docker-compose.yml              # postgres:16-alpine, healthcheck, named volume, port from .env
├── pom.xml                         # Java 21, Spring Boot 3.5.x parent, deps (see below), jar name pricing-sim.jar
├── mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties   # Maven wrapper (reviewer needs no Maven)
│
├── docs/
│   ├── challenge_brief.md          # Problem statement (moved from root "Associate AI ... .md")
│   ├── design_decisions.md         # ADR-style: context → decision → consequence (one per trade-off above)
│   ├── er_diagram.md               # Mermaid erDiagram + cardinality table
│   ├── data_dictionary.md          # Every table/field: type, required, enum, unit, unique, validation
│   ├── id_conventions.md           # ID patterns, regexes, counter scopes, assignment order
│   ├── generator_design.md         # Seed, RNG streams, generation order, distributions, scale profiles
│   ├── pricing_rules.md            # Engine steps S1–S12, reason codes, worked example
│   ├── anomalies_and_ground_truth.md  # 5 issue types, injection method, label schema, leakage controls
│   ├── validation.md               # Every check ID, SQL logic, expected result
│   ├── api.md                      # Endpoints, request/response examples, curl
│   ├── provenance.md               # 100% synthetic; no external data; fictional names; lawful-use statement
│   └── evidence/
│       ├── manifest_full.json            # Full-scale manifest (row counts + SHA-256)
│       ├── validation_summary_full.md    # Full-scale validation output
│       ├── evaluation_metrics_full.json  # Precision/recall + realism metrics
│       ├── reproducibility_report.md     # Run A vs run B checksums, IDENTICAL verdict
│       ├── test_report.txt               # mvnw verify summary
│       └── api_samples.md                # Real responses captured from api_smoke.sh
│
├── scripts/
│   ├── run_all.sh                  # docker up → build → generate → load → validate → detect → evaluate → export
│   ├── repro_check.sh              # Generate twice (same seed), diff checksums, write report; plus a different-seed sanity run
│   ├── fresh_clone_test.sh         # Clone public URL into temp dir, run the whole pipeline, compare with data/sample checksums
│   ├── api_smoke.sh                # curl the 3 core endpoints, save outputs to docs/evidence/api_samples.md
│   └── extract_prompt_history.sh   # jq over Claude Code JSONL transcripts → draft PROMPT_HISTORY entries
│
├── data/sample/                    # COMMITTED starter dataset (scale=starter, seed=20261022)
│   ├── csv/<table>.csv             # 17 data tables (see §4)
│   ├── json/<table>.json           # Same data as JSON arrays
│   ├── sql/schema.sql              # DDL (concatenated Flyway migrations)
│   ├── sql/seed.sql                # INSERT statements in FK-safe order
│   ├── labels/ground_truth.csv     # Anomaly labels (separate from features)
│   ├── labels/ground_truth.json
│   ├── manifest.json               # Generator command, seed, scale, version, files, rows, columns, sha256
│   ├── checksums.sha256            # sha256sum-compatible list
│   ├── validation_summary.json / validation_summary.md
│   ├── detected_exceptions.csv
│   └── evaluation_metrics.json
│
└── src/
    ├── main/java/com/pricingsim/
    │   ├── PricingSimApplication.java      # main(): CLI mode when args given (web=NONE; "generate"/"repro-check" → profile offline), else web server
    │   ├── cli/
    │   │   ├── PricingSimCommand.java      # Top-level @Command listing subcommands
    │   │   ├── PicocliRunner.java          # CommandLineRunner + ExitCodeGenerator bridge
    │   │   ├── ScaleOptions.java           # Mixin: --scale --seed --skus --days --promos --start-date --anomalies-per-type --out
    │   │   ├── GenerateCommand.java        # Writes CSV + labels + manifest + checksums (no DB)
    │   │   ├── LoadCommand.java            # Truncates and loads CSV → Postgres via BulkLoader
    │   │   ├── ValidateCommand.java        # Runs ValidationRunner, writes validation_summary.*
    │   │   ├── DetectCommand.java          # Runs ExceptionDetector → detected_exception table + CSV
    │   │   ├── EvaluateCommand.java        # Detected vs ground truth → evaluation_metrics.json
    │   │   ├── ExportCommand.java          # CSV → JSON / SQL
    │   │   ├── ReproCheckCommand.java      # Generate twice in-process, compare checksums, write report
    │   │   └── RunAllCommand.java          # Full pipeline in one command
    │   ├── config/
    │   │   ├── GeneratorProperties.java    # @ConfigurationProperties("generator"): seed, scales, rates
    │   │   ├── ScaleProfile.java           # record(skus, days, promotions, startDate, anomaliesPerType, competitors…)
    │   │   ├── PricingPolicyProperties.java # @ConfigurationProperties("pricing.policy"): thresholds
    │   │   ├── JacksonConfig.java          # Plain BigDecimal, JavaTimeModule, sorted keys, ISO dates
    │   │   └── OpenApiConfig.java          # springdoc metadata, tags
    │   ├── domain/
    │   │   ├── model/                      # Java records, one per table:
    │   │   │   ├── Product.java, CostRecord.java, PriceHistory.java, CompetitorObservation.java,
    │   │   │   ├── InventorySnapshot.java, Promotion.java, PromotionRule.java, CustomerOrder.java,
    │   │   │   ├── PriceDecision.java,
    │   │   │   ├── PromotionProduct.java, OrderPromotion.java,                  # bridges
    │   │   │   ├── PriceDecisionPromotion.java, PriceDecisionObservation.java,  # bridges
    │   │   │   └── ProductStatusEvent.java, PromotionStatusEvent.java, PriceDecisionStatusEvent.java  # event history
    │   │   ├── enums/                      # Category, ProductStatus, PricePosition, CostChangeReason, Competitor,
    │   │   │                               # ObservationSource, PromoType, StackGroup, PromotionStatus, RuleType,
    │   │   │                               # Channel, CustomerSegment, OrderStatus, DecisionTrigger, DecisionSource,
    │   │   │                               # DecisionType, DecisionStatus, ReasonCode, ApplicationStatus,
    │   │   │                               # ObservationUsage, ActorRole, IssueType, ExceptionCategory
    │   │   └── id/
    │   │       ├── IdFactory.java          # format/parse every ID type; embedded-field extraction
    │   │       ├── IdPatterns.java         # Compiled regexes (shared with validator)
    │   │       └── SequenceCounter.java    # Zero-padded, overflow-checked counters
    │   ├── pricing/                        # PURE JAVA (no Spring/DB), used by generator AND API
    │   │   ├── PricingEngine.java          # Orchestrates steps S1–S12, returns PricingResult
    │   │   ├── PricingContext.java         # Immutable inputs snapshot (everything stored on the decision)
    │   │   ├── PricingResult.java          # Outputs + reason codes + List<ExplanationStep>
    │   │   ├── ExplanationStep.java        # step, rule, inputs, output, message
    │   │   ├── PricingPolicy.java          # Immutable thresholds + RULE_VERSION = "pricing-rules-v1"
    │   │   ├── GuardrailCalculator.java    # Floor/ceiling
    │   │   ├── MarginCalculator.java       # THE one margin function (net basis)
    │   │   ├── CompetitorReferenceResolver.java  # Freshness, confidence filter, latest per competitor, median
    │   │   ├── DemandSignalCalculator.java # 7d/28d velocity, days of cover
    │   │   ├── PromotionStackResolver.java # Eligibility, exclusivity, pairwise exclusion, stack count, combined cap
    │   │   └── PricePointRounder.java      # Price-point endings that respect bounds
    │   ├── generator/
    │   │   ├── DatasetGenerator.java       # Orchestrator: master → simulate → finalise → write manifest
    │   │   ├── GenerationContext.java      # Scale, policy, fixed clock, RandomStreams, sinks, counters
    │   │   ├── rng/RandomStreams.java      # seed = SHA-256(globalSeed|stream|key) → java.util.Random
    │   │   ├── rng/Distributions.java      # lognormal, poisson, bernoulli, weighted choice, truncated normal (StrictMath)
    │   │   ├── master/ProductGenerator.java
    │   │   ├── master/CostRecordGenerator.java
    │   │   ├── master/PromotionGenerator.java      # promotions + rules + promotion_product + status events
    │   │   ├── master/CompetitorObservationGenerator.java
    │   │   ├── sim/MarketSimulator.java    # Day loop: decisions → price_history → demand/orders → inventory
    │   │   ├── sim/SkuState.java           # Mutable per-SKU state (stock, pipeline, trailing sales, current decision)
    │   │   ├── sim/DemandModel.java        # Latent base demand × weekday × price/promo elasticity
    │   │   ├── sim/ReplenishmentPolicy.java # (s,S) with lead times
    │   │   ├── sim/OrderGenerator.java     # Split units into orders, channel/segment, coupon presentation
    │   │   ├── anomaly/AnomalyPlanner.java # Pre-selects targets from the "anomaly" RNG stream
    │   │   ├── anomaly/AnomalyPlan.java
    │   │   ├── anomaly/AnomalyInjector.java # Hooks called by simulator; mutates and records label
    │   │   ├── anomaly/GroundTruthRecord.java
    │   │   └── sink/RowSink.java, CsvRowSink.java, InMemoryRowSink.java
    │   ├── io/
    │   │   ├── TableSpec.java              # Table name, file, ordered columns, PK, FKs, enum columns, ID pattern
    │   │   ├── TableRegistry.java          # All 17 tables + FK-safe load order (drives loader AND validator)
    │   │   ├── CsvSupport.java             # Commons CSV format: RFC4180, LF, header, empty = null
    │   │   ├── CsvDatasetReader.java
    │   │   ├── JsonExporter.java
    │   │   ├── SqlExporter.java
    │   │   ├── ChecksumService.java        # SHA-256 per file + dataset fingerprint
    │   │   └── ManifestWriter.java
    │   ├── persistence/
    │   │   ├── BulkLoader.java             # TRUNCATE … CASCADE then batch insert in TableRegistry order
    │   │   ├── PricingInputDao.java        # Assembles PricingContext from DB for simulate API
    │   │   ├── DecisionQueryDao.java       # Decision + bridges + price history range
    │   │   ├── ExceptionDao.java           # detected_exception read/write, paging
    │   │   └── RunDao.java                 # pipeline_run, validation_result
    │   ├── validation/
    │   │   ├── ValidationCheck.java, CheckResult.java, CheckCategory.java
    │   │   ├── ValidationRunner.java
    │   │   ├── ValidationSummaryWriter.java  # JSON + Markdown
    │   │   └── checks/PrimaryKeyChecks.java, ForeignKeyChecks.java, EnumChecks.java, IdFormatChecks.java,
    │   │              DateOrderChecks.java, ReconciliationChecks.java, LifecycleTransitionChecks.java,
    │   │              DuplicateChecks.java, CoverageChecks.java, NoLookAheadChecks.java, BusinessRuleChecks.java
    │   ├── detection/
    │   │   ├── ExceptionDetector.java      # Runs detectors; NEVER imports generator.anomaly or reads labels/
    │   │   ├── DetectedException.java
    │   │   └── rules/PriceBelowFloorDetector.java, PromoStackViolationDetector.java,
    │   │             StaleObservationDetector.java, MarginBasisDetector.java,
    │   │             UnexplainedPriceChangeDetector.java, OperationalExceptionCollector.java
    │   ├── evaluation/
    │   │   ├── GroundTruthEvaluator.java   # TP/FP/FN per issue type
    │   │   ├── DatasetMetricsCalculator.java # Realism bands
    │   │   └── EvaluationReportWriter.java
    │   ├── service/
    │   │   ├── SimulationService.java, DecisionExplainService.java, ExceptionQueryService.java, ReportService.java
    │   └── api/
    │       ├── PricingController.java      # POST /api/v1/pricing/simulate
    │       ├── DecisionController.java     # GET /api/v1/decisions/{id}, /{id}/explain
    │       ├── ExceptionController.java    # GET /api/v1/exceptions
    │       ├── ProductController.java      # GET /api/v1/products/{sku}/price-history
    │       ├── ReportController.java       # GET /api/v1/reports/validation-summary, /evaluation
    │       ├── ApiExceptionHandler.java    # RFC 7807 ProblemDetail
    │       └── dto/SimulatePriceRequest.java, SimulatePriceResponse.java, DecisionExplanationResponse.java,
    │               ExceptionView.java, PriceHistoryView.java, PageResponse.java
    ├── main/resources/
    │   ├── application.yml                 # datasource from env, flyway, generator + pricing.policy defaults, scales
    │   ├── application-offline.yml         # Excludes DataSource/Flyway/JDBC auto-config (generate needs no DB)
    │   └── db/migration/
    │       ├── V1__master_tables.sql       # product, cost_record, promotion, promotion_rule, promotion_product, competitor_observation
    │       ├── V2__transaction_tables.sql  # price_decision, bridges, price_history, inventory_snapshot, customer_order, order_promotion
    │       ├── V3__event_tables.sql        # product/promotion/price_decision status events
    │       ├── V4__output_tables.sql       # detected_exception, pipeline_run, validation_result
    │       └── V5__indexes.sql             # FK and lookup indexes (sku_id+date etc.)
    ├── test/java/com/pricingsim/…          # See §12
    └── test/resources/
        ├── application-test.yml            # H2 jdbc:h2:mem:…;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
        ├── golden/pricing_scenarios.json   # 20 hand-computed scenarios (inputs → expected price/reasons)
        └── fixtures/broken/*.csv           # Tiny datasets each breaking one rule (dup PK, orphan FK, bad enum…)
```

**pom.xml dependencies:**
- `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `spring-boot-starter-validation`
- `flyway-core` and `flyway-database-postgresql` (needed for Flyway 10+), `postgresql`
- `h2` (test scope)
- `commons-csv`
- `picocli-spring-boot-starter` 4.7.x
- `springdoc-openapi-starter-webmvc-ui` 2.8.x
- `jackson-datatype-jsr310`
- `spring-boot-starter-test` (JUnit 5, AssertJ, MockMvc)

Plugins: `maven-surefire` and `maven-failsafe` (`*IT` classes).

---

## 4. ER design

### 4.1 Tables (9 core entities + 4 bridge tables + 3 event tables + 3 output tables)

| # | Table | Kind | PK |
|---|---|---|---|
| 1 | `product` | Entity (master) | `sku_id` |
| 2 | `cost_record` | Entity (master, effective-dated) | `cost_record_id` |
| 3 | `price_history` | Entity (daily event history) | `price_history_id` |
| 4 | `competitor_observation` | Entity (event) | `observation_id` |
| 5 | `inventory_snapshot` | Entity (daily snapshot) | `inventory_snapshot_id` |
| 6 | `promotion` | Entity (master, effective-dated) | `promotion_id` |
| 7 | `promotion_rule` | Entity (master) | `rule_id` |
| 8 | `customer_order` (`order` is a SQL reserved word) | Entity (transaction) | `order_id` |
| 9 | `price_decision` | Entity (decision record) | `decision_id` |
| B1 | `promotion_product` | Bridge Promotion ⇄ Product | (`promotion_id`, `sku_id`) |
| B2 | `order_promotion` | Bridge Order ⇄ Promotion | (`order_id`, `promotion_id`) |
| B3 | `price_decision_promotion` | Bridge Decision ⇄ Promotion | (`decision_id`, `promotion_id`) |
| B4 | `price_decision_observation` | Bridge Decision ⇄ Observation | (`decision_id`, `observation_id`) |
| E1 | `product_status_event` | Lifecycle history | `event_id` |
| E2 | `promotion_status_event` | Lifecycle history | `event_id` |
| E3 | `price_decision_status_event` | Lifecycle history | `event_id` |
| O1–O3 | `detected_exception`, `pipeline_run`, `validation_result` | Pipeline outputs (not generator inputs) | own IDs |

### 4.2 Relationships and cardinality

| Parent → Child | Card. | FK column | Optional? | Notes |
|---|---|---|---|---|
| product → cost_record | 1:N | `cost_record.sku_id` | Mandatory (≥1 per active month) | Non-overlapping effective periods |
| product → price_history | 1:N | `price_history.sku_id` | Mandatory (1 per active day) | |
| product → competitor_observation | 1:N | `competitor_observation.sku_id` | Optional (only `competitor_tracked=true`) | |
| product → inventory_snapshot | 1:N | `inventory_snapshot.sku_id` | Mandatory (1 per active day) | |
| product → customer_order | 1:N | `customer_order.sku_id` | Optional | |
| product → price_decision | 1:N | `price_decision.sku_id` | Mandatory (≥1: INITIAL) | |
| product ⇄ promotion | N:M | via `promotion_product` | Optional | |
| promotion → promotion_rule | 1:N | `promotion_rule.promotion_id` | Mandatory (≥1) | |
| promotion → promotion_rule (target) | 1:N | `promotion_rule.target_promotion_id` | Optional (only `EXCLUDES_PROMOTION`) | Self-relationship via rule |
| cost_record → price_decision | 1:N | `price_decision.cost_record_id` | Mandatory | Cost basis used |
| cost_record → price_history | 1:N | `price_history.cost_record_id` | Mandatory | For floor reconciliation |
| cost_record → customer_order | 1:N | `customer_order.cost_record_id` | Mandatory | Margin basis |
| price_decision → price_history | 1:N | `price_history.source_decision_id` | Nullable at DB level (null = anomaly) | One decision sets 1..n days |
| price_decision → price_decision | 1:0..1 | `price_decision.previous_decision_id` | Optional (null for INITIAL) | Supersession chain |
| price_history → customer_order | 1:N | `customer_order.price_history_id` | Mandatory | Order price traceable to the day's price |
| customer_order ⇄ promotion | N:M | via `order_promotion` | Optional | Includes REJECTED coupon attempts |
| price_decision ⇄ promotion | N:M | via `price_decision_promotion` | Optional | APPLIED / REJECTED with reason |
| price_decision ⇄ competitor_observation | N:M | via `price_decision_observation` | Optional | USED / EXCLUDED_* with age |
| product / promotion / price_decision → *_status_event | 1:N | `sku_id` / `promotion_id` / `decision_id` | Mandatory (≥1) | Event history, never overwritten |

`docs/er_diagram.md` will hold a Mermaid `erDiagram` containing all of the above.

### 4.3 Lifecycle states (allowed transitions)

- **Product:** `∅→ACTIVE` (launched before the window) | `∅→NEW→ACTIVE` (launched in-window; NEW for 14 days) | `ACTIVE→DISCONTINUED` (terminal). There are no decisions or orders after `discontinued_date`.
- **Promotion:** `∅→DRAFT→SCHEDULED→ACTIVE→EXPIRED`. Also `DRAFT|SCHEDULED→CANCELLED` (never applied) and `ACTIVE→CANCELLED` (early stop; nothing applied after the cancel timestamp). The ACTIVE event is at `start_date 00:00Z` and EXPIRED at `end_date+1 00:00Z`. Final `promotion.status` is the as-of status on 29-06-2026 and equals the last event.
- **PriceDecision:**
  - `∅→PROPOSED→APPROVED→APPLIED→SUPERSEDED`
  - `PROPOSED→ESCALATED→APPROVED→APPLIED`. Escalation happens when |Δ| > 10% or the guardrails conflict. Approval comes from a `MERCH_ANALYST` or `OPS_MANAGER` actor.
  - `PROPOSED|ESCALATED→REJECTED`. The previous price holds.

  Final `price_decision.status` equals the last event.

### 4.4 Effective dates
- `cost_record.effective_from` (inclusive) and `effective_to` (exclusive; null for the latest record), monthly.
- `promotion.start_date` and `end_date` (both inclusive).
- `promotion_rule.effective_from` and `effective_to` (inside the promotion window).
- `product.launch_date` and `discontinued_date`.
- `price_decision.decision_ts` (when it was computed) and `effective_date` (when the price applies).
- `price_history.price_date` and `inventory_snapshot.snapshot_date` (daily).
- `competitor_observation.source_captured_at` (truth time) and `ingested_at` (system time, ≥ capture).

---

## 5. Data dictionary outline

`docs/data_dictionary.md` will hold the full table. Notation: **R** = required, **O** = optional, **U** = unique. Money is INR `NUMERIC(12,2)`. Percentages are fractions `NUMERIC(6,4)`.

### 5.1 product
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| sku_id | VARCHAR(10) | R | `^SKU-\d{6}$` | PK | |
| product_name | VARCHAR(120) | R | synthetic "Brand Subcategory Variant" | | not blank |
| brand | VARCHAR(40) | R | fictional brand list | | |
| category | VARCHAR(20) | R | GROCERY, PERSONAL_CARE, BEAUTY, HEALTH, HOME_KITCHEN, ELECTRONICS, APPAREL, TOYS, SPORTS, STATIONERY | | enum |
| subcategory | VARCHAR(40) | R | per-category list | | belongs to category |
| uom | VARCHAR(8) | R | EACH, PACK | | enum |
| pack_size | INT | R | units | | ≥ 1 |
| status | VARCHAR(16) | R | NEW, ACTIVE, DISCONTINUED | | = last status event |
| launch_date | DATE | R | | | ≤ window end |
| discontinued_date | DATE | O | | | > launch_date; required iff DISCONTINUED |
| mrp | NUMERIC(12,2) | R | INR | | > 0; > first landed cost |
| map_price | NUMERIC(12,2) | O | INR | | 0 < map < mrp |
| min_margin_pct | NUMERIC(6,4) | R | fraction | | 0.05–0.60 |
| min_promo_margin_pct | NUMERIC(6,4) | R | fraction | | 0 < x ≤ min_margin_pct |
| price_position | VARCHAR(8) | R | VALUE, MATCH, PREMIUM | | enum |
| competitor_tracked | BOOLEAN | R | | | |
| created_at | TIMESTAMPTZ | R | UTC | | ≤ launch_date |

### 5.2 cost_record
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| cost_record_id | VARCHAR(25) | R | `^COST-SKU-\d{6}-\d{4}-(0[1-9]\|1[0-2])$` | PK | embedded sku/month match columns |
| sku_id | VARCHAR(10) | R | | FK product | |
| cost_month | CHAR(7) | R | YYYY-MM | U(sku_id, cost_month) | |
| effective_from | DATE | R | | | first of month, or launch_date if later |
| effective_to | DATE | O | exclusive | | > effective_from; no overlap or gap per SKU |
| unit_cost | NUMERIC(12,2) | R | INR | | > 0 |
| freight_cost | NUMERIC(12,2) | R | INR | | ≥ 0 |
| landed_cost | NUMERIC(12,2) | R | INR | | = unit_cost + freight_cost |
| change_pct | NUMERIC(6,4) | O | fraction | | = landed/prev_landed − 1; null on first |
| change_reason | VARCHAR(20) | R | INITIAL, NO_CHANGE, SUPPLIER_INCREASE, SUPPLIER_DECREASE, FREIGHT_CHANGE | | consistent with change_pct sign |

### 5.3 price_history
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| price_history_id | VARCHAR(23) | R | `^PRC-SKU-\d{6}-\d{8}$` | PK | embedded sku/date match |
| sku_id | VARCHAR(10) | R | | FK | |
| price_date | DATE | R | | U(sku_id, price_date) | in product active window |
| list_price | NUMERIC(12,2) | R | INR | | floor ≤ list ≤ mrp (business rule) |
| shelf_price | NUMERIC(12,2) | R | INR | | ≤ list_price; = list after applied markdowns |
| previous_list_price | NUMERIC(12,2) | O | INR | | = prior day list_price; null on first day |
| change_reason | VARCHAR(32) | O (DB) | ReasonCode or NO_CHANGE | | required when price changed (business rule) |
| source_decision_id | VARCHAR(15) | O (DB) | | FK price_decision | list_price = decision.final_list_price |
| cost_record_id | VARCHAR(25) | R | | FK | month(price_date) = cost_month |

### 5.4 competitor_observation
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| observation_id | VARCHAR(16) | R | `^OBS-2026-\d{7}$` | PK | |
| sku_id | VARCHAR(10) | R | | FK | product.competitor_tracked = true |
| competitor | VARCHAR(12) | R | COMP_ALPHA, COMP_BETA, COMP_GAMMA | U(sku, competitor, source_captured_at) | |
| observed_price | NUMERIC(12,2) | R | INR | | > 0; within 0.5×–1.5× mrp |
| source_captured_at | TIMESTAMPTZ | R | UTC | | in window |
| ingested_at | TIMESTAMPTZ | R | UTC | | ≥ source_captured_at |
| source_type | VARCHAR(16) | R | SCRAPE_FEED, PARTNER_FEED, MANUAL_AUDIT | | |
| competitor_in_stock | BOOLEAN | R | | | |
| match_confidence | NUMERIC(4,3) | R | 0–1 | | |

### 5.5 inventory_snapshot
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| inventory_snapshot_id | VARCHAR(23) | R | `^INV-SKU-\d{6}-\d{8}$` | PK | embedded sku/date match |
| sku_id, snapshot_date | | R | | U(sku, date) | active window |
| opening_on_hand | INT | R | units | | ≥0; = previous closing |
| receipts_units | INT | R | units | | ≥ 0 |
| units_sold | INT | R | units | | = Σ FULFILLED order qty (sku, date) |
| adjustment_units | INT | R | units | | ≤ 0 (shrinkage) |
| closing_on_hand | INT | R | units | | = opening + receipts − sold + adjustment ≥ 0 |
| on_order_units | INT | R | units | | ≥ 0 |
| demand_units | INT | R | units | | ≥ units_sold |
| lost_sales_units | INT | R | units | | = demand − sold |
| stockout_flag | BOOLEAN | R | | | = (closing = 0 OR lost_sales > 0) |
| days_of_cover | NUMERIC(8,2) | R | days | | = closing / max(avg 28-day units sold, 0.1) |

### 5.6 promotion
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| promotion_id | VARCHAR(17) | R | `^PROMO-2026-\d{6}$` | PK | |
| promotion_name | VARCHAR(80) | R | | | |
| promo_type | VARCHAR(16) | R | MARKDOWN_PCT, MARKDOWN_AMOUNT, COUPON_PCT, COUPON_AMOUNT | | |
| stack_group | VARCHAR(10) | R | SITEWIDE, CATEGORY, SKU, COUPON | | COUPON iff COUPON_* |
| discount_pct | NUMERIC(6,4) | O | fraction | | required iff *_PCT; 0.05–0.50 |
| discount_amount | NUMERIC(12,2) | O | INR | | required iff *_AMOUNT; > 0 |
| coupon_code | VARCHAR(16) | O | `^CPN-2026-\d{6}$` | U | required iff COUPON_* |
| start_date / end_date | DATE | R | | | start ≤ end; MARKDOWN: Mon→Sun |
| priority | INT | R | 1 (highest)–100 | | |
| channel | VARCHAR(6) | R | ALL, ECOM, STORE | | |
| funding_source | VARCHAR(8) | R | VENDOR, RETAILER | | |
| status | VARCHAR(10) | R | DRAFT, SCHEDULED, ACTIVE, EXPIRED, CANCELLED | | = last event |
| created_at | TIMESTAMPTZ | R | | | < start_date |

### 5.7 promotion_rule
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| rule_id | VARCHAR(16) | R | `^RULE-2026-\d{6}$` | PK | |
| promotion_id | VARCHAR(17) | R | | FK | |
| rule_type | VARCHAR(28) | R | EXCLUSIVE, EXCLUDES_PROMOTION, MAX_STACK_COUNT, MAX_COMBINED_DISCOUNT_PCT, MIN_ORDER_QTY | U(promotion_id, rule_type, target_promotion_id) | |
| target_promotion_id | VARCHAR(17) | O | | FK promotion | required iff EXCLUDES_PROMOTION; ≠ promotion_id; windows overlap |
| param_numeric | NUMERIC(10,4) | O | count / fraction / units | | required for MAX_* and MIN_ORDER_QTY |
| effective_from / effective_to | DATE | R | | | inside the promotion window |

### 5.8 customer_order
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| order_id | VARCHAR(16) | R | `^ORD-2026-\d{7}$` | PK | |
| order_ts | TIMESTAMPTZ | R | UTC | | |
| order_date | DATE | R | | | = date(order_ts) |
| sku_id | VARCHAR(10) | R | | FK | product active; not discontinued |
| customer_ref | VARCHAR(13) | R | `^CUST-\d{7}$` opaque, no PII | | not an FK (documented) |
| customer_segment | VARCHAR(8) | R | NEW, REGULAR, LOYALTY | | |
| channel | VARCHAR(6) | R | ECOM, STORE | | |
| quantity | INT | R | units | | 1–10 |
| price_history_id | VARCHAR(23) | R | | FK | sku and date match the order |
| cost_record_id | VARCHAR(25) | R | | FK | effective on order_date |
| unit_list_price | NUMERIC(12,2) | R | INR | | = price_history.list_price |
| gross_amount | NUMERIC(12,2) | R | INR | | = qty × unit_list_price |
| discount_amount | NUMERIC(12,2) | R | INR | | = Σ APPLIED order_promotion.discount_amount |
| net_amount | NUMERIC(12,2) | R | INR | | = gross − discount > 0 |
| unit_landed_cost | NUMERIC(12,2) | R | INR | | = cost_record.landed_cost |
| gross_margin_amount | NUMERIC(12,2) | R | INR | | = net − qty × unit_landed_cost |
| order_status | VARCHAR(10) | R | FULFILLED, CANCELLED | | CANCELLED is excluded from units_sold |

### 5.9 price_decision
| Field | Type | R/O | Allowed / Unit | U | Validation |
|---|---|---|---|---|---|
| decision_id | VARCHAR(15) | R | `^DEC-2026-\d{6}$` | PK | |
| sku_id | VARCHAR(10) | R | | FK | |
| decision_ts | TIMESTAMPTZ | R | 06:00Z on decision date | | |
| effective_date | DATE | R | | U(sku_id, effective_date) | ≥ date(decision_ts) |
| trigger | VARCHAR(16) | R | INITIAL, WEEKLY_REVIEW, COST_CHANGE, STOCKOUT_EVENT, MANUAL | | |
| decision_source | VARCHAR(16) | R | ENGINE, MANUAL_OVERRIDE | | |
| previous_decision_id | VARCHAR(15) | O | | FK self | null iff INITIAL |
| cost_record_id | VARCHAR(25) | R | | FK | effective on effective_date |
| landed_cost | NUMERIC(12,2) | R | INR | | = cost_record.landed_cost |
| prev_list_price | NUMERIC(12,2) | O | INR | | |
| floor_price / ceiling_price | NUMERIC(12,2) | R | INR | | recomputable (§8) |
| competitor_ref_price | NUMERIC(12,2) | O | INR | | median of USED observations |
| competitor_fresh_count / competitor_stale_count | INT | R | count | | = bridge counts |
| on_hand_units | INT | R | units | | = previous day closing |
| demand_7d_units / demand_28d_units | INT | R | units | | = Σ units_sold in prior windows |
| velocity_ratio | NUMERIC(8,4) | R | ratio | | (d7/7)/(d28/28) |
| days_of_cover | NUMERIC(8,2) | R | days | | |
| target_price | NUMERIC(12,2) | R | INR | | |
| final_list_price | NUMERIC(12,2) | R | INR | | in [floor, ceiling] (business rule) |
| markdown_discount_pct | NUMERIC(6,4) | R | fraction | | combined applied markdown |
| expected_net_price | NUMERIC(12,2) | R | INR | | = final_list × (1 − markdown) |
| margin_pct | NUMERIC(6,4) | R | fraction | | = (net − landed)/net (business rule) |
| required_margin_pct | NUMERIC(6,4) | R | fraction | | min_margin or min_promo_margin |
| decision_type | VARCHAR(8) | R | INCREASE, DECREASE, HOLD | | from the sign of final − prev |
| primary_reason_code | VARCHAR(32) | R | ReasonCode | | |
| reason_codes | VARCHAR(400) | R | pipe-delimited ReasonCodes | | non-empty |
| explanation | TEXT | R | JSON array of ExplanationStep | | parseable |
| rule_version | VARCHAR(20) | R | `pricing-rules-v1` | | |
| status | VARCHAR(10) | R | PROPOSED, ESCALATED, APPROVED, REJECTED, APPLIED, SUPERSEDED | | = last event |

### 5.10 Bridge and event tables
- **promotion_product:** promotion_id FK, sku_id FK, PK(both), added_at.
- **order_promotion:**
  - order_id FK, promotion_id FK, PK(both)
  - sequence_no, coupon_presented BOOL
  - application_status (APPLIED/REJECTED)
  - rejection_reason (ReasonCode, required iff REJECTED)
  - discount_amount (0 if REJECTED)
- **price_decision_promotion:** decision_id FK, promotion_id FK, PK(both), application_status, reason_code, discount_pct_effective.
- **price_decision_observation:** decision_id FK, observation_id FK, PK(both), usage (USED, EXCLUDED_STALE, EXCLUDED_LOW_CONFIDENCE, EXCLUDED_SUPERSEDED), age_hours NUMERIC(8,2).
- **\*_status_event:** event_id PK (`PEV-2026-000001` / `PMEV-2026-000001` / `DEV-2026-0000001`), parent FK, from_status (null on first), to_status, event_ts, actor_role (SYSTEM, MERCH_ANALYST, OPS_MANAGER, WAREHOUSE_OPERATOR), note.

**ReasonCode enum:** INITIAL_PRICE, NO_CHANGE, COST_CHANGE, COMPETITOR_ALIGN, COMPETITOR_DATA_STALE, OVERSTOCK_MARKDOWN, LOW_COVER_INCREASE, STOCKOUT_HOLD, FLOOR_ENFORCED, CEILING_ENFORCED, STEP_LIMIT_APPLIED, PRICE_POINT_ROUNDED, PROMO_APPLIED, PROMO_REJECTED_EXCLUSIVE, PROMO_REJECTED_EXCLUDED_PAIR, PROMO_REJECTED_STACK_LIMIT, PROMO_REJECTED_DISCOUNT_CAP, PROMO_REJECTED_MARGIN, PROMO_REJECTED_MIN_QTY, PROMO_SUPPRESSED_STOCKOUT, GUARDRAIL_CONFLICT_ESCALATED, LARGE_CHANGE_ESCALATED, MANUAL_OVERRIDE.

---

## 6. ID rules

| Entity | Pattern | Example | Counter scope | Assignment order |
|---|---|---|---|---|
| Product | `SKU-%06d` | `SKU-000001` | global | generation index (category-shuffled, seeded) |
| CostRecord | `COST-<sku>-YYYY-MM` | `COST-SKU-000001-2026-01` | natural key | — |
| PriceHistory | `PRC-<sku>-YYYYMMDD` | `PRC-SKU-000001-20260101` | natural key | — |
| InventorySnapshot | `INV-<sku>-YYYYMMDD` | `INV-SKU-000001-20260101` | natural key | — |
| Promotion | `PROMO-YYYY-%06d` | `PROMO-2026-000001` | per year | sorted by (start_date, promo_type, generation index) |
| PromotionRule | `RULE-YYYY-%06d` | `RULE-2026-000001` | per year | promotion_id order, then rule order |
| CompetitorObservation | `OBS-YYYY-%07d` | `OBS-2026-0000001` | per year | (source_captured_at, sku_id, competitor) |
| Order | `ORD-YYYY-%07d` | `ORD-2026-0000001` | per year | (order_date, sku_id, intra-day seq) |
| PriceDecision | `DEC-YYYY-%06d` | `DEC-2026-000001` | per year | (effective_date, sku_id) |
| Coupon code | `CPN-YYYY-%06d` | `CPN-2026-000001` | per year | promotion order |
| Status events | `PEV-` / `PMEV-` / `DEV-` | `DEV-2026-0000001` | per year | (event_ts, parent id) |
| Ground truth | `GT-YYYY-%06d` | `GT-2026-000001` | per run | (issue_type, entity_id) |
| Detected exception | `EXC-YYYY-%07d` | `EXC-2026-0000001` | per run | (issue_type, entity_id) |

Rules:
1. `<sku>` is always the full SKU ID, for example `COST-SKU-000001-2026-01`.
2. YYYY is the year of the record's business date.
3. Counters are zero-padded. `SequenceCounter` throws on overflow, and the validator re-checks capacity.
4. IDs are **immutable**. Anomaly injection changes attribute values only, never IDs.
5. All formatting and parsing goes through `IdFactory`. The validator uses the same `IdPatterns` regexes.
6. **ID-embedded field consistency:** the validator checks these:
   - The `sku_id`/date inside `PRC`/`INV` IDs match their columns.
   - The `sku_id`/month inside `COST` IDs match.
   - `customer_order.price_history_id` matches the order's sku and date.
7. Assignment order is a total order, so IDs are reproducible.

---

## 7. Generator design

### 7.1 Seed and scale
- Default seed is `20261022`, with CLI `--seed` to override.
- Every random draw comes from `RandomStreams.of(streamName, key)`. That function builds `new java.util.Random(first8Bytes(SHA-256(seed + "|" + streamName + "|" + key)))`.
- So streams are independent. Changing one SKU's behaviour or adding anomalies **does not reshuffle other streams**.
- All maths uses `StrictMath`. Money is converted to `BigDecimal(scale 2, HALF_UP)` at every boundary.
- There is no `Instant.now()`. A fixed `reference_ts = 2026-07-01T00:00:00Z` is used for `created_at` derivations.
- Only ordered collections (`TreeMap`/`LinkedHashMap`) are used, and rows are sorted before writing.

**Scale profiles** (in `application.yml`; every field can be overridden by a CLI flag):

| Profile | SKUs | Days | Promotions | Anomalies per type | Competitors | Purpose |
|---|---|---|---|---|---|---|
| `starter` | 200 | 60 (01-01 → 01-03-2026; spans 3 cost months) | 80 | 5 | 3 | Committed sample, CI tests |
| `full` | 5,000 | 180 | 2,000 | 50 | 3 | Brief baseline |
| `tiny` | 20 | 14 | 8 | 1 | 3 | Unit/IT fixtures |

Starter and full use the **same code path**. Only the `ScaleProfile` differs.

### 7.2 Generation order
1. **Product.** 10 categories with weights (Grocery 20%, Personal Care 12%, Beauty 8%, Health 6%, Home & Kitchen 14%, Electronics 8%, Apparel 12%, Toys 6%, Sports 6%, Stationery 8%).
   - 92% launched before 01-01-2026, 5% launched in-window, 3% discontinued in-window.
   - `mrp` comes from a per-category lognormal.
   - `min_margin_pct` comes from category bands, for example Grocery 0.10–0.18 and Electronics 0.12–0.22, Apparel 0.35–0.55.
   - `min_promo_margin_pct` = min_margin − U(0.03, 0.08), with a lower bound of 0.02.
   - 10% of products have `map_price`. 80% are `competitor_tracked`.
   - Product status events are written here.
2. **CostRecord.** One per SKU per active month.
   - Initial landed cost = mrp / U(markup band per category, 1.4–2.6). Freight is 2–6% of unit cost.
   - Each month: 70% NO_CHANGE, 22% small change of ±1–5%, 4% FREIGHT_CHANGE, and 4% **supplier shock** of +10–20%.
   - Supplier shocks create the natural guardrail conflicts, where the new floor exceeds the old price.
3. **Promotion + PromotionRule + promotion_product + status events.**
   - The mix is 60% markdown and 40% coupon.
   - Markdowns run 1–4 whole weeks starting on a Monday. Coupons run 3–21 days.
   - Each promotion targets one category or a SKU list (5–150 SKUs). That gives about 80k bridge rows at full scale.
   - Rules:
     - 30% EXCLUSIVE.
     - 25% have an EXCLUDES_PROMOTION pair with an overlapping promotion that shares SKUs. This deliberately creates **conflicting coupons**.
     - Every promotion gets MAX_STACK_COUNT (2 or 3) and MAX_COMBINED_DISCOUNT_PCT (0.30–0.45).
     - 15% get MIN_ORDER_QTY of 2.
   - 3% are cancelled before they start and 1% are cancelled while active.
4. **CompetitorObservation.** Observations exist only for tracked SKUs, and each competitor covers 40–80% of them.
   - The competitor price is a weekly random walk (σ = 1.5%) anchored at mrp × U(0.80, 0.98).
   - Cadence: ALPHA every 1–2 days, BETA every 2–4 days, GAMMA weekly.
   - 8% of SKU-competitor pairs get **gap episodes** of 7–20 days, which produce stale observations.
   - Ingestion lag is exponential with a mean of 6 h. 2% are **late feeds** that arrive 2–5 days after capture.
   - Match confidence is drawn from Beta-like (0.6–1.0), and 5% fall below 0.80.
   - This table is generated before the simulation because competitors do not react to our prices.
5. **MarketSimulator day loop** (`for day in window: for sku in active SKUs ordered by id`). This writes the dependent and event records:
   1. Receive replenishment arrivals into the day's `receipts_units`.
   2. If today is a decision day (INITIAL, Monday, COST_CHANGE on the 1st, or the day after a stockout), build a `PricingContext`. It uses **only data visible before `decision_ts`**: the previous day's inventory, trailing sales, and observations with `source_captured_at ≤ decision_ts` and `ingested_at ≤ decision_ts`.
   3. Run `PricingEngine`, then write `price_decision`, its bridges and its status events. The previous decision becomes SUPERSEDED.
   4. Write the `price_history` row: list price from the decision in effect; shelf price = list × (1 − markdown); `change_reason` = the decision's primary reason if the price changed, else NO_CHANGE.
   5. Demand: λ = base_demand × weekday factor × (shelf_price / ref_price)^elasticity, with the Sat/Sun factor at 1.25. Then `demand_units ~ Poisson(λ)`.
   6. Units sold = min(demand, on hand). The remainder is lost sales.
   7. Split the units sold into orders of qty 1–3. Assign channel (ECOM 55% / STORE 45%) and segment.
   8. Coupons: 12% of orders present a coupon that is valid for the SKU, channel and date. `PromotionStackResolver` decides APPLIED or REJECTED, so conflicting coupons are rejected naturally with a reason. 2% of orders are CANCELLED.
   9. Inventory: shrinkage of −1 with probability 0.5%, then closing stock. If the inventory position is at or below s, place an order up to S that arrives after a lead time of 3–10 days. 5% of SKUs are "fragile supply" with lead time ×2, and these generate stockouts.
   10. Derive `days_of_cover`, `stockout_flag` and `lost_sales_units`.
6. **Finalise.**
   - Promotion statuses are as of 29-06-2026.
   - Decision APPLIED and SUPERSEDED events.
   - Sort every table, then write it.
7. **Anomaly injection** (§9) happens through hooks during step 5. Each hook is applied to the *already valid* baseline record, before that record is written.
8. **Ground truth, manifest and checksums** are written last.

### 7.3 Derived values
These are computed from source records, never drawn at random:
- `landed_cost`, `change_pct`
- floor and ceiling
- `previous_list_price`, `shelf_price`
- inventory flows, `days_of_cover`, `stockout_flag`, `lost_sales_units`
- order `gross/discount/net/gross_margin`
- `decision_type`, `margin_pct`, `velocity_ratio`
- competitor ref price and fresh/stale counts
- final product and promotion statuses (from events)

### 7.4 Expected full-scale volumes (verified against the manifest after the first full run)
| Table | ≈ rows |
|---|---|
| product | 5,000 |
| cost_record | ~29,500 |
| price_history / inventory_snapshot | ~880,000 each |
| competitor_observation | ~535,000 (≈ 4,000 tracked SKUs × 60% average coverage × ~206 captures per pair over 180 days; measured 534,705 for seed 20261022. The earlier ~400,000 estimate did not match the §7.2 cadence.) |
| promotion / promotion_rule / promotion_product | 2,000 / ~5,500 / ~80,000 |
| price_decision | ~150,000 (well under the 999,999 cap) |
| price_decision_observation / _promotion | ~350,000 / ~120,000 |
| customer_order / order_promotion | ~700,000 / ~250,000 |
| status events (3 tables) | ~470,000 |

**Performance targets:** generate in under 3 min and load in under 6 min, with `-Xmx2g`. The generator streams rows to CSV, and in-memory state is per SKU only.

### 7.5 Realism bands
`DatasetMetricsCalculator` gives WARN if a metric falls outside its band.

| Metric | Band |
|---|---|
| Stockout SKU-day rate | 2–6% |
| Share of observations stale at decision time | 5–15% |
| SKU-days under a markdown | 15–30% |
| Coupon presentations rejected for conflict | 10–30% |
| Average realised gross margin | 15–40% |
| List-price changes per SKU per month | 0.5–3 |
| Pareto: top 20% of SKUs share of units | 55–85% |

---

## 8. Pricing engine rules

`PricingEngine.price(PricingContext) → PricingResult` is pure and deterministic. The **same class** is used by the generator and by `POST /pricing/simulate`, so an API simulation of a stored decision's inputs reproduces that stored decision. That reproduction is an integration test.

**Policy defaults** (`pricing.policy.*`):
- `freshness_hours=72`
- `min_match_confidence=0.80`
- `max_step_change_pct=0.08`
- `auto_approve_max_change_pct=0.10`
- `overstock_days=60`
- `low_cover_days=7`
- `high_velocity_ratio=1.2`
- `overstock_adj=-0.03`
- `low_cover_adj=+0.03`
- Position offsets: VALUE −0.03, MATCH 0, PREMIUM +0.05
- `default_max_stack=2`
- `default_max_combined_discount=0.40`

| Step | Rule | Output / reason codes |
|---|---|---|
| S1 Inputs | Load the cost record effective on `effective_date`, product policy, previous price, inventory and demand signals, candidate observations and candidate promotions. Snapshot everything into `PricingContext`. | — |
| S2 Floor | `floor = roundUpToPaise(max(landed_cost / (1 − min_margin_pct), map_price ?? 0))` | — |
| S3 Ceiling | `ceiling = mrp`. If `ceiling < floor`, this is a **guardrail conflict**: price = floor, status ESCALATED, and an OPS_MANAGER approves. | GUARDRAIL_CONFLICT_ESCALATED |
| S4 Competitor ref | For each competitor, take the latest observation with `captured ≤ decision_ts` and `ingested ≤ decision_ts`. Mark it **EXCLUDED_STALE** if `age > 72h` and EXCLUDED_LOW_CONFIDENCE if confidence < 0.80. Older ones are EXCLUDED_SUPERSEDED. `ref = median(USED)`. If nothing is USED, ref = null. **Every** considered observation is written to `price_decision_observation` with its age. | COMPETITOR_DATA_STALE when observations exist but none is fresh |
| S5 Target | If ref ≠ null: `target = ref × (1 + position_offset)`. Else `target = prev_price` (or `mrp × 0.95` for INITIAL). | COMPETITOR_ALIGN |
| S6 Demand/inventory | Stockout (on_hand = 0): target = prev and markdowns are suppressed. Overstock (cover > 60): target × 0.97. Low cover (< 7) with velocity ≥ 1.2: target × 1.03. | STOCKOUT_HOLD / OVERSTOCK_MARKDOWN / LOW_COVER_INCREASE |
| S7 Step limit | Bound the move to ±8% of prev, except INITIAL and COST_CHANGE when needed to reach the floor. | STEP_LIMIT_APPLIED |
| S8 Clamp | `candidate = clamp(target, floor, ceiling)` | FLOOR_ENFORCED / CEILING_ENFORCED |
| S9 Price point | Round to a whole rupee ending in 9 (≥ ₹100) or a whole rupee (< ₹100). If the rounded value falls below the floor, take the next point up. If above the ceiling, take the next point down. If no point fits, use the unrounded candidate. | PRICE_POINT_ROUNDED |
| S10 Promotions | Eligible markdowns: status allows, date in window, SKU in bridge, channel matches, not cancelled, SKU not stocked out. Sort by `(priority asc, promotion_id asc)`, which is a total order. Add greedily and reject a promotion if any of these hold:<br>- **EXCLUSIVE** while anything is already applied (or an applied one is exclusive).<br>- An **EXCLUDES_PROMOTION** pair is present.<br>- The stack count exceeds the minimum MAX_STACK_COUNT among the applied promotions and the candidate.<br>- The combined discount exceeds the minimum MAX_COMBINED_DISCOUNT_PCT.<br><br>Discounts apply **sequentially on the running price**: percent first, then amount. | PROMO_APPLIED / PROMO_REJECTED_* / PROMO_SUPPRESSED_STOCKOUT |
| S11 Margin | `net = candidate × Π(1 − pct) − Σ amounts`. **Margin is always computed on `net` with the effective landed cost** (`MarginCalculator.netMargin`). Required = `min_promo_margin_pct` if any promotion is applied, else `min_margin_pct`. If it fails, remove the last-added promotion (lowest precedence) and recompute until it passes. | PROMO_REJECTED_MARGIN |
| S12 Outcome | `decision_type` from the sign of `final − prev`. Primary reason is the most material step, by a fixed precedence list. If \|Δ\| > 10%, status ESCALATED, else APPROVED, then APPLIED. Store all inputs, the bridges, `reason_codes`, the `explanation` JSON and `rule_version`. | LARGE_CHANGE_ESCALATED |

**Order-level coupons** use the same `PromotionStackResolver`, seeded with the markdowns already applied on the day's decision. They also check MIN_ORDER_QTY and channel. Margin on orders is recomputed on the net amount for metrics. Coupons are customer-presented, so the margin rule for coupons is **not** a rejection criterion at order time. Instead, that exposure is reported in metrics. This is documented as a business assumption.

**"Every price is explainable":**
`price_history.source_decision_id` → `price_decision` (inputs + explanation) → `cost_record`, `price_decision_observation` → `competitor_observation`, `price_decision_promotion` → `promotion` → `promotion_rule`. The explain endpoint renders this chain.

---

## 9. Anomaly injection

**Principle:** generate the valid baseline record, then let `AnomalyInjector` mutate the minimum fields needed, keeping all derived arithmetic **internally consistent**. Then write a ground-truth row. Only the targeted rule is broken, and no unrelated row is silently corrupted.

- Targets are chosen up front by `AnomalyPlanner` from the dedicated `anomaly` RNG stream, among records that are eligible at baseline.
- The number of anomalies per type equals `anomaliesPerType`: 5 for starter, 50 for full.
- Downstream causal effects are intended and are not corruption. For example, a below-floor price increases that day's demand.
- `--anomalies-per-type 0` produces a clean dataset. Tests use it to prove the baseline has **zero** business-rule findings.

| # | issue_type | Eligible target | Injection | Detection rule (no labels used) | expected_action |
|---|---|---|---|---|---|
| 1 | `PRICE_BELOW_FLOOR` | A weekly-review decision | Record as `decision_source=MANUAL_OVERRIDE`, reason `MANUAL_OVERRIDE`, with `final_list_price = floor × U(0.85, 0.97)` rounded down. The price-history days it covers carry the price, and the orders use it. *About 1% of legitimate MANUAL_OVERRIDE decisions exist within guardrails, so the source field is not a giveaway.* | Recomputed floor > `final_list_price` (and on covered PRC rows) | BLOCK_PRICE |
| 2 | `PROMO_STACK_VIOLATION` | An order where the baseline **rejected** a conflicting coupon (exclusive, excluded pair, stack limit or cap) | Flip it to APPLIED and recompute `discount_amount`, `net_amount` and `gross_margin_amount` consistently | Re-run stacking rules on the APPLIED set per order → violation | REJECT_PROMOTION |
| 3 | `STALE_OBSERVATION_AS_CURRENT` | A decision where at least one considered observation was EXCLUDED_STALE | Mark it USED, recompute `competitor_ref_price`, target, final price and counts. The engine output follows the stale price. | Bridge row USED with `age_hours > 72` | EXCLUDE_OBSERVATION |
| 4 | `MARGIN_BEFORE_DISCOUNT` | A decision where the baseline rejected a markdown with **PROMO_REJECTED_MARGIN** | Re-apply it, store `margin_pct = (list − landed)/list`, which is the wrong basis and passes, and recompute the net and shelf price | Recomputed net margin ≠ stored `margin_pct`, and the net margin < required | RECOMPUTE_MARGIN_ON_NET |
| 5 | `PRICE_CHANGE_WITHOUT_REASON` | A non-decision day mid-week | Change `list_price` by ±3–8% from that day until the next decision. Set `change_reason` and `source_decision_id` to null for the span. Orders on those days use that price. | PRC row where the price ≠ the previous day and (reason null or decision null) → flag the first row of the span | REQUIRE_REASON |

**`labels/ground_truth.csv` columns** (and `ground_truth.json` = `{seed, scale, counts_by_issue_type, records:[…]}`):

```
gt_id, issue_type, entity_type, entity_id, sku_id, event_date,
related_ids, field_name, baseline_value, injected_value,
violated_rule, expected_detection, expected_action
```
Example:
`GT-2026-000001,PRICE_BELOW_FLOOR,price_decision,DEC-2026-004512,SKU-000873,2026-02-09,PRC-SKU-000873-20260209|COST-SKU-000873-2026-02,final_list_price,1299.00,1149.00,LIST_PRICE_GTE_FLOOR,TRUE,BLOCK_PRICE`

**Leakage controls:**
1. Labels are written only to `labels/` and never to `csv/`. `BulkLoader` loads an allowlist from `TableRegistry` that excludes labels.
2. No feature table has an `is_anomaly`/`issue_type` column. A test scans every CSV header.
3. The `detection` and `validation` packages never import `generator.anomaly` or touch `labels/`. A test scans the source imports.
4. Injected records look like valid records (no sentinel values or special flags).
5. Only `EvaluateCommand` reads ground truth.
6. The API never exposes labels.

---

## 10. Validation checks and validation_summary

`ValidationRunner` runs SQL checks against the loaded DB (Postgres, or H2 in tests). Checks are **generated from `TableRegistry` metadata** where possible, so no table is missed.

| Category | ID range | Checks | Expected |
|---|---|---|---|
| PRIMARY_KEY | PK-001..017 | `GROUP BY pk HAVING COUNT(*)>1` and null PKs, per table | 0 |
| FOREIGN_KEY | FK-001..0xx | `LEFT JOIN parent WHERE parent.pk IS NULL AND child.fk IS NOT NULL` for every FK, bridges included | 0 |
| ENUM | EN-001.. | Every enum column ∈ allowed set | 0 |
| ID_FORMAT | ID-001.. | Regex per ID column. Embedded sku/date/month = columns. Counter capacity. | 0 |
| DATE_ORDER | DT-001.. | Cost periods have no overlap or gap. effective_from < effective_to. Promotion start ≤ end, rules inside the promotion window. ingested ≥ captured. order_date = date(order_ts). Records lie inside the product active window. No orders or decisions after discontinuation. created_at < start. | 0 |
| NO_LOOK_AHEAD | LA-001.. | Decision observations captured and ingested ≤ decision_ts. Decision demand windows end before effective_date. | 0 |
| RECONCILIATION | RC-001.. | landed = unit + freight. Inventory flow equation and opening = previous closing. units_sold = Σ FULFILLED order qty. Order gross/discount/net/margin. Order unit price = PRC list. Decision landed = cost record. fresh/stale counts = bridge. velocity and days of cover recomputed. shelf = list × (1 − markdown). PRC previous_list_price continuity. | 0 |
| LIFECYCLE | LC-001.. | Every transition is in the allowed set (§4.3). Event timestamps are monotonic. Final status = last event. Cancelled promotions are not applied after cancellation. A superseded decision has a successor. | 0 |
| DUPLICATE | DU-001.. | Natural keys: (sku, date) in PRC/INV/decision, (sku, competitor, captured_at), (promotion, sku), (order, promotion), (sku, cost_month), coupon_code | 0 |
| COVERAGE | CV-001.. | Each active SKU has one PRC and one INV row per active day. Each active month has a cost record. Each promotion has ≥1 rule and ≥1 SKU. Each SKU has an INITIAL decision. | 0 |
| BUSINESS_RULE | BR-001..005 | The 5 anomaly rules from §9 | = injected count (WARN, reconciled by evaluate) |

**Outputs:**
- `validation_summary.json`:
  ```json
  {
    "run_id", "seed", "scale", "dataset_fingerprint",
    "totals": {"checks", "passed", "failed", "warnings"},
    "checks": [{"check_id", "category", "table", "description",
                "rows_checked", "violations", "status", "sample_ids[≤5]"}],
    "business_rule_findings": {"PRICE_BELOW_FLOOR": 50, …}
  }
  ```
  `executed_at` is written to `run_log.json` instead, so the summary is checksum-stable.
- `validation_summary.md`: a table of the same data for the README and evidence.
- **Exit code:** non-zero if any structural category (everything except BUSINESS_RULE) has status FAIL.

**Evaluation (`evaluation_metrics.json`):**
- Per issue_type TP/FP/FN, precision, recall and F1, matched on `(issue_type, entity_type, entity_id)`.
- Overall micro and macro averages.
- Realism metrics (§7.5) with band status.
- Operational exception counts: stale-competitor decisions, guardrail escalations, stockout days, rejected coupons.
- Decision mix, revenue, units and gross margin by category.

---

## 11. Export, checksums and the reproducibility proof

- **CSV** (canonical, written by `generate`):
  - RFC 4180, UTF-8 without BOM, `\n` line endings.
  - Header row with fixed column order from `TableSpec`.
  - Null is an empty field. BigDecimal uses `toPlainString`. Dates and timestamps are ISO-8601 UTC.
  - Rows are sorted by PK.
- **JSON** (`export --formats json`): one array per table for master tables. For any table over 100k rows, JSON Lines (`.jsonl`) to keep memory flat. Keys are in column order.
- **SQL** (`export --formats sql`): `schema.sql` (the Flyway migrations concatenated) and `seed.sql` (multi-row INSERTs in FK-safe order, wrapped in a transaction). It is committed for starter only.
- **Manifest (`manifest.json`):**
  - generator version (git commit SHA if available, plus the pom version)
  - the exact command line, seed and scale profile values, rule_version, reference timestamp
  - per file: path, rows, columns, sha256
  - `dataset_fingerprint` = SHA-256 of the sorted `file:sha256` lines
  - provenance statement ("100% synthetic, no external data")
- **`checksums.sha256`:** `sha256sum -c` compatible, so reviewers can verify with standard tools.
- **Reproducibility proof:**
  - `scripts/repro_check.sh [starter|full]` generates into `out/repro/run-a` and `out/repro/run-b` with the same seed, then runs `diff run-a/checksums.sha256 run-b/checksums.sha256`. It also compares the row counts from both manifests and writes `docs/evidence/reproducibility_report.md` with both tables and an **IDENTICAL / DIFFERENT** verdict.
  - It then runs seed `20261023` and asserts the fingerprint **differs**, a sanity check that the seed matters.
  - `ReproCheckCommand` does the same in-process. `GeneratorDeterminismIT` asserts it in CI.
  - **Bonus proof:** `fresh_clone_test.sh` regenerates starter and compares the result against the committed `data/sample/checksums.sha256`.

---

## 12. Tests to write

All tests run with `./mvnw verify`. Unit tests use Surefire and `*IT` classes use Failsafe with H2.

**Unit tests (pure Java)**

| Test class | Cases |
|---|---|
| `IdFactoryTest` | Format and parse every type. Zero padding. Overflow throws. Embedded sku/date extraction. Invalid strings rejected. |
| `RandomStreamsTest` | Same (seed, stream, key) gives the same sequence. Different keys are independent. Changing one stream leaves others unaffected. |
| `DistributionsTest` | Poisson and lognormal means within tolerance over 100k draws (fixed seed). |
| `GuardrailCalculatorTest` | Floor from margin. MAP dominates. Paise rounding up. Ceiling = MRP. Ceiling < floor → conflict. |
| `MarginCalculatorTest` | Net basis. Sequential pct-then-amount. **Regression test: margin on list ≠ margin on net, and the engine uses net.** Zero or negative net rejected. |
| `CompetitorReferenceResolverTest` | Age exactly 72h00m is fresh, 72h00m01s is stale. Late ingestion excluded. Low confidence excluded. Median with an even count. All stale → null + COMPETITOR_DATA_STALE. |
| `DemandSignalCalculatorTest` | Velocity ratio with zero 28-day sales (no division by zero). Days of cover floor at 0.1. |
| `PromotionStackResolverTest` | EXCLUSIVE first and later. Excluded pair. Stack limit = min over the set. Combined cap. Priority tie broken by ID. **Shuffled input order gives identical output.** MIN_ORDER_QTY. Channel mismatch. Cancelled promotion ineligible. |
| `PricePointRounderTest` | Endings in 9. Bounds respected. No valid point → unrounded. |
| `PricingEngineTest` | Each reason code is reachable. Increase, decrease, hold. Stockout suppresses markdown. Margin drops the lowest-precedence promotion. Step limit. Escalation at Δ > 10%. Explanation has all 12 steps. |
| `GoldenScenariosTest` | 20 hand-computed scenarios from `golden/pricing_scenarios.json`. |

**Generator, injection and output tests**

| Test class | Cases |
|---|---|
| `GeneratorScaleTest` | Row counts follow the profile formulas (tiny/starter). Every SKU has its days. Promotions = N. |
| `DerivedValuesTest` | Inventory flow, order totals, landed cost, shelf price and floors all reconcile in generated data. |
| `GeneratorDeterminismIT` | Starter generated twice gives identical checksums. A different seed gives a different fingerprint. |
| `AnomalyInjectionTest` | Exactly N per type. Every GT entity_id exists in the CSVs. `anomaliesPerType=0` gives 0 business-rule findings. |
| `NoLeakageTest` | No feature CSV header contains a label column. `detection`/`validation` sources do not import `generator.anomaly` or reference `labels/`. GT IDs do not appear in any non-label file apart from as natural entity IDs. |
| `CsvRoundTripTest` | Write → read gives equal records. Nulls, decimals and timestamps preserved. LF endings. |

**Integration tests (H2)**

| Test class | Cases |
|---|---|
| `FlywayMigrationIT` | V1–V5 apply on H2. All tables, FKs and checks exist. |
| `BulkLoaderIT` | Load tiny/starter. DB counts = manifest counts. Reload is idempotent (truncate first). |
| `ValidationRunnerIT` | Clean dataset: all structural PASS. Each `fixtures/broken/*.csv` triggers exactly its check (dup PK, orphan FK, bad enum, reversed dates, illegal transition, broken reconciliation, duplicate natural key). |
| `EndToEndPipelineIT` | Generate starter → load → validate → detect → evaluate. Recall = 1.0 and precision = 1.0 per type. Structural failures = 0. |
| `EngineReplayIT` | For 100 sampled stored decisions, rebuild the context from the DB, re-run the engine, and get the identical final price and reasons (except injected ones). |
| `PricingControllerIT` (MockMvc) | Simulate: valid request returns 200 with the structure. Unknown SKU → 404 ProblemDetail. Bad date → 400. Overrides respected. |
| `DecisionControllerIT` | Explain returns the inputs, steps, observations with usage, promotions with status, and the PRC range. 404 path. |
| `ExceptionControllerIT` | Filters (type/category/sku/date range). Paging. Stable sort. |

---

## 13. REST API endpoints

The base path is `/api/v1`. OpenAPI is at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`. Errors are RFC 7807 `ProblemDetail`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/pricing/simulate` | Run the engine for a SKU on a date using DB inputs with optional what-if overrides. **Read-only by default.** Nothing is persisted, and the generated dataset stays reproducible. |
| GET | `/decisions/{decisionId}` | The stored decision record |
| GET | `/decisions/{decisionId}/explain` | Full traceability chain: inputs, cost record, observations (USED/EXCLUDED + age), promotions (APPLIED/REJECTED + reason), explanation steps, the PRC rows it produced, status events, and detected exceptions linked to it (from the detector, never labels) |
| GET | `/exceptions` | Query params `category=ANOMALY\|OPERATIONAL`, `issueType`, `skuId`, `from`, `to`, `page`, `size` (≤ 500). Sorted by event_date then id. |
| GET | `/products/{skuId}/price-history?from&to` | Daily list and shelf price with the source decision and reason |
| GET | `/reports/validation-summary` | Latest validation summary |
| GET | `/reports/evaluation` | Latest evaluation metrics |

**Simulate request:**
```json
{
  "skuId": "SKU-000873",
  "asOfDate": "2026-03-02",
  "overrides": {
    "landedCost": 812.00,
    "onHandUnits": 0,
    "competitorPrices": [{"competitor": "COMP_ALPHA", "price": 1199.00, "capturedAt": "2026-03-01T10:00:00Z"}],
    "promotionIds": ["PROMO-2026-000412"]
  }
}
```
**Response:** `candidateListPrice`, `expectedNetPrice`, `floorPrice`, `ceilingPrice`, `marginPct`, `requiredMarginPct`, `decisionType`, `status`, `primaryReasonCode`, `reasonCodes[]`, `observations[]`, `promotions[]`, `explanation[]`, `ruleVersion`.

---

## 14. README contents

1. **Title and one-paragraph summary.** Include a badge-free status line: "Tests: N passing. Full-scale validation: 0 structural failures."
2. **What this does.** The problem, the actors and a pipeline diagram (Mermaid): generate → load → validate → detect → evaluate → export → API.
3. **Prerequisites:** JDK 21, Docker + Docker Compose v2, bash, ~3 GB free disk for the full scale. Maven is not needed (wrapper included).
4. **Quick start (5 commands, starter scale):** copied from §17.
5. **Configuration:**
   - `.env` variables.
   - `application.yml` keys (`generator.*`, `pricing.policy.*`).
   - CLI flags table (`--scale --seed --skus --days --promos --anomalies-per-type --out --formats`).
   - How to override a policy threshold.
6. **Full-scale run.** The command, expected runtime and expected row counts (linked to `docs/evidence/manifest_full.json`).
7. **Verify it works (reviewer checklist):**
   - `./mvnw verify` (expected test count).
   - `sha256sum -c data/sample/checksums.sha256`.
   - `scripts/repro_check.sh starter` → IDENTICAL.
   - Open `validation_summary.md`: structural 0 failures, business findings = injected.
   - `evaluation_metrics.json`: per-type recall and precision.
   - Swagger: simulate, then explain a decision ID from `data/sample`.
   - The 3 curl examples.
   - SQL spot checks: 3 queries for "every price explainable", "no price below floor except labelled" and "inventory reconciles".
8. **Data model.** Link to the ER diagram, data dictionary and ID conventions, plus a short table of the 9 entities.
9. **Pricing rules summary.** Steps S1–S12 in brief, with a link to `docs/pricing_rules.md`.
10. **Edge cases and ground truth.** The 5 types, where the labels live and how leakage is prevented.
11. **Design decisions and trade-offs.** Link to `docs/design_decisions.md` with the top 5 inline.
12. **Assumptions and limitations** (§2).
13. **Testing approach.** Pyramid, golden scenarios, negative fixtures, determinism.
14. **Project structure** (abridged tree).
15. **Provenance and lawful use.** Fully synthetic, fictional names, no external data, no PII.
16. **AI usage.** Links to `AI_USAGE.md` and `PROMPT_HISTORY.md`.
17. **Troubleshooting:**
    - Port 5432 busy: change `POSTGRES_PORT`.
    - Docker not running.
    - `JAVA_HOME` not 21.
    - Out of memory on full scale: use `-Xmx2g`.
    - Windows line endings: `.gitattributes`.

---

## 15. PROMPT_HISTORY.md and AI_USAGE.md

**PROMPT_HISTORY.md** is a chronological record, required by the guidelines.
- **Source:** Claude Code session transcripts (`~/.claude/projects/-home-ginger-my-projects-YottaFlex/*.jsonl`). `scripts/extract_prompt_history.sh` uses `jq` to pull user prompts and timestamps into a draft, which I then edit by hand. Prompts from any other tool (for example a browser chat) are added manually with their timestamps.
- **Entry format:**
  ```
  ### #07 — 2026-11-14 18:40 UTC — Claude Code (Opus 5.5) — Phase P2 Pricing engine
  **Prompt (verbatim):** …
  **What AI produced:** 1–2 lines
  **What I did with it:** accepted / modified (what and why) / rejected (why)
  **Verification:** test(s) or check that proved it
  ```
- Entry #01 is this planning prompt, and PLAN.md is its output.
- **Redaction:** remove secrets, tokens and personal contact details. Keep the prompts otherwise verbatim and note any redactions.
- Update it **at every commit boundary**, not at the end.

**AI_USAGE.md** structure:
1. **Tools used:** Claude Code (model and version) and any others.
2. **Summary table** with columns Area | AI contribution | Reliance (Heavy/Medium/Light) | What I reviewed, changed or validated. Example areas: planning, Flyway schema, generator, pricing engine, stack resolver, anomaly injector, validator SQL, tests, README.
3. **Where AI accelerated the work:** scaffolding, boilerplate records and DTOs, SQL check generation, doc drafting.
4. **Where I relied heavily on it,** honestly.
5. **What I personally decided or overrode,** with concrete examples to fill in as they happen. Candidates:
   - Choosing net-basis margin.
   - Capture-time freshness instead of ingestion time.
   - Per-stream RNG for reproducibility.
   - Rejecting AI-suggested `Math.random`/`HashMap` iteration.
   - Correcting off-by-one at the 72-hour boundary.
6. **AI mistakes caught,** with how they were caught (which test or check).
7. **Verification methods independent of AI:** tests, reproducibility hashes, manual SQL spot checks on Postgres, hand-computed golden scenarios.
8. **Statement:** I understand and can walk through every component.

**Commit trailers:** commits made with AI assistance keep their `Co-Authored-By` trailer. That is consistent with transparent disclosure.

---

## 16. Git commit strategy

- **Do not backdate** or rewrite commit timestamps. Every commit must fall inside the 72-hour window. Push after every commit, or at least hourly, so GitHub server timestamps corroborate the history.
- Before the first commit, check that `git config user.name/user.email` is the identity you want shown publicly.
- Conventional commit messages. Each commit builds and passes its tests, and is small enough to review.
- Create the **public** GitHub repo at the start, so the push history is visible from the first commit.

| # | Commit | Contents |
|---|---|---|
| 1 | `chore: initialise repo with challenge brief and build plan` | docs/challenge_brief.md, PLAN.md, .gitignore, .gitattributes, .editorconfig, LICENSE, PROMPT_HISTORY.md stub |
| 2 | `build: Spring Boot 3 skeleton, Maven wrapper, Docker Compose Postgres` | pom, mvnw, app class, application*.yml, docker-compose, .env.example |
| 3 | `docs: data dictionary, ER diagram and ID conventions` | docs/* |
| 4 | `feat(db): Flyway schema for core, bridge, event and output tables` | V1–V5 + FlywayMigrationIT |
| 5 | `feat(domain): records, enums and IdFactory` | + IdFactoryTest |
| 6 | `feat(pricing): guardrails, competitor freshness, net margin and stack resolver` | + unit tests |
| 7 | `feat(pricing): pricing engine with explanation steps and golden scenarios` | |
| 8 | `feat(generator): seeded RNG streams and master data generators` | |
| 9 | `feat(generator): market day-loop simulator for decisions, prices, orders and inventory` | + DerivedValuesTest |
| 10 | `feat(io): deterministic CSV writer, manifest, checksums and generate command` | |
| 11 | `feat(anomaly): labelled edge-case injection with separate ground truth` | + leakage tests |
| 12 | `feat(persistence): JdbcTemplate batch loader and load command` | + BulkLoaderIT |
| 13 | `feat(validation): PK/FK/enum/date/reconciliation/lifecycle/duplicate checks and summary` | + fixtures |
| 14 | `feat(detection): exception detector and ground-truth evaluation metrics` | + EndToEndPipelineIT |
| 15 | `feat(api): simulate, explain and exceptions endpoints with OpenAPI` | + MockMvc ITs |
| 16 | `feat(export): JSON/SQL export and reproducibility check` | scripts |
| 17 | `data: add starter sample dataset and full-scale evidence` | data/sample, docs/evidence |
| 18 | `docs: README, AI usage and prompt history` | |
| 19 | `fix: issues found in fresh-clone test` | if any |
| 20 | Tag `v1.0.0`, which marks the submitted state | |

---

## 17. Step-by-step run instructions

These are the commands the README will show and that I will run for the evidence.

```bash
# 0. Clone and configure
git clone https://github.com/<your-username>/pricing-promo-simulator.git
cd pricing-promo-simulator
cp .env.example .env                      # edit POSTGRES_PORT if 5432 is busy

# 1. Start PostgreSQL
docker compose up -d
docker compose ps                         # wait for "healthy"

# 2. Build and test (unit + H2 integration)
./mvnw clean verify                       # expect: BUILD SUCCESS, all tests green

# 3. Generate starter dataset (no DB needed)
java -jar target/pricing-sim.jar generate --scale starter --seed 20261022 --out out/starter

# 4. Load into Postgres (Flyway migrates on startup)
java -jar target/pricing-sim.jar load --in out/starter

# 5. Validate, detect exceptions, evaluate against ground truth
java -jar target/pricing-sim.jar validate --in out/starter     # writes validation_summary.json/.md
java -jar target/pricing-sim.jar detect   --in out/starter     # writes detected_exceptions.csv + DB table
java -jar target/pricing-sim.jar evaluate --in out/starter     # writes evaluation_metrics.json

# 6. Export JSON/SQL
java -jar target/pricing-sim.jar export --in out/starter --formats json,sql

# 7. Prove reproducibility
./scripts/repro_check.sh starter                               # expect: IDENTICAL
sha256sum -c data/sample/checksums.sha256                      # committed sample still verifies

# 8. Full scale (5,000 SKUs × 180 days × 2,000 promotions)
java -Xmx2g -jar target/pricing-sim.jar run-all --scale full --out out/full

# 9. API
java -jar target/pricing-sim.jar                               # no args → web server on :8080
open http://localhost:8080/swagger-ui.html
./scripts/api_smoke.sh                                         # simulate, explain, exceptions

# One-shot alternative for steps 1–7
./scripts/run_all.sh starter
```

---

## 18. Final submission checklist

**Functional**
- [ ] `./mvnw clean verify` passes from a clean checkout, and the test count is recorded in `docs/evidence/test_report.txt`.
- [ ] Starter and full runs complete. The full manifest is committed in `docs/evidence/`.
- [ ] Validation: 0 structural failures. Business-rule findings = 5 × anomaliesPerType.
- [ ] Evaluation: precision and recall recorded per type. Realism bands are mostly PASS, and any WARN is explained.
- [ ] `repro_check.sh` gives IDENTICAL, and the report is committed.
- [ ] Swagger UI loads. simulate, explain and exceptions return real data, captured in `api_samples.md`.

**Repository and documentation**
- [ ] README covers install, configure, run, verify, assumptions, trade-offs, limitations and the testing approach.
- [ ] `docs/` is complete: data dictionary, ER, ID conventions, pricing rules, anomalies, validation, API, provenance.
- [ ] PROMPT_HISTORY.md is complete through the last prompt. AI_USAGE.md is honest and specific.
- [ ] `data/sample/` is committed and `checksums.sha256` verifies. `out/` is git-ignored.
- [ ] No secrets in the repo (`.env` ignored; only `.env.example` committed). No file over 50 MB.
- [ ] Every commit is inside the 72-hour window. The history is meaningful and pushed. Tag `v1.0.0`.

**Fresh-clone test** (≥ 1 h before submitting)
- [ ] `scripts/fresh_clone_test.sh https://github.com/<user>/pricing-promo-simulator.git` runs in a new temp directory under a different Compose project name. Steps 1–7 pass, and the regenerated starter checksums equal the committed `data/sample/checksums.sha256`.
- [ ] Open the repo URL in a private/incognito browser window to confirm it is **public**.

**Submit**
- [ ] Final push done. `git status` is clean. The default branch is `main`.
- [ ] Paste the repository URL (`https://github.com/<user>/pricing-promo-simulator`) into the YottaFlex portal field **once**. Submissions are final.
- [ ] Submit **before Sun 15-11-2026 12:02 UTC**, with a target of 10:00 UTC at the latest.
- [ ] Do not push after submitting, so the reviewed state equals the submitted state. If a push is unavoidable, the `v1.0.0` tag still marks the submission.
- [ ] Walkthrough prep: a 5-minute demo script.
  1. Problem and the ER model.
  2. Run the starter pipeline live.
  3. Pick one `price_history` row, then explain it through the decision chain.
  4. Show an injected anomaly and its detection.
  5. Show the reproducibility hashes.
  6. Walk through AI_USAGE: where I overrode the AI.
