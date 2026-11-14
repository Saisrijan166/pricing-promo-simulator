# BUILD_PROMPTS.md

Build prompts for the Pricing & Promotion Simulator, run one at a time and in order. Each prompt is self-contained: it
relies only on `PLAN.md` and the repository state left by the previous prompt.

Rules common to every prompt:
- `PLAN.md` is the specification. Where the code and the plan disagree, fix the code. If the plan is wrong, update `PLAN.md` in the same commit and say why.
- Commit with the repository's configured git identity. Use meaningful Conventional Commit messages (PLAN §16).
- Append the prompt verbatim to `PROMPT_HISTORY.md` as the next entry, in the PLAN §15 format. Fill "What AI produced" and "Verification". Leave "What I did with it" as `TODO(me)` for the developer to complete.
- Never mark a step done unless its "Done when" checks have actually been run and passed. Report any failure with its output.

---

## Prompt 1 — Project setup, Docker Compose, Flyway schema, domain entities, data dictionary

```text
Read PLAN.md (§2–§6, §15, §16). Implement phases P0–P1. Follow PLAN.md exactly and do not change scope.

Deliverables:
1. Move the challenge brief to docs/challenge_brief.md.
2. Root files per PLAN §3: .gitignore, .gitattributes (LF for csv/sql/json/sh), .editorconfig, LICENSE (MIT),
   .env.example, docker-compose.yml (postgres:16-alpine, healthcheck, named volume, port from .env),
   pom.xml (Java 21, Spring Boot 3.5.x, all deps in PLAN §3, finalName pricing-sim), Maven wrapper.
3. PricingSimApplication.java, application.yml (datasource from env with defaults matching compose),
   application-offline.yml (excludes DataSource/Flyway/JDBC auto-config), src/test/resources/application-test.yml (H2, MODE=PostgreSQL).
4. Flyway V1–V5 per PLAN §4–§5: 9 entity tables, 4 bridge tables, 3 event tables, 3 output tables.
   Include PKs, FKs, NOT NULL on structural fields and VARCHAR+CHECK enums. Do NOT add business-rule constraints (PLAN §2).
   The migrations must run on both Postgres and H2.
5. domain/model (16 records), domain/enums (every enum and value in PLAN §5), domain/id (IdFactory, IdPatterns, SequenceCounter per PLAN §6).
6. io/TableSpec + io/TableRegistry: the 16 data tables with column order, PK, FKs, enum columns, ID regex and FK-safe load order.
7. docs/data_dictionary.md (every field in PLAN §5), docs/er_diagram.md (Mermaid erDiagram + cardinality table), docs/id_conventions.md.
8. PROMPT_HISTORY.md in the PLAN §15 format, recording the planning prompts that produced PLAN.md and BUILD_PROMPTS.md.
9. Tests: IdFactoryTest, FlywayMigrationIT (H2), TableRegistryTest (registry columns == migrated schema columns).

Done when:
- ./mvnw clean verify → BUILD SUCCESS, all tests green.
- docker compose up -d → postgres healthy; java -jar target/pricing-sim.jar starts, Flyway applies V1–V5 to Postgres,
  and `docker compose exec postgres psql ... -c '\dt'` lists all 19 tables.

Finally: append this prompt to PROMPT_HISTORY.md, then commit as PLAN §16 steps 1–5 (separate commits). Do not push yet.
```

---

## Prompt 2 — Seeded master data generators (Product, Cost, Competitor, Inventory, Price history) and CLI

