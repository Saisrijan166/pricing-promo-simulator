# AI Usage

## Tools
- Claude Code (VS Code), model Claude Opus 5.5, on 14-11-2026.
- Every prompt I used is recorded in [PROMPT_HISTORY.md](PROMPT_HISTORY.md).

## How I used AI
I used AI as the main implementer and acted as the specifier and reviewer.
- **I decided:** the tech stack (Java 21, Spring Boot, PostgreSQL, Flyway), the phase split,
  the "done when" check for each phase, and which design choices to accept.
- **AI did:** drafted PLAN.md, wrote most of the code, tests, scripts and docs, phase by phase.

## Where I relied heavily on AI
- Scaffolding and boilerplate (Maven/Spring/Docker/Flyway setup, records, enums, DTOs).
- Writing the generator, pricing engine, validator and test suites from the plan.
- First drafts of the documentation.

## What I reviewed and validated myself
- Ran `./mvnw clean verify` (338 tests, 0 failures).
- Ran the starter pipeline: 183 checks, 0 structural failures, every injected anomaly detected.
- Compared regenerated checksums with `data/sample/checksums.sha256`: identical.
- Started the API and ran the smoke test: all endpoints returned 200.
- Did a fresh-clone test against the public GitHub repo.

## Decisions I accepted or changed
- Net-basis margin everywhere (one consistent cost/discount basis).
- Competitor freshness measured on capture time, not ingestion time.
- Guardrail conflicts priced at the floor and escalated, because MRP is a hard ceiling in India.
- Stale-observation realism band left as a documented WARN.

## AI mistakes I caught
- `System.exit` would have stopped the server in web mode (found in code review).
- A test expected 41 CHECK constraints; there are 42 (found by the test run).
- A test expected the wrong rounding result (the test was wrong, not the code).
- The stale-observation anomaly picked an observation that couldn't become USED
  (found only at full scale).