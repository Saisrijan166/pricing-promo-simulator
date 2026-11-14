# PROMPT_HISTORY.md

A chronological log of every AI prompt used to build this project. The YottaFlex submission guidelines require it.
How AI was used overall is summarised in `AI_USAGE.md`.

- **Tool:** Claude Code (VS Code extension), model Claude Opus 5.5 (`claude-opus-5-5`), unless an entry says otherwise.
- **Source:** Claude Code session transcripts (`~/.claude/projects/<project>/*.jsonl`). Prompts are copied verbatim
  from the transcripts. Timestamps are the transcript's UTC timestamp for the user message.
- **Redaction:** secrets, tokens and personal contact details are replaced with `[REDACTED]`. The IDE adds editor
  context to each message automatically (`<ide_opened_file>` notes) and wraps pastes in tags. Neither is part of the
  prompt, so both are omitted. Nothing else is changed.
- The log is updated at every commit boundary (PLAN.md §15).
- Entries #03a–#03d come from a second Claude Code session that ran in parallel on repository housekeeping. They
  are placed chronologically; the letter suffix keeps the existing entry numbers, which commit messages refer to.
- `scripts/extract_prompt_history.sh` regenerates a draft of every prompt from the transcripts, for checking this
  file against them.

---

### #01 — 2026-11-14 06:50 UTC — Claude Code (Opus 5.5) — Phase P0 Planning

**Prompt (verbatim):**

```text
Read docs/challenge_brief.md and the YottaFlex submission guidelines PDF in this folder. Create PLAN.md, a complete start-to-finish plan to build, run and submit this assignment.

Fixed stack: Java 21, Spring Boot 3.x, Maven, PostgreSQL (Docker Compose), Flyway, JdbcTemplate batch inserts, H2 for tests, JUnit 5, Jackson, Commons CSV, springdoc-openapi, Picocli or CommandLineRunner for the generator CLI.

PLAN.md must include:
1. Project summary and what reviewers evaluate
2. Assumptions, trade-offs, limitations
3. Final folder/package structure (every file to create, and its purpose)
4. ER design: 9 entities, PKs, FKs, cardinality, bridge tables, lifecycle states, effective dates
5. Data dictionary outline (type, required, enums, units, uniqueness, validation per field)
6. ID rules (SKU-000001, COST-<sku>-2026-01, PRC-<sku>-20260101, PROMO-2026-000001, DEC-2026-000001)
7. Generator design: fixed seed 20261022, parameterized scale (5,000 SKUs, 180 days, 2,000 promos), generation order (master, then child/event), derived values computed from source records
8. Pricing engine rules: floors, ceilings, margin basis, stale competitor check, promo stacking/exclusion, candidate price, PriceDecision with stored inputs and reason
9. Anomaly injection: the 5 labelled edge cases and the ground_truth.csv format (never leaked into features)
10. Validation checks (PK, FK, enums, date order, reconciliation, lifecycle, duplicates) and the validation_summary output
11. Export (CSV/JSON/SQL), checksums, and the run-twice reproducibility proof
12. Tests to write
13. REST API endpoints (simulate price, explain decision, list exceptions)
14. README contents: install, configure, run commands, verify steps
15. PROMPT_HISTORY.md and AI_USAGE.md approach (where AI helped, what I reviewed/changed/validated)
16. Git commit strategy (meaningful commits, within the 72-hour window)
17. Step-by-step run instructions (docker compose up, build, generate, validate, test)
18. Final submission checklist (public GitHub repo, fresh-clone test, submit URL on the YottaFlex portal before Sun 15 Nov 2026 12:02 UTC, single submission only)

Be specific and actionable. Do not write any code yet, only the plan.
```

**What AI produced:** `PLAN.md`, an 18-section plan to build, run and submit the project. It covers assumptions and
trade-offs, package structure, ER design, a data dictionary outline, ID rules, generator design, pricing-engine steps
S1–S12, anomaly injection and ground truth, validation checks, export and reproducibility, the test list, API, README
outline, AI-disclosure approach, commit strategy, run steps and a submission checklist. The AI read the challenge brief
and the submission-guidelines PDF to write it.

**What I did with it:** Accepted as written. The committed `PLAN.md` is identical to the AI's final version (checked by
diffing the transcript's file write against the committed file). It became the specification for every build
prompt.

**Verification:** Planning only, so nothing to execute. PLAN §1 maps every review criterion in the brief to the section
that addresses it.

---

### #02 — 2026-11-14 07:08 UTC — Claude Code (Opus 5.5) — Phase P0 Build prompts

**Prompt (verbatim):**

```text
Read PLAN.md. Create BUILD_PROMPTS.md containing 5 copy-paste prompts that I will give you (Claude Code) one by one to build the entire assignment end to end.

Rules for each prompt:
- It must be self-contained, short and precise, and reference PLAN.md
- State exact deliverables, files to create, and a "done when" check (build passes, tests pass, a command to run)
- End by telling you to commit with a meaningful message and append the prompt used to PROMPT_HISTORY.md

Split the work like this:
1. Project setup, Docker Compose, Flyway schema, domain entities, data dictionary
2. Seeded master data generators (Product, Cost, Price, Competitor, Inventory) and CLI params
3. Promotions, rules, orders, pricing engine, and PriceDecision with stored inputs and reasons
4. Anomaly injector, ground_truth file, validator, validation_summary, CSV/JSON/SQL export, checksums, reproducibility script
5. Tests, REST API, README, AI_USAGE.md, final full run at 5,000 SKUs, fresh-clone check, submission checklist

Output only the 5 prompts in BUILD_PROMPTS.md, clearly numbered.
```