```text
Read PLAN.md (§2, §6, §7, §11 CSV rules). Implement the first half of phase P3 on top of the existing domain, schema and TableRegistry.

Deliverables:
1. generator/rng: RandomStreams (java.util.Random seeded from SHA-256(seed|stream|key)) and Distributions (StrictMath only).
   The generator must not use Instant.now(), Math.random or unordered HashMap/HashSet iteration.
2. config: GeneratorProperties and ScaleProfile. Put the tiny/starter/full profiles from PLAN §7.1 in application.yml. Default seed 20261022.
3. cli (picocli): PricingSimCommand, PicocliRunner and the ScaleOptions mixin
   (--scale --seed --skus --days --promos --start-date --anomalies-per-type --out).
   GenerateCommand runs with the offline profile and needs no database. Flags used only by later steps are accepted and recorded.
4. generator/master: ProductGenerator (+ product_status_event), CostRecordGenerator and CompetitorObservationGenerator,
   exactly per PLAN §7.2 steps 1, 2 and 4. This includes in-window launches and discontinuations, supplier cost shocks,
   stale gap episodes, late feeds and low-confidence observations.
5. generator/sim: MarketSimulator (day loop), SkuState, DemandModel and ReplenishmentPolicy.
   It writes inventory_snapshot (every derived field in PLAN §5.5) and price_history.
   Pricing goes behind a PriceSetter interface. Implement a temporary InitialPriceSetter
   (initial price held constant, rounded per PLAN §8 S9) that the next step replaces.
   units_sold is a daily total for now; the next step splits it into orders.
6. generator/sink: RowSink, CsvRowSink, InMemoryRowSink. io/CsvSupport implements the PLAN §11 CSV rules
   (RFC 4180, UTF-8, LF, header, empty = null, plain BigDecimal, ISO-8601 UTC, rows sorted by PK).
7. Tests:
   - RandomStreamsTest and DistributionsTest.
   - GeneratorScaleTest: row counts follow the tiny profile.
   - DerivedValuesTest: landed cost, cost periods without gaps or overlaps, inventory flow equation, opening = previous closing.
   - GeneratorDeterminismTest: same seed twice gives byte-identical files; a different seed gives different files.

Done when:
- ./mvnw clean verify → all tests green.
- With Docker stopped, `java -jar target/pricing-sim.jar generate --scale starter --out out/starter` writes the
  product, product_status_event, cost_record, competitor_observation, price_history and inventory_snapshot CSVs
  for 200 SKUs, 60 days and 3 cost months.
- Generating into out/starter2 and running `diff -r out/starter out/starter2` produces no output.

Finally: append this prompt to PROMPT_HISTORY.md, then commit (PLAN §16 step 8, plus a separate commit for the simulator). Do not push yet.
```

---

## Prompt 3 — Promotions, rules, orders, pricing engine, and PriceDecision with stored inputs and reasons

```text
Read PLAN.md (§2, §4.3, §5.6–§5.10, §7.2 steps 3 and 5, §8). Implement phase P2 and the second half of P3 on top of the existing generator.

Deliverables:
1. generator/master/PromotionGenerator writes promotion, promotion_rule, promotion_product and promotion_status_event
   per PLAN §7.2 step 3. This covers Mon→Sun markdowns, coupons with coupon codes, EXCLUSIVE rules,
   EXCLUDES_PROMOTION pairs on overlapping promotions (the conflicting coupons), cancellations, and the ID ordering in PLAN §6.
2. pricing package, pure Java with no Spring or DB:
   PricingPolicy (+ PricingPolicyProperties), GuardrailCalculator, MarginCalculator (net basis only),
   CompetitorReferenceResolver (72 h on source_captured_at), DemandSignalCalculator,
   PromotionStackResolver (total order: priority, then promotion_id), PricePointRounder, PricingEngine,
   PricingContext, PricingResult and ExplanationStep. Implement steps S1–S12 and every ReasonCode in PLAN §8.
3. Replace InitialPriceSetter. MarketSimulator triggers decisions per PLAN §2 assumption 7 and builds PricingContext
   with no look-ahead. It writes:
   - price_decision: every stored-input field in PLAN §5.9, plus reason_codes, explanation JSON and rule_version.
   - price_decision_observation: USED or EXCLUDED_* with age_hours.
   - price_decision_promotion: APPLIED or REJECTED with a reason.
   - price_decision_status_event, including ESCALATED, APPROVED, APPLIED and SUPERSEDED.
   price_history gets source_decision_id, change_reason and shelf_price.
4. generator/sim/OrderGenerator splits units sold into customer_order rows.
   Coupons go through PromotionStackResolver into order_promotion (APPLIED or REJECTED with a reason).
   2% of orders are CANCELLED. Every order amount is derived per PLAN §5.8.
5. docs/pricing_rules.md (S1–S12 with one worked numeric example) and docs/generator_design.md.
6. Tests:
   - GuardrailCalculatorTest.
   - MarginCalculatorTest, including the margin-before-discount regression.
   - CompetitorReferenceResolverTest, including the exact 72 h boundary.
   - PromotionStackResolverTest, including invariance to shuffled input.
   - PricePointRounderTest and PricingEngineTest (every reason code reachable).
   - GoldenScenariosTest: 20 hand-computed cases in src/test/resources/golden/pricing_scenarios.json.
   - Extend DerivedValuesTest: order totals reconcile; units_sold = Σ FULFILLED quantity; every price_history row
     matches its decision's final_list_price; a clean run has no price below floor or above MRP.

Done when:
- ./mvnw clean verify → all tests green.
- `generate --scale starter --out out/starter` writes all 16 data tables, and every price_history row has a source_decision_id.
- generate prints, and the starter run has non-zero counts of, all of these:
  COMPETITOR_DATA_STALE decisions, GUARDRAIL_CONFLICT_ESCALATED decisions, stockout days and REJECTED coupons.
- Two generations with the same seed are still byte-identical.

Finally: append this prompt to PROMPT_HISTORY.md, then commit (PLAN §16 steps 6, 7 and 9). Do not push yet.
```

