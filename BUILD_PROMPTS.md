# BUILD_PROMPTS.md

Five prompts to paste into Claude Code one at a time, in order. Each prompt builds on the commits from the one before it.

---

## Prompt 1 — Project setup, Docker Compose, Flyway schema, domain entities, data dictionary

```text
Read PLAN.md (§2–§6, §15, §16). Implement phases P0–P1. Follow PLAN.md exactly and do not change scope.

Deliverables:
1. Move "Associate AI Forward Deployed Engineers .md" to docs/challenge_brief.md.
2. Root files per PLAN §3: .gitignore, .gitattributes (LF for csv/sql/json/sh), .editorconfig, LICENSE (MIT),
   .env.example, docker-compose.yml (postgres:16-alpine, healthcheck, named volume, port from .env),
   pom.xml (Java 21, Spring Boot 3.5.x, all deps in PLAN §3, finalName pricing-sim), Maven wrapper.
3. PricingSimApplication.java, application.yml (datasource from env with defaults matching compose),
   application-offline.yml (excludes DataSource/Flyway/JDBC auto-config), src/test/resources/application-test.yml (H2, MODE=PostgreSQL).
4. Flyway V1–V5 per PLAN §4–§5: 9 entity tables, 4 bridge tables, 3 event tables, 3 output tables.
   Include PKs, FKs, NOT NULL on structural fields and VARCHAR+CHECK enums. Do NOT add business-rule constraints (PLAN §2).
   The migrations must run on both Postgres and H2.
5. domain/model (16 records), domain/enums (every enum and value in PLAN §5), domain/id (IdFactory, IdPatterns, SequenceCounter per PLAN §6).
6. io/TableSpec + io/TableRegistry: 17 data tables with column order, PK, FKs, enum columns, ID regex and FK-safe load order.
7. docs/data_dictionary.md (every field in PLAN §5), docs/er_diagram.md (Mermaid erDiagram + cardinality table), docs/id_conventions.md.
8. PROMPT_HISTORY.md in the PLAN §15 format. Backfill #01 (the prompt that created PLAN.md) and #02 (the prompt that created BUILD_PROMPTS.md) from this session.
9. Tests: IdFactoryTest, FlywayMigrationIT (H2), TableRegistryTest (registry columns == migrated schema columns).

Done when:
- ./mvnw clean verify → BUILD SUCCESS, all tests green.
- docker compose up -d → postgres healthy; java -jar target/pricing-sim.jar starts, Flyway applies V1–V5 to Postgres,
  and `docker compose exec postgres psql ... -c '\dt'` lists all 19 tables.

Finally: append this prompt verbatim to PROMPT_HISTORY.md as #03, then commit in the PLAN §16 steps 1–5 as separate commits with meaningful messages.
If `gh` is authenticated, create the PUBLIC repo "pricing-promo-simulator" and push. If it is not, stop and give me the exact commands.
```

---

## Prompt 2 — Seeded master data generators (Product, Cost, Price, Competitor, Inventory) and CLI params

```text
Read PLAN.md (§2, §6, §7, §11 CSV format rules). Implement phase P3, first half. Follow PLAN.md exactly.

Deliverables:
1. generator/rng: RandomStreams (SHA-256(seed|stream|key) → java.util.Random) and Distributions (StrictMath only).
   No Instant.now(), no HashMap iteration and no Math.random anywhere in the generator.
2. config: GeneratorProperties and ScaleProfile with the tiny/starter/full profiles from PLAN §7.1 in application.yml. Default seed 20261022.
3. cli (picocli): PricingSimCommand, PicocliRunner and ScaleOptions
   (--scale --seed --skus --days --promos --start-date --anomalies-per-type --out).
   Add GenerateCommand, which runs with the offline profile and needs no DB. Flags not used yet must be accepted and recorded.
4. generator/master: ProductGenerator (+ product_status_event), CostRecordGenerator and CompetitorObservationGenerator,
   per PLAN §7.2 steps 1, 2 and 4, including stale gap episodes, late feeds, low-confidence observations and supplier cost shocks.
5. generator/sim: MarketSimulator day loop, SkuState, DemandModel and ReplenishmentPolicy.
   It writes inventory_snapshot (every derived field in PLAN §5.5) and price_history.
   Pricing goes behind a `PriceSetter` interface. For now implement a temporary InitialPriceSetter
   (INITIAL price held constant, rounded per PLAN §8 S9). Prompt 3 replaces it.
   Keep units_sold as a daily total for now; Prompt 3 splits it into orders.
6. generator/sink: RowSink, CsvRowSink, InMemoryRowSink. io/CsvSupport per the PLAN §11 CSV rules.
7. Tests: RandomStreamsTest, DistributionsTest, GeneratorScaleTest (tiny; row counts follow the profile),
   DerivedValuesTest (landed cost, cost periods with no gap or overlap, inventory flow equation, opening = previous closing),
   GeneratorDeterminismTest (tiny generated twice → byte-identical files; a different seed → different files).

Done when:
- ./mvnw clean verify → all tests green.
- With Docker STOPPED, `java -jar target/pricing-sim.jar generate --scale starter --out out/starter` writes
  product, product_status_event, cost_record, competitor_observation, price_history and inventory_snapshot CSVs
  with 200 SKUs, 60 days and 3 cost months.
- Generating again into out/starter2 and running `diff -r out/starter out/starter2` gives no output.

Finally: append this prompt verbatim to PROMPT_HISTORY.md, then commit (PLAN §16 step 8, plus a separate commit for the simulator skeleton) with meaningful messages and push.
```

