# Pricing rules

How `com.pricingsim.pricing.PricingEngine` turns one `PricingContext` into one price decision (PLAN.md §8). The
engine is pure Java with no Spring or database. The generator and `POST /api/v1/pricing/simulate` use the same class,
so the stored inputs of a decision reproduce it. Rule version: `pricing-rules-v1`.

Code: `PricingEngine`, `GuardrailCalculator`, `MarginCalculator`, `CompetitorReferenceResolver`,
`DemandSignalCalculator`, `PromotionStackResolver`, `PricePointRounder`. Tests: `PricingEngineTest`,
`GoldenScenariosTest` (20 hand-computed cases in `src/test/resources/golden/pricing_scenarios.json`) and one unit test
class per calculator.

## Policy

Bound from `pricing.policy.*` in `application.yml`. `GeneratorScaleTest` checks that the file equals
`PricingPolicy.defaults()`.

| Setting | Default | Used in |
|---|---|---|
| `freshness-hours` | 72 | S4: fresh if `decision_ts − source_captured_at ≤ 72 h` (exactly 72 h is fresh) |
| `min-match-confidence` | 0.80 | S4 |
| `max-step-change-pct` | 0.08 | S7 |
| `auto-approve-max-change-pct` | 0.10 | S12 |
| `overstock-days` / `overstock-adj` | 60 / −0.03 | S6 |
| `low-cover-days` / `high-velocity-ratio` / `low-cover-adj` | 7 / 1.2 / +0.03 | S6 |
| `value-offset` / `match-offset` / `premium-offset` | −0.03 / 0 / +0.05 | S5 |
| `default-max-stack` / `default-max-combined-discount` | 2 / 0.40 | S10, when no promotion in the set has the rule |
| `initial-target-ratio` | 0.95 | S5 for INITIAL without a competitor reference |
| `min-history-days` | 7 | S6: overstock and low-cover adjustments need this much sales history |

Changing any value changes the generated dataset, so `PricingPolicy.RULE_VERSION` must change with it.

## When decisions happen (generator)

Decisions run at 06:00Z and take effect the same day (PLAN.md §2 assumption 7). If several triggers apply on one day,
this order wins:

1. INITIAL: the SKU's first active day.
2. COST_CHANGE: the 1st of a month whose landed cost changed (`change_pct ≠ 0`).
3. STOCKOUT_EVENT: the day after closing stock first hits 0.
4. WEEKLY_REVIEW: every Monday.

1% of weekly reviews are a legitimate analyst MANUAL_OVERRIDE: a target within ±5% of the previous price, still
subject to the floor, MRP and price points.

On other days the decision in effect continues. `price_history` repeats its list price and shelf price.

## Inputs (S1)

`PricingContext` contains only data visible before `decision_ts`:

- **Cost:** the cost record effective on the effective date.
- **Product policy:** MRP, MAP, `min_margin_pct`, `min_promo_margin_pct` and price position.
- **Previous price:** the previous list price (null for INITIAL).
- **Stock:** `on_hand_units` = the previous day's closing stock plus today's receipts. Receipts arrive before trading
  starts; on the first day, this is the opening stock plus receipts.
- **Sales:** units sold in the 7 and 28 days before the effective date, and how many of those days have history (n7,
  n28).
- **Competitor observations** of the SKU.
- **Promotions** that include the SKU.

## Steps