---

## Prompt 4 — Anomaly injection, ground truth, loader, validator, detection, evaluation, export, checksums, reproducibility

```text
Read PLAN.md (§9, §10, §11, §12). Implement phases P4–P6 on top of the existing generator and pricing engine.

Deliverables:
1. generator/anomaly: AnomalyPlanner, AnomalyPlan, AnomalyInjector and GroundTruthRecord.
   Inject the 5 issue types exactly as the PLAN §9 table specifies, through simulator hooks, keeping all arithmetic consistent.
   Write labels/ground_truth.csv and labels/ground_truth.json with the PLAN §9 columns.
   --anomalies-per-type 0 must produce a clean dataset.
2. io: ChecksumService, ManifestWriter (manifest.json + checksums.sha256 per PLAN §11), CsvDatasetReader,
   JsonExporter (JSON Lines above 100k rows) and SqlExporter (schema.sql + seed.sql).
3. persistence/BulkLoader + LoadCommand: JdbcTemplate batchUpdate with batch size 5000 and reWriteBatchedInserts=true,
   TRUNCATE first, TableRegistry order. It never loads labels/.
4. validation: every category in PLAN §10, generated from TableRegistry where possible.
   ValidationSummaryWriter writes JSON and Markdown. ValidateCommand exits non-zero on any structural FAIL.
5. detection: ExceptionDetector, the 5 rule detectors and OperationalExceptionCollector, plus DetectCommand.
   The detection code must not reference generator.anomaly or labels/.
6. evaluation: GroundTruthEvaluator, DatasetMetricsCalculator (PLAN §7.5 bands) and EvaluateCommand.
7. ExportCommand, ReproCheckCommand and RunAllCommand.
   Scripts:
   - scripts/run_all.sh: docker up → build → generate → load → validate → detect → evaluate → export.
   - scripts/repro_check.sh: same seed twice → IDENTICAL; different seed → DIFFERENT; writes docs/evidence/reproducibility_report.md.
8. docs/anomalies_and_ground_truth.md, docs/validation.md and docs/provenance.md.
9. Tests: AnomalyInjectionTest, NoLeakageTest, CsvRoundTripTest, BulkLoaderIT,
   ValidationRunnerIT (src/test/resources/fixtures/broken/* datasets, each breaking exactly one rule),
   EndToEndPipelineIT and GeneratorDeterminismIT.

Done when:
- ./mvnw clean verify → all tests green.
- `./scripts/run_all.sh starter` exits 0. validation_summary.md shows 0 structural failures and 25 business-rule findings (5 per type).
- evaluation_metrics.json shows precision = recall = 1.0 for each of the 5 issue types.
- `./scripts/repro_check.sh starter` prints IDENTICAL, and the different-seed check prints DIFFERENT.
- out/starter is copied to data/sample/, and `cd data/sample && sha256sum -c checksums.sha256` passes.

Finally: append this prompt to PROMPT_HISTORY.md, then commit (PLAN §16 steps 10–14, 16, and the data/sample part of 17). Do not push yet.
```

---

## Prompt 5 — REST API and the complete test suite

