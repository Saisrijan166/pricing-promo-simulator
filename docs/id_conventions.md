# ID conventions

All IDs are formatted and parsed by `com.pricingsim.domain.id.IdFactory`. The validator uses the regexes in
`IdPatterns`, and counters come from `SequenceCounter` (PLAN §6). Tests: `IdFactoryTest`.

## Patterns

| Entity | Pattern | Example | Regex (`IdPatterns`) | Counter scope | Assignment order |
|---|---|---|---|---|---|
| Product | `SKU-%06d` | `SKU-000001` | `^SKU-(\d{6})$` | global | generation index (category-shuffled, seeded) |
| CostRecord | `COST-<sku>-YYYY-MM` | `COST-SKU-000001-2026-01` | `^COST-(SKU-\d{6})-(\d{4})-(0[1-9]\|1[0-2])$` | natural key | — |
| PriceHistory | `PRC-<sku>-YYYYMMDD` | `PRC-SKU-000001-20260101` | `^PRC-(SKU-\d{6})-(\d{8})$` | natural key | — |
| InventorySnapshot | `INV-<sku>-YYYYMMDD` | `INV-SKU-000001-20260101` | `^INV-(SKU-\d{6})-(\d{8})$` | natural key | — |
| Promotion | `PROMO-YYYY-%06d` | `PROMO-2026-000001` | `^PROMO-(\d{4})-(\d{6})$` | per year | sorted by (start_date, promo_type, generation index) |
| PromotionRule | `RULE-YYYY-%06d` | `RULE-2026-000001` | `^RULE-(\d{4})-(\d{6})$` | per year | promotion_id order, then rule order |
| CompetitorObservation | `OBS-YYYY-%07d` | `OBS-2026-0000001` | `^OBS-(\d{4})-(\d{7})$` | per year | (source_captured_at, sku_id, competitor) |
| Order | `ORD-YYYY-%07d` | `ORD-2026-0000001` | `^ORD-(\d{4})-(\d{7})$` | per year | (order_date, sku_id, intra-day seq) |
| PriceDecision | `DEC-YYYY-%06d` | `DEC-2026-000001` | `^DEC-(\d{4})-(\d{6})$` | per year | (effective_date, sku_id) |
| Coupon code | `CPN-YYYY-%06d` | `CPN-2026-000001` | `^CPN-(\d{4})-(\d{6})$` | per year | promotion order |
| Product status event | `PEV-YYYY-%06d` | `PEV-2026-000001` | `^PEV-(\d{4})-(\d{6})$` | per year | (event_ts, parent id) |
| Promotion status event | `PMEV-YYYY-%06d` | `PMEV-2026-000001` | `^PMEV-(\d{4})-(\d{6})$` | per year | (event_ts, parent id) |
| Decision status event | `DEV-YYYY-%07d` | `DEV-2026-0000001` | `^DEV-(\d{4})-(\d{7})$` | per year | (event_ts, parent id) |
| Customer token | `CUST-%07d` | `CUST-0000001` | `^CUST-(\d{7})$` | global | opaque, no PII, no FK |
| Ground truth | `GT-YYYY-%06d` | `GT-2026-000001` | `^GT-(\d{4})-(\d{6})$` | per run | (issue_type, entity_id) |
| Detected exception | `EXC-YYYY-%07d` | `EXC-2026-0000001` | `^EXC-(\d{4})-(\d{7})$` | per run | (issue_type, entity_id) |

The regexes use only `^ $ \d {n} ( | )`, so the same text works in Java, PostgreSQL (`~`) and H2 (`REGEXP_LIKE`).
Capture groups expose the embedded fields.

## Rules

1. `<sku>` is always the **full** SKU ID: `COST-SKU-000001-2026-01`, not `COST-000001-2026-01`.
2. `YYYY` is the year of the record's business date. All data in the 2026 simulation window carries `2026`. A product
   launched before the window has its `∅→ACTIVE` status event on its launch date, so that event carries the launch
   year (for example `PEV-2023-000017`). Counters run separately per year.
3. Counters are zero-padded to a fixed width. `SequenceCounter` throws `IllegalStateException` on overflow, and
   `SequenceCounter.ensureCapacity(n)` lets the generator reject a scale before it starts. The validator re-checks
   capacity.
4. IDs are **immutable**. Anomaly injection changes attribute values only, never IDs.
5. All formatting and parsing goes through `IdFactory`. The validator uses the same `IdPatterns` regexes.
6. **ID-embedded field consistency**, checked by the validator:
   - the SKU and date inside `PRC` / `INV` IDs equal the `sku_id` and `price_date` / `snapshot_date` columns;
   - the SKU and month inside `COST` IDs equal `sku_id` and `cost_month`;
   - `customer_order.price_history_id` embeds the order's `sku_id` and `order_date`.
   `IdFactory.skuOf`, `IdFactory.costMonthOf` and `IdFactory.dateOf` extract these fields. Impossible calendar dates
   such as `20260230` are rejected.
7. Every assignment order above is a **total order**, so IDs are reproducible for a fixed seed.

## Capacity at full scale

| Sequence | Width | Capacity / year | Expected at full scale (PLAN §7.4) |
|---|---|---|---|
| SKU | 6 | 999,999 | 5,000 |
| PROMO / CPN | 6 | 999,999 | 2,000 |
| RULE | 6 | 999,999 | ~5,500 |
| OBS | 7 | 9,999,999 | ~400,000 |
| ORD | 7 | 9,999,999 | ~700,000 |
| DEC | 6 | 999,999 | ~150,000 |
| PEV / PMEV | 6 | 999,999 | ~5,500 / ~8,000 |
| DEV | 7 | 9,999,999 | ~450,000 |
| GT | 6 | 999,999 | 250 (50 × 5 types) |
| EXC | 7 | 9,999,999 | anomalies + operational exceptions |

## API

```java
IdFactory.sku(1);                                         // SKU-000001
IdFactory.costRecord("SKU-000001", YearMonth.of(2026, 1)); // COST-SKU-000001-2026-01
IdFactory.priceHistory("SKU-000001", LocalDate.of(2026, 1, 1)); // PRC-SKU-000001-20260101
IdFactory.decision(2026, 1);                              // DEC-2026-000001

SequenceCounter decisions = SequenceCounter.forSequence(IdFactory.Sequence.DECISION, 2026);
decisions.ensureCapacity(150_000);                        // fail fast before generating
decisions.nextId();                                       // DEC-2026-000001

IdFactory.skuOf("PRC-SKU-000873-20260209");               // SKU-000873
IdFactory.dateOf("PRC-SKU-000873-20260209");              // 2026-02-09
IdFactory.parseSequenceId(IdFactory.Sequence.DECISION, "DEC-2026-004512"); // (DECISION, 2026, 4512)
```