| Step | Rule | Reason codes |
|---|---|---|
| S1 Inputs | Snapshot of the context above. INITIAL and COST_CHANGE triggers add their reason. | INITIAL_PRICE, COST_CHANGE |
| S2 Floor | `floor = roundUpToPaise(max(landed / (1 − min_margin_pct), map_price ?? 0))`. Rounding up keeps the margin at the floor ≥ the minimum. | — |
| S3 Ceiling | `ceiling = MRP`. If `ceiling < floor`, it is a **guardrail conflict**: the price is the floor (unrounded), the decision is ESCALATED and an OPS_MANAGER approves it. | GUARDRAIL_CONFLICT_ESCALATED |
| S4 Competitor ref | Only observations with `captured ≤ decision_ts` and `ingested ≤ decision_ts` are visible. Per competitor, the latest visible one is EXCLUDED_STALE if its age is over 72 h, otherwise EXCLUDED_LOW_CONFIDENCE if its confidence is below 0.80, otherwise USED. Older visible ones still inside 72 h are EXCLUDED_SUPERSEDED. `ref = median(USED)`, the mean of the middle two for an even count. Every considered observation goes to `price_decision_observation` with `age_hours`. | COMPETITOR_DATA_STALE when observations were considered but none is fresh |
| S5 Target | Manual override: the analyst's price. Else with a reference: `ref × (1 + position offset)`. Else the previous price, or `MRP × 0.95` for INITIAL. | MANUAL_OVERRIDE, COMPETITOR_ALIGN |
| S6 Demand / inventory | Stockout (on hand = 0): target = previous price, markdowns suppressed. Otherwise, with at least 7 days of history: cover > 60 days gives target × 0.97; cover < 7 days with velocity ≥ 1.2 gives target × 1.03. `cover = on_hand / max(d28 / n28, 0.1)`, `velocity = (d7 / n7) / (d28 / n28)`, which is 1 when there are no sales. | STOCKOUT_HOLD, OVERSTOCK_MARKDOWN, LOW_COVER_INCREASE |
| S7 Step limit | Bound to `[prev × 0.92, prev × 1.08]`. Not applied to INITIAL or a manual override. | STEP_LIMIT_APPLIED |
| S8 Clamp | `clamp(price, floor, ceiling)`. The floor always wins, so a cost increase can move the price past the step limit (PLAN.md: "except INITIAL and COST_CHANGE when needed to reach the floor"). | FLOOR_ENFORCED, CEILING_ENFORCED |
| S9 Price point | Round to the nearest whole rupee below ₹100, or the nearest rupee ending in 9 from ₹100 up. Ties go up. If the result is below the floor, take the next point up; if above the ceiling, the next point down. If no point fits, keep the candidate. | PRICE_POINT_ROUNDED |
| S10 Promotions | Markdowns (channel ALL) that are in their window and not cancelled by `decision_ts`, evaluated in `(priority, promotion_id)` order. Each is added greedily unless one of these rejects it: MIN_ORDER_QTY above 1 (a decision prices one unit), EXCLUSIVE, an EXCLUDES_PROMOTION pair, the stack count over the minimum MAX_STACK_COUNT, or the combined discount over the minimum MAX_COMBINED_DISCOUNT_PCT. Discounts apply sequentially on the running price, percentages first and then amounts, each rounded to the paisa. Every considered markdown goes to `price_decision_promotion`. | PROMO_APPLIED, PROMO_REJECTED_MIN_QTY / EXCLUSIVE / EXCLUDED_PAIR / STACK_LIMIT / DISCOUNT_CAP, PROMO_SUPPRESSED_STOCKOUT |
| S11 Margin | `net = list − Σ markdown discounts` (the shelf price). `margin_pct = (net − landed) / net`, always on net (`MarginCalculator`). It is required to be ≥ `min_promo_margin_pct` if any markdown applies, otherwise ≥ `min_margin_pct`. On failure the last-applied markdown is dropped and the margin recomputed. | PROMO_REJECTED_MARGIN |
| S12 Outcome | `decision_type` comes from the sign of `final − prev` (HOLD for INITIAL). If \|Δ\| > 10% of prev it is ESCALATED and a MERCH_ANALYST approves, otherwise APPROVED. Every decision ends APPLIED, and later SUPERSEDED. | LARGE_CHANGE_ESCALATED, NO_CHANGE (HOLD) |

**Reason codes** are listed in step order without duplicates. The primary reason is the first match in a fixed list:

- **When the price changed:** GUARDRAIL_CONFLICT_ESCALATED, MANUAL_OVERRIDE, INITIAL_PRICE, COST_CHANGE,
  FLOOR_ENFORCED, CEILING_ENFORCED, STOCKOUT_HOLD, LARGE_CHANGE_ESCALATED, STEP_LIMIT_APPLIED, OVERSTOCK_MARKDOWN,
  LOW_COVER_INCREASE, COMPETITOR_ALIGN, COMPETITOR_DATA_STALE, PRICE_POINT_ROUNDED.
- **When the price held:** GUARDRAIL_CONFLICT_ESCALATED, MANUAL_OVERRIDE, STOCKOUT_HOLD, COMPETITOR_DATA_STALE,
  NO_CHANGE.

`price_history.change_reason` is the primary reason on days the list price changed, and NO_CHANGE otherwise.