```text
Read PLAN.md (§12, §13). Implement phase P7 on top of the existing pipeline.

Deliverables:
1. REST API per PLAN §13:
   - Controllers: PricingController, DecisionController, ExceptionController, ProductController and ReportController.
   - service: SimulationService, DecisionExplainService, ExceptionQueryService and ReportService.
   - persistence DAOs: PricingInputDao, DecisionQueryDao, ExceptionDao and RunDao.
   - DTOs, ApiExceptionHandler (RFC 7807 ProblemDetail) and OpenApiConfig.
   POST /pricing/simulate is read-only and reuses PricingEngine.
   /decisions/{id}/explain returns the full traceability chain, and links only detector findings (never labels).
2. Tests:
   - PricingControllerIT, DecisionControllerIT and ExceptionControllerIT (MockMvc, H2): success paths, 400 validation, 404 ProblemDetail, paging and filters.
   - EngineReplayIT: 100 stored non-anomalous decisions, re-run from the DB, give the identical final price and reasons.
   - Close every remaining gap against the PLAN §12 test list.
3. scripts/api_smoke.sh: curl simulate, explain and exceptions against a running server; save the real responses to docs/evidence/api_samples.md.
4. docs/api.md: endpoints, request and response examples, curl commands.

Done when:
- ./mvnw clean verify → all tests green. Every class listed in PLAN §12 exists and runs.
- With the starter data loaded, `java -jar target/pricing-sim.jar` serves http://localhost:8080/swagger-ui.html,
  and `./scripts/api_smoke.sh` exits 0 with non-empty responses for all three endpoints.

Finally: append this prompt to PROMPT_HISTORY.md, then commit (PLAN §16 step 15). Do not push yet.
```

---

## Prompt 6 — Full-scale run, documentation, fresh-clone verification, submission readiness

```text
Read PLAN.md (§14, §15, §17, §18). Implement phases P8–P9. The submission deadline is Sun 15-11-2026 12:02 UTC.

Deliverables:
1. Full run: `java -Xmx2g -jar target/pricing-sim.jar run-all --scale full --out out/full` (5,000 SKUs, 180 days, 2,000 promotions).
   - Record the runtime of each stage.
   - Copy the manifest, validation summary and evaluation metrics to docs/evidence/
     (manifest_full.json, validation_summary_full.md, evaluation_metrics_full.json).
   - Required: 0 structural failures, 250 business-rule findings, precision = recall = 1.0 per type.
   - Fix any realism-band WARN, or explain it in the README.
   - Run `./scripts/repro_check.sh full`. If it is too slow, run it for starter and state that in the README.
2. Save the `./mvnw clean verify` summary (test counts) to docs/evidence/test_report.txt.
3. docs/design_decisions.md: one ADR per trade-off in PLAN §2.
4. README.md, every section in PLAN §14, using real numbers from docs/evidence.
   Every command in it must be copy-paste runnable from a fresh clone.
5. AI_USAGE.md per PLAN §15, drafted from PROMPT_HISTORY.md and git log.
   Every statement about what the developer personally reviewed, changed or validated is marked `TODO(me): confirm`.
   Do not invent such claims.
6. PROMPT_HISTORY.md is complete and consistent with git log. scripts/extract_prompt_history.sh exists.
7. PLAN.md matches the final implementation: table counts, file list, and the commit-attribution statement in §15.
8. scripts/fresh_clone_test.sh <repo URL>: clone into a temp directory and use a separate Compose project name and port.
   Run build, tests, starter run_all and repro_check, and compare the regenerated starter checksums with data/sample/checksums.sha256.
9. Push main to origin. Run `./scripts/fresh_clone_test.sh https://github.com/Saisrijan166/pricing-promo-simulator.git`.
   Fix anything it finds as `fix:` commits, push, and re-run until it passes. Then tag v1.0.0 and push the tag.

Done when:
- The fresh-clone test passes against the public GitHub URL.
- `git status` is clean, `git log origin/main` equals local main, and the v1.0.0 tag is on GitHub.
- The repo opens without login (check with `curl -s -o /dev/null -w '%{http_code}'` → 200).
- No secrets are tracked, and no tracked file is over 50 MB.
- The PLAN §18 checklist is printed, with ✅/❌ and the evidence (file or command) for every item.
  Any remaining `TODO(me)` markers are listed for the developer to resolve before submitting.
- The repository URL is not submitted on the YottaFlex portal; the developer submits it, once.

Finally: append this prompt to PROMPT_HISTORY.md, then commit (PLAN §16 steps 17–18), push, and tag v1.0.0.
```