---

## Prompt 3 — Promotions, rules, orders, pricing engine, and PriceDecision with stored inputs and reasons

```text
Read PLAN.md (§2, §4.3, §5.6–§5.10, §7.2 steps 3 and 5, §8). Implement phase P2 and the second half of P3. Follow PLAN.md exactly.

Deliverables:
1. generator/master/PromotionGenerator: promotion, promotion_rule, promotion_product and promotion_status_event, per PLAN §7.2 step 3.
   This covers markdowns aligned to Mon→Sun, coupons, exclusive and excluded pairs (conflicting coupons), cancellations, and ID ordering per PLAN §6.
2. pricing package, pure Java with no Spring or DB: PricingPolicy (+ PricingPolicyProperties), GuardrailCalculator, MarginCalculator
   (net basis only), CompetitorReferenceResolver (72h on source_captured_at), DemandSignalCalculator, PromotionStackResolver
   (total order: priority, then promotion_id), PricePointRounder, PricingEngine, PricingContext, PricingResult and ExplanationStep.
   Implement steps S1–S12 and every ReasonCode from PLAN §8.
3. Replace InitialPriceSetter. MarketSimulator triggers decisions per PLAN §2 assumption 7 and builds PricingContext with no look-ahead.
   It writes price_decision (every stored-input field in PLAN §5.9, plus reason_codes, explanation JSON and rule_version),
   price_decision_observation (USED/EXCLUDED_* with age_hours), price_decision_promotion (APPLIED/REJECTED with reason)
   and price_decision_status_event (incl. ESCALATED and SUPERSEDED).
   price_history gets source_decision_id, change_reason and shelf_price.
4. generator/sim/OrderGenerator: splits units sold into customer_order rows. Coupons go through PromotionStackResolver and are written
   to order_promotion as APPLIED or REJECTED with a reason. 2% of orders are CANCELLED. All order amounts are derived per PLAN §5.8.
5. docs/pricing_rules.md (S1–S12 with one worked numeric example) and docs/generator_design.md.
6. Tests: GuardrailCalculatorTest, MarginCalculatorTest (incl. the margin-before-discount regression), CompetitorReferenceResolverTest
   (72h boundary), PromotionStackResolverTest (incl. shuffled-input invariance), PricePointRounderTest, PricingEngineTest,
   GoldenScenariosTest (20 hand-computed cases in src/test/resources/golden/pricing_scenarios.json),
   and an extended DerivedValuesTest. The extended test checks that order totals reconcile, units_sold = Σ FULFILLED qty,
   every price_history row matches its decision's final_list_price, and the clean run has no price below floor or above MRP.

Done when:
- ./mvnw clean verify → all tests green.
- `generate --scale starter --out out/starter` writes all 16 data tables, and every price_history row has a source_decision_id.
- The starter run includes non-zero counts of: COMPETITOR_DATA_STALE decisions, GUARDRAIL_CONFLICT_ESCALATED decisions,
  stockout days and REJECTED coupons. Print these counts at the end of generate.
- Generating twice still gives byte-identical output.

Finally: append this prompt verbatim to PROMPT_HISTORY.md, then commit (PLAN §16 steps 6, 7 and 9) with meaningful messages and push.
```

---

## Prompt 4 — Anomaly injector, ground truth, validator, validation_summary, CSV/JSON/SQL export, checksums, reproducibility script