**Explanation:** `price_decision.explanation` is a JSON array of the 12 steps:
`{step, rule, inputs{…}, output, message}`. All values are strings, and the output is null when a step produced
nothing.

**Order coupons** use the same `PromotionStackResolver`, seeded with the day's applied markdowns, so exclusivity,
pairs, stack and cap rules count the markdowns. MIN_ORDER_QTY uses the order quantity. A coupon percentage applies to
the running line amount after markdowns. The order margin is not a rejection criterion (PLAN.md §8).

## Worked example

This example is `PricingEngineTest.workedExampleFromPricingRulesDoc`, so it cannot drift from the code.

**Decision:** weekly review, Monday 05-01-2026 06:00Z.

**Inputs:**

- **Product:** MRP ₹499, no MAP, `min_margin_pct` 0.25, `min_promo_margin_pct` 0.15, position MATCH.
- **Cost and price:** landed cost ₹300.00, previous list price ₹459.
- **Stock:** 50 units on hand, 2 units sold per day over 28 days (cover 25 days, velocity 1.0).
- **Competitor observations** (age at decision time):

  | Competitor | Price | Age | Confidence |
  |---|---|---|---|
  | ALPHA | ₹419 | 6 h | 0.92 |
  | ALPHA | ₹429 | 50 h | 0.95 |
  | BETA | ₹409 | 30 h | 0.88 |
  | GAMMA | ₹389 | 90 h | 0.99 |

- **Markdowns:**

  | Promotion | Discount | Priority | Rules |
  |---|---|---|---|
  | A | 10% | 10 | max stack 3, cap 40% |
  | B | ₹30 | 20 | max stack 3 |
  | C | 5% | 30 | EXCLUSIVE |

| Step | Computation | Result |
|---|---|---|
| S2 | 300 / (1 − 0.25) = 400.00 | floor ₹400.00 |
| S3 | MRP | ceiling ₹499.00, no conflict |
| S4 | ALPHA ₹419 (6 h) USED; ALPHA ₹429 (50 h) SUPERSEDED; BETA ₹409 (30 h) USED; GAMMA ₹389 (90 h) STALE. Median(419, 409) | ref ₹414.00 |
| S5 | MATCH: 414 × (1 + 0) | target ₹414.00 |
| S6 | cover 25 days, velocity 1.0 | no adjustment |
| S7 | 459 × 0.92 = 422.28 > 414 | ₹422.28, STEP_LIMIT_APPLIED |
| S8 | inside [400, 499] | ₹422.28 |
| S9 | nearest point: 419 (3.28 away) vs 429 (6.72 away) | final list ₹419.00, PRICE_POINT_ROUNDED |
| S10 | A: 419 × 0.10 = 41.90 → applied. B: ₹30, stack 2 ≤ 3, combined 71.90 / 419 = 17.2% ≤ 40% → applied. C: exclusive while A and B are applied → PROMO_REJECTED_EXCLUSIVE | net 419 − 41.90 − 30 = ₹347.10 |
| S11 | (347.10 − 300) / 347.10 = 13.6% < 15% → drop the last applied (B) → net 377.10, (377.10 − 300) / 377.10 = 0.2045 ≥ 0.15 | shelf ₹377.10, margin 0.2045, B PROMO_REJECTED_MARGIN |
| S12 | 419 vs 459: −8.7%, within 10% | DECREASE, APPROVED |

- **Stored outcome:** `markdown_discount_pct` 0.1000, `expected_net_price` 377.10.
- **Reason codes:** COMPETITOR_ALIGN | STEP_LIMIT_APPLIED | PRICE_POINT_ROUNDED | PROMO_APPLIED | PROMO_REJECTED_MARGIN |
  PROMO_REJECTED_EXCLUSIVE.
- **Primary reason:** STEP_LIMIT_APPLIED.
- **Bridges:** `price_decision_observation` gets 4 rows (fresh 2, stale 1). `price_decision_promotion` gets 3 rows
  (A APPLIED with `discount_pct_effective` 0.1000, B and C REJECTED).

## Known tension

When a guardrail conflict occurs, PLAN.md §8 S3 prices at the floor, which is above MRP. PLAN.md §2 assumption 4
treats MRP as a legal hard ceiling in India. The engine follows S3, escalates the decision, and an OPS_MANAGER
approves it. These are the only list prices above MRP in a clean dataset, and the validator and detector must treat
them as escalated operational exceptions, not anomalies.
