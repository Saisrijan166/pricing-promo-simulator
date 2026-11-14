# Design decisions

Each ADR follows **context → decision → consequence**. ADR-01 to ADR-10 are the trade-offs in PLAN.md §2.
ADR-11 to ADR-16 are decisions made during implementation; PROMPT_HISTORY.md has the detail.

## ADR-01 Enum storage: VARCHAR + CHECK, not PostgreSQL ENUM types

- **Context.** The same Flyway scripts must run on PostgreSQL (Docker) and H2 (tests), and enum domains must be visible
  to the validator.
- **Decision.** Every enum column is `VARCHAR` with a named `CHECK (col IN (…))` constraint, `ck_<table>_<column>`.
  `TableRegistry` lists the same domains.
- **Consequence.** One schema runs on both databases (`FlywayMigrationIT`), and `TableRegistryTest` keeps code and DDL
  identical. Adding a value means changing a migration, not altering a type. Storage is slightly larger than a native
  enum, which does not matter at this scale.

## ADR-02 Business rules are enforced by the validator and detector, not by DB constraints

- **Context.** The dataset deliberately contains labelled anomalies, such as a price below the floor or a stale
  observation used as current. A `CHECK (list_price >= floor)` would make them impossible to load and so impossible
  to detect.
- **Decision.** The database enforces structure only: PK, FK, enum domains and NOT NULL on structural fields. Business
  rules live in `validation` (183 checks) and in `detection.rules`.
- **Consequence.** Anomalies load and are found by rule (precision = recall = 1.0). Natural-key uniqueness is a
  DUPLICATE check rather than a unique index. Anyone loading the data elsewhere must run `validate`.

## ADR-03 Bulk load with JdbcTemplate.batchUpdate, not COPY

- **Context.** The stack is fixed (JdbcTemplate), and the full dataset is about 4 million rows.
- **Decision.** `BulkLoader` streams each CSV in TableRegistry order through `batchUpdate` in batches of 5,000, with
  `reWriteBatchedInserts=true`, in one transaction. It TRUNCATEs first, so a reload is idempotent.
- **Consequence.** The load is portable (H2 and PostgreSQL), transactional and memory-flat. It is slower than `COPY`;
  the full-scale timing is in the README. `COPY` would be the next step if load time mattered.

## ADR-04 CSV is the canonical output; JSON and SQL are produced by `export`

- **Context.** The full dataset is hundreds of MB of CSV. JSON for everything multiplies size and time for little
  gain.
- **Decision.** `generate` writes CSV with fixed rules (RFC 4180, UTF-8, LF, PK order, plain decimals, ISO-8601 UTC).
  The checksums and fingerprint cover the CSV and the labels. `export` writes JSON (JSON Lines above 100k rows) and SQL
  on demand.
- **Consequence.** The reproducibility proof is byte-level on one format. JSON and SQL are derived and verified by
  `ExportIT`. The full run exports JSON only; SQL is committed for the starter dataset.

## ADR-05 Commit only the starter dataset, plus full-scale evidence

- **Context.** GitHub limits files to 100 MB, and the full dataset is reproducible from the seed.
- **Decision.** `data/sample/` holds the complete starter output (csv, json, sql, labels, manifest, checksums,
  summaries). `docs/evidence/` holds the full-scale manifest, validation summary and metrics.
- **Consequence.** A reviewer can inspect the data without running anything, and verify it with
  `sha256sum -c checksums.sha256`. The full data must be regenerated (`run-all --scale full`), and the manifest lets
  anyone check the result.

## ADR-06 Single-threaded day loop with per-SKU random streams

- **Context.** Global sequence IDs (DEC, ORD, OBS, DEV) need a total order. Parallel simulation makes byte-identical
  output hard to guarantee.
- **Decision.** One thread visits days in order and SKUs in ID order. Randomness comes from independent streams
  `SHA-256(seed|stream|key)` per concern and SKU.
- **Consequence.** Output is byte-identical across runs and JVMs (`repro_check.sh`, `GeneratorDeterminismIT`).
  Changing one SKU or adding anomalies never reshuffles another SKU's draws. The full scale still generates in about
  1.5 minutes, so parallelism was not needed.

## ADR-07 Rule-based detector that mirrors the engine's guardrails

- **Context.** The labels are rule-defined, and the brief asks for separate ground truth and a way to measure
  detection.
- **Decision.** `detection.rules` re-derives each business rule from stored inputs with the engine's own calculators
  (floor, net margin, stacking rules, freshness on capture time, price-change reason). The detector never reads labels
  or the anomaly code (`NoLeakageTest`).
- **Consequence.** Precision and recall of 1.0 are expected. They prove the labels are exact and the clean baseline
  has no unlabelled violations; they do not show detector "intelligence". The README says so.

## ADR-08 One Maven module

