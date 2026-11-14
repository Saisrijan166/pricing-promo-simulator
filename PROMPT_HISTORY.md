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
diffing the transcript's file write against the committed file). TODO(me): add anything you reviewed or changed your
mind about.

**Verification:** Planning only, so nothing to execute. PLAN §1 maps every review criterion in the brief to the section
that addresses it. TODO(me): confirm your own read-through against the brief.

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

This is `BUILD_PROMPTS.md` Prompt 1 with one change: the final "create the public repo and push" line was replaced by
the no-push note.

**What AI produced:** _In progress. Completed in the last commit of this prompt (PLAN §16 step 5)._

**What I did with it:** _Pending._

**Verification:** _Pending._