**What AI produced:** `BUILD_PROMPTS.md`, five copy-paste prompts that cover PLAN phases P0–P9. Each prompt lists exact
deliverables, a "done when" check, and an instruction to commit and log itself here.

**What I did with it:** Accepted as written. The committed file is identical to the AI's output. Prompt 1 was edited
before use (see #03).

**Verification:** Each prompt is verified when it runs. See #03 onwards.

---

### #03 — 2026-11-14 07:12 UTC — Claude Code (Opus 5.5) — Phases P0–P1 Setup, schema, domain, data dictionary

**Prompt (verbatim):**

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


Note: do not push as of now anything will do everything later after everything is built and done
```

This is the original `BUILD_PROMPTS.md` Prompt 1 with one change: the final "create the public repo and push" line was
replaced by the no-push note. Prompt 1's wording in `BUILD_PROMPTS.md` was tidied afterwards; the text above is what
actually ran.

**What AI produced:** Five commits (PLAN §16 steps 1–5):
- The brief moved to `docs/challenge_brief.md`; `.gitignore`, `.gitattributes`, `.editorconfig`, MIT `LICENSE`.
- `pom.xml` (Java 21, Spring Boot 3.5.16, jar `pricing-sim.jar`), Maven Wrapper 3.3.4 (Maven 3.9.16), `docker-compose.yml`
  (postgres:16-alpine, healthcheck, named volume, port from `.env`), `.env.example`, `PricingSimApplication`,
  `application.yml`, `application-offline.yml`, `application-test.yml` (H2, PostgreSQL mode).
- Flyway V1–V5: 19 tables, 19 PKs, 28 FKs, 42 enum CHECKs, non-unique lookup indexes, no business-rule constraints.
- 16 domain records, 25 enums, `IdFactory` / `IdPatterns` / `SequenceCounter`, `TableSpec` / `TableRegistry`.
- `docs/data_dictionary.md`, `docs/er_diagram.md`, `docs/id_conventions.md`.
- Tests: `IdFactoryTest` (28), `TableRegistryTest` (98, parameterised over the 16 tables), `FlywayMigrationIT` (7).

Decisions and deviations the AI flagged for review:
1. PLAN §3 and §10 say "17 data tables", but §4.1 lists 16 (9 entities + 4 bridges + 3 event tables). There are 16
   records, and BUILD_PROMPTS Prompt 3 also says 16. The registry holds the 16 tables from §4.1.
2. `IssueType` has the 5 anomaly types plus 4 OPERATIONAL types (COMPETITOR_DATA_STALE, GUARDRAIL_CONFLICT_ESCALATED,
   STOCKOUT, COUPON_REJECTED). With these, `detected_exception` can hold the operational exceptions from PLAN §10 and §13.
   `Uom` and `FundingSource` enums were added because PLAN §5 defines those value sets.
3. ID regexes use `\d{4}` for the year (PLAN §6 `YYYY`), not the literal `2026` shown in the PLAN §5 regex column.
4. Natural-key uniqueness, such as `U(sku_id, price_date)`, is not a DB constraint. The indexes are non-unique and the
   validator's DUPLICATE checks enforce it (PLAN §2, §10).
5. PLAN §5 does not define columns for `pipeline_run`, `validation_result` or `detected_exception`. The AI designed them
   from PLAN §9, §10 and §13.
6. The app imports `.env` (`spring.config.import`), so one `.env` file configures both Docker Compose and the app.
7. A first argument starting with `--` (for example `--server.port=9090`) keeps web mode. Only a command word switches
   to CLI mode.
8. The AI caught a bug in its own first draft before committing. `System.exit(SpringApplication.exit(...))` also ran in
   web mode, which would have stopped the server straight after startup.

**What I did with it:** Accepted, including decisions 1–5 above, once `./mvnw clean verify` passed and the schema applied to PostgreSQL. I edited the prompt before running it (see the note above).

**Verification:**
- `./mvnw clean verify` → BUILD SUCCESS, 133 tests, 0 failures. Commits 2 and 4 were also built and tested on their own
  in a clean worktree.
- `docker compose up -d` → postgres `healthy`. A local `.env` used port 5433, because 5432 was already in use on this
  machine.
- `java -jar target/pricing-sim.jar` → Flyway "Successfully applied 5 migrations to schema "public", now at version v5"
  on PostgreSQL 16.15. `/swagger-ui.html` and `/v3/api-docs` return HTTP 200.
- `docker compose exec postgres psql -U pricingsim -d pricingsim -c '\dt'` → 20 relations: the 19 application tables
  plus `flyway_schema_history`. Postgres constraint counts match H2: 19 PK, 28 FK, 42 CHECK.
- `java -jar target/pricing-sim.jar generate` with no reachable database → the `offline` profile starts and exits 0.
- The 5 Mermaid diagrams in `docs/er_diagram.md` render without errors in mermaid-cli 11.

---

### #03a — 2026-11-14 08:45 UTC — Claude Code (Opus 5.5), separate session — Housekeeping: Repository identity and remote

**Prompt (verbatim):**

```text
revert the  commits and all 

keep the changes locally 
and have to commit with saisrijan166 idenetity only that too no claude nothing else trace and 

git remote add origin https://github.com/Saisrijan166/pricing-promo-simulator.git
git branch -M main
git push -u origin main

this is repo
```

**What AI produced:** In that session, the five P0–P1 commits were re-created with author and committer
`Saisrijan166 <Saisrijan166@gmail.com>` and no AI trailer. File contents were unchanged and the commits got new hashes
(`5c2dd0c` … `e43b534`). The `origin` remote was added, and `main` was pushed to it.

**What I did with it:** Accepted.

**Verification:** `git log --format='%an <%ae>'` shows only Saisrijan166, and `git ls-remote origin` showed
`refs/heads/main` on GitHub.

---

### #03b — 2026-11-14 08:47 UTC — Claude Code (Opus 5.5), separate session — Housekeeping: Question: are PROMPT_HISTORY and BUILD_PROMPTS needed?

**Prompt (verbatim):**

```text
promt histry and build prompts are needed ?? to commit or submission
```

**What AI produced:** An answer only, with no file change. `PROMPT_HISTORY.md` is required by the submission guidelines;
`BUILD_PROMPTS.md` is optional working material.

**What I did with it:** Accepted.

**Verification:** Not applicable (question).

---

### #03c — 2026-11-14 08:50 UTC — Claude Code (Opus 5.5), separate session — Housekeeping: Rewrite BUILD_PROMPTS as standard, self-contained prompts through submission

**Prompt (verbatim):**

```text
so make them starndartd prompts ad the standard prompt histoiry no traces o this chat or naything should exists 
not include this session in histiry or propts  so on 

so once finallised prompts rewrite the many requeired or whatvere required final submisison till 
i want completekte submission ready prompts so on in thefile and till prompts finish suubmissionr ead appluaction should be made
```

**What AI produced:** Commit `6574e1c`. `BUILD_PROMPTS.md` was restructured into six self-contained phase prompts, from
setup through submission (the prompts used as #04 onwards), and five lines in `PROMPT_HISTORY.md` #03 were adjusted.

This prompt asked to keep that session out of the history. On 14-11-2026, during prompt #08, the developer chose to
record all prompts verbatim instead, so the history is complete and consistent with git log.

**What I did with it:** Accepted.

**Verification:** `git show --stat 6574e1c`.

---

### #03d — 2026-11-14 08:52 UTC — Claude Code (Opus 5.5), separate session — Housekeeping: Do not commit BUILD_PROMPTS

**Prompt (verbatim):**

```text
also no need of commiting build prompts
```

**What AI produced:** Commit `a7a5804`. `BUILD_PROMPTS.md` was removed from the repository and is kept locally only
(listed in `.git/info/exclude`). Each prompt in it is recorded verbatim in this file when it is used.

**What I did with it:** Accepted.

**Verification:** `git show --stat a7a5804`.

---

### #04 — 2026-11-14 08:53 UTC — Claude Code (Opus 5.5) — Phase P3 (first half) Seeded generators, simulator and CLI

**Prompt (verbatim):**

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

This is `BUILD_PROMPTS.md` Prompt 2, unchanged.

**What AI produced:** Two commits (PLAN §16 steps 8 and 9):
- `generator/rng`: `RandomStreams` (`java.util.Random` seeded from the first 8 bytes of SHA-256(`seed|stream|key`)) and
  `Distributions` (uniform, Bernoulli, Box–Muller normal, lognormal, exponential, chunked Knuth Poisson, truncated
  normal, Beta-like, weighted choice, Fisher–Yates shuffle, largest-remainder apportioning; `StrictMath` only).
- `config`: `GeneratorProperties` (`generator.*`: seed 20261022, default scale, fixed `reference-ts`, scales) and
  `ScaleProfile`. The tiny, starter and full profiles from PLAN §7.1 are in `application.yml`.
- `cli`: `PricingSimCommand`, `PicocliRunner` (Spring-aware picocli factory, exit code passed to the shell), the
  `ScaleOptions` mixin with all 8 flags, and `GenerateCommand` (offline profile, writes `<out>/csv/<table>.csv`).
- `generator/master`: `ProductGenerator` (+ `product_status_event`), `CostRecordGenerator`,
  `CompetitorObservationGenerator` and `CategoryCatalog` (fictional brands, per-category price, margin and markup bands).
- `generator/sim`: `MarketSimulator` (day loop), `SkuState`, `DemandModel`, `ReplenishmentPolicy` ((s, S), lead time
  3–10 days, doubled for 5% "fragile supply" SKUs), the `PriceSetter` interface and the temporary `InitialPriceSetter`.
- `generator/sink`: `RowSink`, `CsvRowSink` (formats each row on arrival, sorts by PK on close, fails on a duplicate
  PK), `InMemoryRowSink`. `io/CsvSupport` implements the PLAN §11 CSV rules and reads files back into records.
- Supporting classes: `DatasetGenerator` and `GenerationContext` (both listed in PLAN §3), `GenerationSettings` and
  `Amounts` (exact double → `BigDecimal` rounding).
- Tests: `RandomStreamsTest` (7), `DistributionsTest` (19), `GeneratorScaleTest` (8), `DerivedValuesTest` (11),
  `GeneratorDeterminismTest` (3), and `GeneratorSourceRulesTest` (1), which fails if generator or pricing code uses
  the clock, `Math.random`, non-strict `Math` or hash-ordered collections.

Decisions and deviations the AI flagged for review:
1. `pricing/PricePointRounder` (PLAN §8 S9) was written now, because the initial price and MRPs need it. The next step
   reuses it and adds `PricePointRounderTest`. The S2 floor is computed inside the temporary `InitialPriceSetter`
   until `GuardrailCalculator` exists.
2. `--promos` and `--anomalies-per-type` are accepted, applied to the run settings and printed. They are kept with
   the command line in `GenerationSettings` for the manifest (PLAN §11, a later step). No extra file is written.
3. Generation rates (92/5/3 lifecycle, 70/22/4/4 cost changes, cadences and so on) are named constants in the
   generator classes, next to the PLAN §7.2 reference. `GeneratorProperties` binds only the seed, default scale,
   reference timestamp and scales.
4. Lifecycle and category mixes are apportioned exactly (largest remainder) and then shuffled, instead of drawn per SKU
   (starter: 184 / 10 / 6). Match confidence is "Beta-like" as a mixture: 5% U(0.60, 0.80), 95% 0.80 + 0.20·max(U1, U2).
5. A product is active from `max(launch, window start)` to `min(discontinued − 1, window end)`. `days_of_cover`
   averages the SKU's last 28 snapshot rows (fewer at the start of its history). Both definitions were added to
   `docs/data_dictionary.md`.
6. A pre-window launch's `∅→ACTIVE` event carries its launch year (for example `PEV-2021-000001`), per PLAN §6 rule 2.
   `docs/id_conventions.md` now says so.
7. PLAN §7.4 estimated ~400,000 competitor observations, but the §7.2 cadence and coverage give ~535,000 (measured
   534,705 at full scale). PLAN §7.4 was corrected in the first commit.
8. `generate` deletes the `*.csv` files in `<out>/csv` first, so a re-run never leaves stale tables behind.
9. Full scale was run once as a check: 24 s wall-clock and 1.4 GB peak RSS with `-Xmx2g` for these 6 tables. The
   later tables (orders, decisions) add rows that `CsvRowSink` holds in memory until it sorts them, so memory needs
   watching in the next step.

**What I did with it:** Accepted, including decisions 1–9 above, once the checks below passed.

**Verification:**
- `./mvnw clean verify` → BUILD SUCCESS, 182 tests (175 unit + 7 integration), 0 failures.
- With the Postgres container stopped (`docker compose stop`), `java -jar target/pricing-sim.jar generate --scale
  starter --out out/starter` exits 0 and writes the 6 CSVs: product 200, product_status_event 213, cost_record 587
  (cost months 2026-01, 2026-02, 2026-03), competitor_observation 7,143, price_history 11,525, inventory_snapshot
  11,525 (60 days, 01-01-2026 to 01-03-2026).
- Generating into `out/starter2`, then `diff -r out/starter out/starter2` → no output, exit 0.
- Starter realism: stockout SKU-days 3.25% (PLAN §7.5 band 2–6%). Full scale: stockouts 3.20%, and the top 20% of
  SKUs sell 66.8% of units (band 55–85%).
- Flag overrides (`--skus --days --seed --start-date --promos --anomalies-per-type --out`) apply. An unknown scale,
  `--skus 0` or an unknown option gives an error message and a non-zero exit.

---

### #05 — 2026-11-14 09:29 UTC — Claude Code (Opus 5.5) — Phases P2 and P3 (second half) Pricing engine, promotions, decisions and orders

**Prompt (verbatim):**

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

This is `BUILD_PROMPTS.md` Prompt 3, unchanged.

**What AI produced:** Three commits (PLAN §16 steps 6, 7 and 9):
- **Pricing building blocks** (`pricing/`, pure Java):
  - `PricingPolicy` (+ `config/PricingPolicyProperties` and `pricing.policy.*` in `application.yml`).
  - `GuardrailCalculator`, `MarginCalculator` (net basis only, with no list-price margin method at all) and
    `CompetitorReferenceResolver` (visibility needs captured and ingested ≤ `decision_ts`; 72 h on capture time;
    latest per competitor; superseded within 72 h; median).
  - `DemandSignalCalculator`, `PromotionOffer` and `PromotionStackResolver` (total order on priority, then
    `promotion_id`; sequential percent-then-amount discounts rounded to the paisa).
- **Engine:** `PricingEngine` (S1–S12), `PricingContext`, `PricingResult` and `ExplanationStep` (deterministic compact
  JSON). Every `ReasonCode` is produced.
- **Generator:**
  - `PromotionGenerator` writes promotion, promotion_rule, promotion_product and promotion_status_event.
  - `EnginePriceSetter` replaces `InitialPriceSetter`. It triggers INITIAL / COST_CHANGE / STOCKOUT_EVENT /
    WEEKLY_REVIEW, adds 1% MANUAL_OVERRIDE, and writes price_decision, both bridges and the status events.
  - `OrderGenerator` writes customer_order and order_promotion.
  - `CsvRowSink` now spills sorted runs to disk and merges them, so the heap stays flat at full scale.
- **Docs:** `docs/pricing_rules.md` (S1–S12, a worked example that is also a test) and `docs/generator_design.md`.
- **Tests:**
  - `GuardrailCalculatorTest` (7), `MarginCalculatorTest` (6), `CompetitorReferenceResolverTest` (9),
    `DemandSignalCalculatorTest` (6), `PromotionStackResolverTest` (11), `PricePointRounderTest` (16).
  - `PricingEngineTest` (21) and `GoldenScenariosTest` (20 cases in
    `src/test/resources/golden/pricing_scenarios.json`).
  - Extended: `DerivedValuesTest` (17), `GeneratorScaleTest` (10), `GeneratorDeterminismTest` (4).

Decisions and deviations the AI flagged for review:
1. **Guardrail conflict prices above MRP.** PLAN §8 S3 sets price = floor when floor > MRP. PLAN §2 assumption 4 calls
   MRP a legal hard ceiling. The engine follows S3 and escalates to an OPS_MANAGER. This is the only case where a list
   price exceeds MRP (1 SKU-day in starter). The test asserts "no price above MRP except escalated guardrail
   conflicts", and `docs/pricing_rules.md` § Known tension records it. Developer decision needed: keep the S3
   behaviour, or cap at MRP and accept a sub-minimum margin.
2. **Definitions made precise; PLAN §5.9, §7.2 and `docs/data_dictionary.md` updated:**
   - `on_hand_units` = previous closing + receipts delivered before trading (on the first day there is no previous
     closing).
   - `velocity_ratio = (d7/n7)/(d28/n28)`, using days with history, and 1 without sales.
   - `days_of_cover = on_hand / max(d28/n28, 0.1)`. S6 overstock and low-cover adjustments need 7 days of history.
   - `decision_type` is HOLD for INITIAL.
   - MIN_ORDER_QTY is coupon-only, since a markdown prices one unit on the shelf.
3. **Primary reason:** two fixed precedence lists, one for price changes and one for holds (`docs/pricing_rules.md`).
4. **No REJECTED decisions.** Escalations are always approved the same day (PLAN §2 limitation). REJECTED is allowed
   but not generated, because a rejected decision would leave an older decision's expired markdowns on the shelf.
5. **Markdown timing.** An active markdown is cancelled at a Monday 00:00Z, and markdowns run Monday to Sunday. Shelf
   prices therefore never carry a cancelled or expired markdown, even between decisions. Orders are placed between
   06:00 and 24:00Z, after the 06:00 decision.
6. **Markdowns on orders.** `order_promotion` lists the day's markdowns on every order (`coupon_presented = false`),
   so `Σ APPLIED discount_amount = gross − net`.
7. **Considered observations.** Only the latest observation per competitor, plus older ones still inside 72 h, count
   as "considered". This keeps `price_decision_observation` bounded (305k rows at full scale).
8. **Realism bands, starter:**
   - In band: stockouts 4.5%, SKU-days under a markdown 17.2%, coupon rejections 21.9%, margin 39.8%, list changes
     1.9 per SKU-month, Pareto 70%.
   - **Outside:** the stale observation share is 21% (band 5–15%), because GAMMA observes weekly and the freshness
     window is 72 h. Not changed: it needs a choice between the PLAN cadence and the PLAN band.
9. **Volumes:** full-scale volumes differ from the PLAN §7.4 estimates, including customer_order 941,692 vs ~700,000
   and about 764 MB of CSV vs ~400 MB. `price_decision` is 300 MB, mostly explanation JSON. The full run takes about
   1 minute and also completes with `-Xmx768m`. PLAN §7.4 is left for the P8 full run to correct with manifest
   numbers.
10. **Starter guardrail conflicts.** Starter has exactly 1 GUARDRAIL_CONFLICT_ESCALATED decision (a supplier shock on
    01-03-2026), so the "non-zero" check depends on the fixed seed. Full scale has 740.
11. **`PricePointRounderTest`** was added here as the earlier history entry said it would be. `DemandSignalCalculatorTest`
    (PLAN §12) was added too.

**What I did with it:** Accepted, including decisions 1, 4 and 8 above, once the checks below passed.

**Verification:**
- `./mvnw clean verify` → BUILD SUCCESS, 287 tests (280 unit + 7 integration), 0 failures.
- All 20 golden scenarios matched their hand-computed values on the first run; each scenario's `notes` field shows the
  arithmetic. One hand-written expectation in `PricePointRounderTest` was wrong: 1234 is a tie between 1229 and 1239,
  and the rule rounds ties up. The test was corrected, not the code.
- `java -jar target/pricing-sim.jar generate --scale starter --out out/starter` → exit 0, 16 CSVs, and all 11,525
  price_history rows have a `source_decision_id`. Printed: COMPETITOR_DATA_STALE decisions 109,
  GUARDRAIL_CONFLICT_ESCALATED 1, stockout days 518, REJECTED coupons 264.
- A second generation into `out/starter2`, then `diff -r out/starter out/starter2` → no output.
- Full scale (`--scale full`, `-Xmx2g`): exit 0 in about 63 s. 147,138 decisions, 941,692 orders,
  COMPETITOR_DATA_STALE 9,369, GUARDRAIL_CONFLICT_ESCALATED 740, stockouts 4.89%, REJECTED coupons 12,532.

---

### #06 — 2026-11-14 10:13 UTC — Claude Code (Opus 5.5) — Phases P4–P6 Anomalies, ground truth, load, validation, detection, evaluation, export

**Prompt (verbatim):**

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

This is `BUILD_PROMPTS.md` Prompt 4, unchanged.

**What AI produced:** Seven commits: PLAN §16 steps 10–14 and 16, plus the data/sample part of step 17.
- **io:** `ChecksumService`, `ManifestWriter` (`manifest.json` + `checksums.sha256`), `CsvDatasetReader`,
  `JsonExporter` (JSON Lines above 100k rows) and `SqlExporter` (`schema.sql` + `seed.sql`). `generate` now writes
  `csv/`, `labels/`, the manifest and the checksums.
- **generator/anomaly:**
  - A baseline dry run collects eligible records (`CandidateCollector`).
  - `AnomalyPlanner` picks exactly N per type from the `anomaly` streams, at most one per SKU.
  - `AnomalyInjector` injects the 5 PLAN §9 types through `SimulationHooks` and `PricingEngine.Faults`, with
    consistent arithmetic.
  - `GroundTruthFiles` writes `labels/ground_truth.csv/.json`.
- **persistence:** `BulkLoader` (one transaction; TRUNCATE … CASCADE / DELETE; batches of 5,000; registry tables only)
  with `load`, plus `RunDao` (reproducible run IDs) and `Dialect`.
- **validation:** `ValidationRunner` with 183 checks in all 11 PLAN §10 categories (PK, FK, enum, ID format and
  duplicates generated from `TableRegistry`), `ValidationSummaryWriter` (JSON + Markdown) and `validate` (exit 1 on a
  structural FAIL).
- **detection:** the 5 rule detectors in `detection.rules` (shared with the BUSINESS_RULE checks),
  `OperationalExceptionCollector`, `ExceptionDetector` (`detected_exception` + CSV) and `detect`.
- **evaluation:** `GroundTruthEvaluator` (per-type, micro and macro P/R/F1), `DatasetMetricsCalculator` (PLAN §7.5
  bands), `EvaluationReportWriter` and `evaluate`.
- **Commands and scripts:** `export`, `repro-check` and `run-all`; `scripts/run_all.sh` and `scripts/repro_check.sh`
  (writes `docs/evidence/reproducibility_report.md`).
- **Docs:** `docs/anomalies_and_ground_truth.md`, `docs/validation.md` (every check ID) and `docs/provenance.md`.
- **Data:** `data/sample/` holds the starter output: csv, json, sql, labels, manifest, checksums, validation summary,
  detected exceptions and evaluation metrics.
- **Tests:**
  - `AnomalyInjectionTest` (8), `NoLeakageTest` (5), `CsvRoundTripTest` (5).
  - Integration: `BulkLoaderIT` (3), `ValidationRunnerIT` (10, with 8 `fixtures/broken/*.properties`),
    `EndToEndPipelineIT` (2), `GeneratorDeterminismIT` (1), `ExportIT` (1).

Decisions and deviations the AI flagged for review:
1. **Two-pass anomaly planning.** PLAN §9 says targets are chosen up front "among records that are eligible at
   baseline". The generator runs the baseline once with rows discarded to find them, then the real run injects. This
   roughly doubles generate time when anomalies are requested. With one anomaly per SKU, every other SKU's data
   equals the clean run; `AnomalyInjectionTest` checks this.
2. **Faults in the engine.** The engine has an explicit `Faults` parameter (forced price, stale-as-fresh, margin on
   list). The anomalies come from the real engine with consistent arithmetic, rather than from editing rows
   afterwards. The generator baseline and the API always use `Faults.NONE`.
3. **Business-rule code lives in detection.** The five rules exist once, in `detection.rules`. The validator's
   BR-001..005 run them, so validation depends on detection; detection and validation never touch
   `generator.anomaly` or `labels/`.
4. **Validation layout.** PLAN §3 lists one class per category; registry-driven categories are grouped in
   `RegistryChecks`. Coverage, floor, velocity and days-of-cover checks run in Java over streamed rows; all other
   checks are SQL that runs on both PostgreSQL and H2.
5. **Fixtures are patches.** `fixtures/broken/` holds declarative patches on a freshly generated tiny dataset
   (properties files), not committed CSV datasets. Where the DB would reject the broken row, the fixture drops that
   constraint first.
6. **MARGIN_BEFORE_DISCOUNT labels.** `baseline_value` is the correct net-basis margin of the stored net price,
   because the baseline record's own margin can equal the injected value.
7. **Vacuous scores.** Precision is 1.0 when nothing was detected and recall is 1.0 when nothing was labelled, so a
   clean run scores 1.0 / 1.0 with zero counts.
8. **Load speed.** Starter takes about 8 s on the local Docker Postgres, so a full-scale load may exceed the PLAN
   target of under 6 minutes. Not measured yet; that is P8.
9. **Committed size.** `data/sample/` is about 48 MB (json 24 MB, sql 13 MB, csv 11 MB). No file is over 50 MB.

**What I did with it:** Accepted, including decisions 1–3 and 9 above, once the checks below passed.

**Verification:**
- `./mvnw clean verify` → BUILD SUCCESS, 322 tests (298 unit + 24 integration), 0 failures. Each of the 6 code commits
  was verified with `./mvnw clean verify` before it was made.
- `./scripts/run_all.sh starter` → exit 0. `validation_summary.md`: 183 checks, **0 structural failures, 25
  business-rule findings** (5 per type).
- `evaluation_metrics.json`: precision = recall = 1.0000 for each of the 5 issue types (5 TP, 0 FP, 0 FN each).
  Realism: 6 of 7 bands OK; the stale observation share (0.212 vs band 0.05–0.15) is WARN, as flagged in #05.
- `./scripts/repro_check.sh starter` → "Same seed 20261022: IDENTICAL", "Different seed 20261023: DIFFERENT".
- `out/starter` copied to `data/sample/` (without `run_log.json`, which holds wall-clock time), then
  `cd data/sample && sha256sum -c checksums.sha256` → all 18 files OK.
- `sql/schema.sql` + `sql/seed.sql` loaded into a fresh PostgreSQL database with matching row counts.

---

### #07 — 2026-11-14 11:02 UTC — Claude Code (Opus 5.5) — Phase P7 REST API and the complete test suite

**Prompt (verbatim):**

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

This is `BUILD_PROMPTS.md` Prompt 5, unchanged.

**What AI produced:** One commit (PLAN §16 step 15).
- **API (`api/`):** `PricingController` (`POST /api/v1/pricing/simulate`), `DecisionController` (`/decisions/{id}`,
  `/decisions/{id}/explain`), `ExceptionController` (`/exceptions` with category / issueType / skuId / date filters and
  paging), `ProductController` (`/products/{sku}/price-history`) and `ReportController` (`/reports/validation-summary`,
  `/reports/evaluation`). DTOs are in `api/dto`. `ApiExceptionHandler` returns RFC 7807 ProblemDetail (404 unknown
  resource, 400 validation).
- **Config:** `config/OpenApiConfig` and `config/ApiConfig`. The API beans are `@Profile("!offline")`, so the DB-free
  commands still start without a database.
- **Services:** `SimulationService` (read-only; reuses `PricingEngine`), `DecisionExplainService` (the full chain and
  detector findings on the decision or its price-history rows), `ExceptionQueryService` and `ReportService` (the files
  of the latest VALIDATE / EVALUATE run).
- **Persistence:** `PricingInputDao` rebuilds a `PricingContext` from the DB with the generator's own definitions.
  `DecisionQueryDao`, `ExceptionDao`, `RunDao.latest` and `Rows` give driver-independent value types.
- **Tests:** `PricingControllerIT` (4), `DecisionControllerIT` (6), `ExceptionControllerIT` (4), `EngineReplayIT` (1);
  `GeneratorScaleTest` gained a starter-scale test; `NoLeakageTest` now also scans the api, service and persistence
  code. All 25 test classes listed in PLAN §12 exist and run.
- **Scripts and docs:** `scripts/api_smoke.sh`, which writes `docs/evidence/api_samples.md` from real responses and
  pretty-prints without parsing numbers, so `249.00` stays `249.00`. `docs/api.md` covers endpoints, inputs, examples,
  curl commands and errors.

Decisions and deviations the AI flagged for review:
1. **How simulate builds its inputs.** The trigger is the stored decision's trigger on that date, else INITIAL without
   earlier price history, else WEEKLY_REVIEW. A stored MANUAL_OVERRIDE supplies its target price as the analyst's
   price. So simulating a stored decision's date reproduces it; `EngineReplayIT` checks 100 decisions of a clean
   starter dataset, explanation JSON included.
2. **Mixed field naming.** Stored rows in `/decisions` and `/explain` keep their column names (snake_case), so they
   match the data dictionary. DTO fields are camelCase.
3. **Report files are found through the database.** The report endpoints serve the JSON files of the latest run,
   located through `pipeline_run.input_path`. That path is relative (`out/<scale>`), so the server must be started
   from the repository root.
4. **What-if competitor prices replace stored observations.** They are treated as ingested at capture, with
   confidence 1.0 unless given.

**What I did with it:** Accepted once the checks below passed. On 14-11-2026 I re-ran `./scripts/api_smoke.sh` against the running server, and every endpoint returned HTTP 200.

**Verification:**
- `./mvnw clean verify` → BUILD SUCCESS, 338 tests (299 unit + 39 integration), 0 failures.
- With the starter data loaded in PostgreSQL, `java -jar target/pricing-sim.jar`: `/swagger-ui.html` → HTTP 200 after
  redirect, and `/v3/api-docs` is served.
- `./scripts/api_smoke.sh` → exit 0. simulate 2,327 bytes, explain 4,896 bytes, exceptions 2,345 bytes, all HTTP 200.
  The unknown-decision example returns a 404 ProblemDetail.
- `generate --scale tiny` with an unreachable `DB_URL` still exits 0 (offline profile, no API beans).

---

### #08 — 2026-11-14 11:26 UTC — Claude Code (Opus 5.5) — Phases P8–P9 Full-scale run, documentation, fresh-clone test, submission

**Prompt (verbatim):**

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

This is `BUILD_PROMPTS.md` Prompt 6, unchanged.

**Developer decision during this prompt:** The AI asked how to record the four prompts of the parallel housekeeping
session. They are needed for the history to be complete and consistent with git log, but at the time they were asked
to be left out. Answer: "Include verbatim (Recommended)". They are now entries #03a–#03d.

**What AI produced:**
- **Full-scale run:** `run-all --scale full`, plus evidence in `docs/evidence/`: `manifest_full.json`,
  `validation_summary_full.md`, `evaluation_metrics_full.json`, `full_run_log.txt`, both reproducibility reports and
  `test_report.txt`.
- **Two fixes found by the full run:**
  - `102144b`: a stale-observation anomaly target that cannot become USED crashed `generate`.
  - `be1e8d2`: markdown SKU-days were 14.9%, just below the 15–30% band.
- **Refreshed data:** `data/sample/` and the API samples regenerated with the final code.
- **New files:** `README.md` (every PLAN §14 section, numbers from the evidence), `AI_USAGE.md` (developer statements
  left for the developer to complete), `docs/design_decisions.md` (16 ADRs), `scripts/fresh_clone_test.sh` and
  `scripts/extract_prompt_history.sh` (jq).
- **PLAN.md alignment:**
  - 16 data tables, not 17.
  - The file tree matches the code.
  - Measured §7.4 volumes and timings.
  - §15 commit attribution: no AI trailer; disclosure is in this file and AI_USAGE.md.
  - §17 commands use `--out`.
  - Checksum verification runs inside `data/sample`.
- **PROMPT_HISTORY.md:** the housekeeping prompts #03a–#03d.

Decisions and deviations the AI flagged for review:
1. **Load is slower than planned.** Full-scale load takes 7.5 min against the PLAN target of 6 min. It is documented,
   not optimised; `COPY` is the next step (ADR-03).
2. **One realism band stays WARN.** The stale-observation share (21.7% vs 5–15%) follows from the plan's own weekly
   GAMMA cadence and 72 h freshness. It is explained in the README rather than tuned away.
3. **README wording.** The README describes the project neutrally. Personal statements of review and validation are
   only in AI_USAGE.md.

**What I did with it:** Accepted. AI_USAGE.md was then completed from the recorded decisions and verification runs.

**Verification (before the push):**
- `run-all --scale full`: exit 0 in 10 min 45 s, peak RSS 1.8 GB.
  - Validation: 183 checks, **0 structural failures, 250 business-rule findings** (50 per type).
  - Evaluation: precision = recall = 1.0 for each type.
  - Realism: 6 of 7 bands OK.
- `./scripts/repro_check.sh full` → IDENTICAL (18 files), seed 20261023 → DIFFERENT. Its fingerprint equals the
  `run-all` dataset's.
- `./scripts/run_all.sh starter` → 0 structural failures, 25 findings, precision = recall = 1.0. `data/sample`
  verifies with `sha256sum -c`.
- `./mvnw clean verify` → 338 tests, 0 failures.
- README checks run by hand against the starter data in PostgreSQL:
  - the three SQL spot checks each return 0;
  - the curl examples (simulate, explain, exceptions, price history, reports) return HTTP 200;
  - the `-Dpricing.policy.freshness-hours=48` override is recorded in the manifest.

**Verification (after the push):**
- `git push origin main` (fast-forward `6574e1c..236ce85`); `origin/main` equals local `main`. The repository page
  returns HTTP 200 without login.
- `./scripts/fresh_clone_test.sh https://github.com/Saisrijan166/pricing-promo-simulator.git` on `236ce85`
  → **FRESH-CLONE TEST PASSED** on the first attempt; no `fix:` commit was needed:
  - clone;
  - `.env` with port 55432 and its own Compose project;
  - `./mvnw clean verify` (91 s);
  - `./scripts/run_all.sh starter` (0 structural failures, 25 findings);
  - `./scripts/repro_check.sh starter` (IDENTICAL / DIFFERENT);
  - regenerated starter checksums equal `data/sample/checksums.sha256`;
  - `sha256sum -c` passes inside `data/sample`.
- Tag `v1.0.0` is placed on the commit that records this result (PROMPT_HISTORY.md only) and pushed.