```text
Read PLAN.md (§9, §10, §11, §12). Implement phases P4–P6. Follow PLAN.md exactly.

Deliverables:
1. generator/anomaly: AnomalyPlanner, AnomalyPlan, AnomalyInjector and GroundTruthRecord.
   Inject the 5 issue types exactly as specified in the PLAN §9 table, through simulator hooks, keeping the arithmetic consistent.
   Write labels/ground_truth.csv and labels/ground_truth.json with the PLAN §9 columns.
   --anomalies-per-type 0 must produce a clean dataset.
2. io: ChecksumService, ManifestWriter (manifest.json + checksums.sha256 per PLAN §11), CsvDatasetReader, JsonExporter (JSONL above 100k rows)
   and SqlExporter (schema.sql + seed.sql).
3. persistence/BulkLoader + LoadCommand: JdbcTemplate batchUpdate with batch size 5000 and reWriteBatchedInserts=true,
   TRUNCATE first, TableRegistry order. It must never load labels/.
4. validation: every category in PLAN §10, generated from TableRegistry where possible.
   Add ValidationSummaryWriter (json + md) and ValidateCommand, which exits non-zero on any structural FAIL.
5. detection: ExceptionDetector, the 5 rule detectors and OperationalExceptionCollector, plus DetectCommand.
   The detection code must not reference generator.anomaly or labels/.
6. evaluation: GroundTruthEvaluator, DatasetMetricsCalculator (PLAN §7.5 bands) and EvaluateCommand.
7. ExportCommand, ReproCheckCommand and RunAllCommand.
   Scripts: scripts/run_all.sh (docker up → generate → load → validate → detect → evaluate → export) and scripts/repro_check.sh
   (same seed twice → IDENTICAL, different seed → DIFFERENT; writes docs/evidence/reproducibility_report.md).
8. docs/anomalies_and_ground_truth.md, docs/validation.md and docs/provenance.md.
9. Tests: AnomalyInjectionTest, NoLeakageTest, CsvRoundTripTest, BulkLoaderIT,
   ValidationRunnerIT (src/test/resources/fixtures/broken/* datasets that each break one rule), EndToEndPipelineIT and GeneratorDeterminismIT.

Done when:
- ./mvnw clean verify → all tests green.
- `./scripts/run_all.sh starter` exits 0. validation_summary.md shows 0 structural failures and 25 business-rule findings (5 per type).
- evaluation_metrics.json shows precision = recall = 1.0 for every issue type.
- `./scripts/repro_check.sh starter` prints IDENTICAL, and the different-seed check prints DIFFERENT.
- out/starter is copied to data/sample/, and `cd data/sample && sha256sum -c checksums.sha256` passes.

Finally: append this prompt verbatim to PROMPT_HISTORY.md, then commit (PLAN §16 steps 10–14, 16, and the data/sample part of 17) with meaningful messages and push.
```

---

## Prompt 5 — Tests, REST API, README, AI_USAGE.md, final 5,000-SKU run, fresh-clone check, submission checklist

```text
Read PLAN.md (§12, §13, §14, §15, §17, §18). Implement phases P7–P9. Follow PLAN.md exactly. The deadline is 15-11-2026 12:02 UTC.

Deliverables:
1. REST API per PLAN §13: PricingController, DecisionController, ExceptionController, ProductController and ReportController.
   Add the services, persistence DAOs, DTOs, ApiExceptionHandler (ProblemDetail) and OpenApiConfig. simulate is read-only.
2. Tests: PricingControllerIT, DecisionControllerIT, ExceptionControllerIT (MockMvc, H2) and EngineReplayIT.
   Close any gap against the PLAN §12 test list. Save the `./mvnw clean verify` summary to docs/evidence/test_report.txt.
3. Full run: `java -Xmx2g -jar target/pricing-sim.jar run-all --scale full --out out/full` (5,000 SKUs, 180 days, 2,000 promotions).
   Record the runtimes. Copy manifest_full.json, validation_summary_full.md and evaluation_metrics_full.json to docs/evidence/.
   Fix or explicitly explain any realism-band WARN. Run `./scripts/repro_check.sh full` if time allows; otherwise use starter and say so.
4. scripts/api_smoke.sh, which saves real responses to docs/evidence/api_samples.md. Also docs/api.md and docs/design_decisions.md.
5. README.md per PLAN §14, using the real numbers from docs/evidence.
6. AI_USAGE.md per PLAN §15. Draft it from PROMPT_HISTORY.md and git log. Mark every statement about what I personally reviewed,
   changed or validated with "TODO(me): confirm" so I can verify it. Do not invent claims on my behalf.
7. scripts/extract_prompt_history.sh. Make PROMPT_HISTORY.md complete up to this prompt.
8. Push, then run `./scripts/fresh_clone_test.sh <public repo URL>`.
   Fix anything it finds as `fix:` commits and re-run until it passes. Then tag v1.0.0 and push the tag.

Done when:
- The fresh-clone test passes and the regenerated starter checksums equal data/sample/checksums.sha256.
- `git status` is clean, and every commit timestamp is inside the 72-hour window.
- You print the PLAN §18 checklist with ✅/❌ and the evidence (file or command) for each item, plus the repo URL.

Do NOT submit on the YottaFlex portal. I will submit the URL myself, once.

Finally: append this prompt verbatim to PROMPT_HISTORY.md, then commit (PLAN §16 steps 15, 17, 18 and the tag) with meaningful messages and push.
```