- **Context.** A 72-hour window, and one deployable artefact is enough.
- **Decision.** One module with clear packages: `domain`, `pricing` (pure Java), `generator`, `io`, `persistence`,
  `validation`, `detection`, `evaluation`, `service`, `api`, `cli`. The `pricing` and `generator` packages have no
  Spring or DB dependencies.
- **Consequence.** The build is simpler and faster. Package boundaries are kept by convention and by tests (source
  scans for determinism and leakage), not by the module system.

## ADR-09 Picocli CLI inside the Spring Boot jar

- **Context.** Reviewers need one artefact that generates, loads, validates and serves the API. Generating must work
  without a database.
- **Decision.** `pricing-sim.jar` with no arguments starts the web server; with a command word it runs picocli. The
  `generate`, `export` and `repro-check` commands activate an `offline` profile that excludes DataSource, JDBC and
  Flyway. API beans are `@Profile("!offline")`.
- **Consequence.** Every command is `java -jar target/pricing-sim.jar <command>`, and DB-free commands start with
  PostgreSQL down. There are some Spring start-up costs (about 3 s) per command.

## ADR-10 java.util.Random and StrictMath

- **Context.** `SplittableRandom` and `Math` are not guaranteed bit-for-bit identical across JVMs and platforms.
- **Decision.** Only `java.util.Random`, whose algorithm is fixed by the Java specification, and `StrictMath`. Money
  converts through the exact binary value (`new BigDecimal(double)`), never `Double.toString`.
  `GeneratorSourceRulesTest` fails the build on `Math.`, `Math.random`, `Instant.now()` or hash-ordered collections in
  generator or pricing code.
- **Consequence.** Reproducibility is portable, and `RandomStreamsTest` pins known values. `java.util.Random` is
  slower than newer generators, which is irrelevant here.

## ADR-11 Guardrail conflict prices at the floor (above MRP) and escalates

- **Context.** PLAN §8 S3 says price = floor when floor > MRP. PLAN §2 calls MRP a legal hard ceiling in India.
- **Decision.** Follow S3: the price is the floor, the decision is ESCALATED, and an OPS_MANAGER approves it. The
  validator's business rule is "not below floor", and the conflict is an OPERATIONAL exception.
- **Consequence.** These are the only list prices above MRP in a clean dataset (1 SKU-day in starter, 743 decisions at
  full scale). In production this would be a blocking alert. The alternative, capping at MRP and accepting a
  sub-minimum margin, is a one-line change in `GuardrailCalculator.clamp`.

## ADR-12 Precise definitions where the plan was loose

- **Decision.**
  - A decision's `on_hand_units` = opening + receipts of the day.
  - Velocity and days of cover use the days that have history (n7, n28).
  - Demand adjustments need 7 days of history.
  - `decision_type` is HOLD for INITIAL.
  - MIN_ORDER_QTY applies to coupons only.
  - Primary reasons follow two fixed precedence lists, one for changes and one for holds.
- **Consequence.** Every derived value is recomputable by the validator, and the data dictionary states each formula.

## ADR-13 Anomaly targets chosen from a baseline dry run, at most one per SKU

- **Context.** PLAN §9 asks for targets chosen up front among records eligible in the baseline. Eligibility (for
  example "a markdown was rejected for margin") is only known after simulating.
- **Decision.** A dry run (rows discarded) collects eligible records by natural key. `AnomalyPlanner` picks N per type
  from the `anomaly` streams on distinct SKUs. The real run injects through `SimulationHooks`.
- **Consequence.** Generation takes about twice as long when anomalies are requested. Every SKU without an anomaly is
  identical to the clean run (`AnomalyInjectionTest`), and every label exists and breaks exactly its rule.

## ADR-14 Defects are produced by the engine, through explicit Faults

- **Context.** Editing finished rows to fake a stale-data or margin-basis defect would leave derived values
  inconsistent.
- **Decision.** `PricingEngine.price(context, Faults)` can force a list price, treat a stale observation as fresh, or
  judge margin on the list price. Only the injector uses Faults; the generator baseline and the API use `Faults.NONE`.
- **Consequence.** Injected decisions are arithmetically consistent records with correct downstream effects. The
  engine carries a little test-only surface, and it is named for what it is.

## ADR-15 Business rules exist once, in `detection.rules`

- **Decision.** The validator's BR-001..005 and the detector run the same five rule classes.
- **Consequence.** The two cannot disagree, and validation depends on detection. Neither touches labels.

## ADR-16 Spill-to-disk CSV sorting

- **Context.** Rows must be written in PK order, but the day loop produces them in date order, and the full scale has
  about 4 million rows.
- **Decision.** `CsvRowSink` keeps formatted lines and spills sorted runs of 250,000 to temporary files, then merges.
- **Consequence.** Full scale generates with `-Xmx2g` (and with 768 MB); the output bytes are identical with or
  without spilling (`GeneratorDeterminismTest`).
