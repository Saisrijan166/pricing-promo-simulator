# Anomalies and ground truth

The generator first builds a valid baseline, then injects five labelled edge-case types into it (PLAN.md §9). The
labels go to `labels/` only. The validator and detector find the anomalies without reading the labels, and only
`evaluate` compares the two.

Code: `com.pricingsim.generator.anomaly` (`CandidateCollector`, `AnomalyPlanner`, `AnomalyPlan`, `AnomalyInjector`,
`GroundTruthRecord`, `GroundTruthFiles`). The simulator calls them through `generator.sim.SimulationHooks`, and the
pricing engine's `Faults` reproduce the defects with consistent arithmetic. Tests: `AnomalyInjectionTest`,
`NoLeakageTest`.

## How targets are chosen

1. **Dry run.** With `--anomalies-per-type N > 0`, a baseline run is simulated first and its rows are discarded.
   `CandidateCollector` records every record eligible for each type, by natural key (SKU, date and, for orders, the
   order's position that day).
2. **Planning.** `AnomalyPlanner` shuffles each type's candidates with its own `anomaly` RNG stream and takes N on
   distinct SKUs, scarcest type first. Each SKU gets at most one anomaly. Because every SKU draws from its own
   streams, its data up to the injection point is identical to the dry run, so the planned target exists in the real
   run.
3. **Injection.** The real run injects at the planned points. Downstream effects stay within the anomalous SKU and are
   intended causal effects, not corruption: for example, a below-floor price raises that SKU's demand.
   `AnomalyInjectionTest.skusWithoutAnAnomalyAreUntouched` checks that every other SKU's inventory and prices equal the
   clean run.
4. **Clean run.** `--anomalies-per-type 0` skips steps 1–3. The dataset is the clean baseline and the label files have
   no records.

IDs never change: injection changes attribute values, never identifiers (PLAN.md §6 rule 4).

## The five types

| # | issue_type | Eligible target | Injection (arithmetic kept consistent) | Detection rule (no labels) | expected_action |
|---|---|---|---|---|---|
| 1 | `PRICE_BELOW_FLOOR` | An engine WEEKLY_REVIEW decision without a guardrail conflict | Recorded as an analyst `MANUAL_OVERRIDE` (source and primary reason) at the price point at or below `floor × U(0.85, 0.97)`. The step limit and clamp are skipped. Margin, markdowns and escalation are recomputed. The price-history days it covers carry the price, and their orders use it. About 1% of baseline weekly reviews are legitimate manual overrides inside the guardrails, so the source field is not a giveaway. | The floor, recomputed from the decision's cost record and the product, is above `final_list_price` | `BLOCK_PRICE` |
| 2 | `PROMO_STACK_VIOLATION` | An order where a presented coupon was rejected for a stacking conflict (exclusive, excluded pair, stack limit or cap) and forcing it keeps the net amount positive | That coupon becomes APPLIED. Every applied coupon's discount (percent first, then amount, on the running amount after markdowns), the order discount, the net amount and the gross margin are recomputed. | Re-run the stacking rules on each order's APPLIED set: exclusivity, excluded pairs, stack count, combined cap, minimum quantity | `REJECT_PROMOTION` |
| 3 | `STALE_OBSERVATION_AS_CURRENT` | A decision that considered at least one EXCLUDED_STALE observation | The first stale observation (by ID) is USED. The reference price, fresh and stale counts, target, final price, markdowns and margin all follow it. `age_hours` keeps the real age. | A `price_decision_observation` row is USED although `decision_ts − source_captured_at > 72 h` | `EXCLUDE_OBSERVATION` |
| 4 | `MARGIN_BEFORE_DISCOUNT` | A decision that rejected a markdown with `PROMO_REJECTED_MARGIN`, where re-applying it keeps the net price positive | The margin is judged on the list price, so the markdown stays applied. Net and shelf price follow, and `margin_pct` is stored as `(list − landed) / list`. | The margin recomputed from `expected_net_price` differs from the stored `margin_pct`, and it is below `required_margin_pct` | `RECOMPUTE_MARGIN_ON_NET` |
| 5 | `PRICE_CHANGE_WITHOUT_REASON` | A Tuesday–Thursday with no decision, where a ±8% move fits inside [floor, MRP] in at least one direction | The list price moves ±U(3%, 8%), using the direction that stays inside [floor, MRP], until the next decision. Markdowns are recomputed on the new price. Those days have `change_reason` and `source_decision_id` = null, and their orders use that price. | A `price_history` row whose price differs from the previous day and that has no reason or no decision; only the first row of the span is flagged | `REQUIRE_REASON` |

## Label files

`labels/ground_truth.csv` follows the same CSV rules as the data files. `related_ids` is pipe-delimited.

```
gt_id, issue_type, entity_type, entity_id, sku_id, event_date,
related_ids, field_name, baseline_value, injected_value,
violated_rule, expected_detection, expected_action
```

| issue_type | entity_type / entity_id | related_ids | field_name: baseline → injected |
|---|---|---|---|
| PRICE_BELOW_FLOOR | price_decision / DEC-… | cost record, then every price-history row the decision covers | `final_list_price`: engine price → injected price |
| PROMO_STACK_VIOLATION | customer_order / ORD-… | forced promotion, the order's price-history row | `order_promotion.application_status`: REJECTED → APPLIED |
| STALE_OBSERVATION_AS_CURRENT | price_decision / DEC-… | cost record, the stale observation | `price_decision_observation.usage`: EXCLUDED_STALE → USED |
| MARGIN_BEFORE_DISCOUNT | price_decision / DEC-… | cost record, the re-applied markdown(s) | `margin_pct`: correct net-basis margin of the stored net price → stored list-basis margin |
| PRICE_CHANGE_WITHOUT_REASON | price_history / PRC-… (first day) | the decision that was in effect, then the span's later price-history rows | `list_price`: decision price → injected price |

GT IDs (`GT-YYYY-%06d`) follow (issue_type, entity_id). Detection is matched on (issue_type, entity_type, entity_id).

`labels/ground_truth.json` holds the same records as
`{"seed", "scale", "counts_by_issue_type", "records": [...]}`.

Starter (seed 20261022, 5 per type) gives 25 labels on 25 distinct SKUs.

## Leakage controls

These are checked by `NoLeakageTest`.

1. Labels are written only to `labels/`, never to `csv/`. `TableRegistry` has no label table, so the loader, which
   loads exactly the registry tables, cannot load labels.
2. No feature CSV header has a label-like column (`issue_type`, `gt_id`, `label`, `anomaly`, `injected`, …).
3. The `detection` and `validation` sources never import `generator.anomaly` and never mention `labels/` or the
   ground-truth files.
4. Injected records carry no flag or sentinel value, and no GT ID or rule name appears in any feature file.
5. Only `evaluate` reads the labels. The API never exposes them.
