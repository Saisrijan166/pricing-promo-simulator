# Generator design

How `java -jar target/pricing-sim.jar generate` builds a dataset (PLAN.md §7). The generator is pure Java with no
Spring or database: `DatasetGenerator` writes the 16 data tables through a `RowSink`. The CLI writes CSV with
`CsvRowSink`; tests keep the rows in memory with `InMemoryRowSink`.

## Seed, streams and determinism

- **One seed.** The default is `generator.seed: 20261022`, overridden with `--seed`. Every draw comes from
  `RandomStreams.of(stream, key)`, which is `new java.util.Random(first 8 bytes of SHA-256(seed|stream|key))`.
  `java.util.Random` is specified bit for bit, so streams are identical on every JVM. `RandomStreamsTest` pins one
  value.
- **Independent streams.** Each stream belongs to one concern and, where possible, one SKU. Adding draws to one stream
  never shifts another, so one SKU's demand does not depend on the next SKU's, and anomaly injection (a later step)
  will not reshuffle the baseline.
- **No clock, no hash order.** There is no `Instant.now()` and no `Math.random`. Only `StrictMath` is used, and only
  ordered collections (`TreeMap`, `EnumMap`, sorted lists). `GeneratorSourceRulesTest` fails the build if generator or
  pricing code uses any of these. Money becomes `BigDecimal` via the exact binary value (`Amounts`), never via
  `Double.toString`.
- **Total-order IDs.** Every ID is assigned in a total order (PLAN.md §6). Each CSV is sorted by primary key before it
  is written, so the output never depends on emission order. `GeneratorDeterminismTest` checks that the same seed
  gives byte-identical files and a different seed gives different files.

| Stream | Key | Used for |
|---|---|---|
| `product-category`, `product-lifecycle` | — | Shuffling the exactly apportioned category and lifecycle mixes |
| `product` | SKU | Brand, subcategory, pack, MRP, margins, MAP, position, tracking, dates |
| `cost` | SKU | Markup, freight, monthly cost changes |
| `promotion-type` | — | Shuffling the promotion type mix |
| `promotion` | generation index | Window, targets, discount, rules, channel, priority, created time |
| `promotion-cancel` | — / promotion ID | Which promotions are cancelled, and when |
| `promotion-pair` | promotion ID | EXCLUDES_PROMOTION target |
| `competitor-coverage` | competitor | Share of tracked SKUs a competitor covers |
| `competitor-observation` | competitor\|SKU | Anchor, random walk, gaps, cadence, lag, confidence |
| `sku-latent` | SKU | Base demand, elasticity, reference price, fragile supply (never exported) |
| `demand` | SKU | Daily Poisson demand |
| `operations` | SKU | Initial stock, shrinkage, lead times |
| `decision` | SKU | Manual overrides, approval delays |
| `orders` | SKU | Order split, cancellations, time, channel, segment, customer, coupons |

## Scale profiles

These are set in `application.yml` under `generator.scales`. Every value can be overridden with `--skus`, `--days`,
`--promos`, `--start-date` and `--anomalies-per-type`. Starter and full use the same code path.

| Profile | SKUs | Days | Promotions | Anomalies / type | Competitors |
|---|---|---|---|---|---|
| tiny | 20 | 14 | 8 | 1 | 3 |
| starter | 200 | 60 (01-01-2026 to 01-03-2026) | 80 | 5 | 3 |
| full | 5,000 | 180 (01-01-2026 to 29-06-2026) | 2,000 | 50 | 3 |

`GenerationContext.checkCapacity()` rejects a scale that could overflow a fixed-width ID before anything is generated.

## Generation order

1. **Products** (`ProductGenerator`).
   - **Exact mixes:** categories are apportioned exactly by weight (Grocery 20%, Personal Care 12%, Beauty 8%,
     Health 6%, Home & Kitchen 14%, Electronics 8%, Apparel 12%, Toys 6%, Sports 6%, Stationery 8%), and so is the
     lifecycle (92% launched before the window, 5% launched in it, 3% discontinued in it).
   - **Per-category values:** MRP is lognormal per category and rounded to a price point. `min_margin_pct` comes
     from category bands.
   - **Other attributes:** `min_promo_margin_pct` = `min_margin_pct` − U(0.03, 0.08), at least 0.02; 10% of products
     have a MAP of 80–90% of MRP; 80% are competitor-tracked. Brands are fictional (`CategoryCatalog`).
   - **Status events:**
     - Launched before the window: ∅→ACTIVE on the launch date.
     - Launched in the window: ∅→NEW, then NEW→ACTIVE 14 days later.
     - Discontinued: a DISCONTINUED event at 00:00Z on the discontinuation date.
2. **Cost records** (`CostRecordGenerator`).
   - One per SKU per active month.
   - Initial landed cost = MRP / U(category markup band, within 1.4–2.6). Freight is 2–6% of unit cost.
   - Monthly change: 70% none, 22% ±1–5%, 4% freight change, 4% supplier shock of +10–20%.
   - Effective periods are contiguous: `effective_to` is the next record's `effective_from`.
3. **Promotions** (`PromotionGenerator`).
   - **Mix:** 60% markdowns (channel ALL, Monday to Sunday, 1–4 weeks) and 40% coupons (3–21 days, with a coupon
     code, channel ALL, ECOM or STORE).
   - **Targets:** a SKU list from one category or from any category. Markdown lists are 0.1–0.4% of SKUs (at least
     5), so 15–30% of SKU-days carry a markdown. Coupon lists are 20–150 SKUs.
   - **Rules:** every promotion has MAX_STACK_COUNT 2–3 and MAX_COMBINED_DISCOUNT_PCT 0.30–0.45. 30% are EXCLUSIVE.
     25% get an EXCLUDES_PROMOTION pair with an overlapping promotion that shares SKUs. 15% of coupons need
     MIN_ORDER_QTY 2.
   - **Lifecycle:** DRAFT → SCHEDULED → ACTIVE → EXPIRED. 3% are cancelled before they start and 1% while active. An
     active markdown is stopped at a Monday 00:00Z, so no shelf price keeps a cancelled markdown. Events after the
     last simulated day are not written, so the status is the as-of status.
4. **Competitor observations** (`CompetitorObservationGenerator`).
   - Only tracked SKUs, and only on their active days. Each competitor covers 40–80% of them.
   - The price is a weekly random walk (σ 1.5%) from MRP × U(0.80, 0.98).
   - Cadence: ALPHA every 1–2 days, BETA every 2–4 days, GAMMA weekly.
   - 8% of SKU-competitor pairs have a 7–20 day gap. Ingestion lag is exponential with a mean of 6 h; 2% are late
     feeds that arrive 2–5 days after capture.
   - Match confidence is 0.60–1.00, with 5% below 0.80.
5. **Market day loop** (`MarketSimulator`): `for day in window: for SKU in active SKUs by ID`.
   1. Receive replenishment (`receipts_units`).
   2. Price: `EnginePriceSetter` decides on decision days (see [pricing_rules.md](pricing_rules.md)). It writes
      `price_decision`, both decision bridges and the decision status events, then the day's `price_history` row.
   3. Demand: Poisson with mean `base × weekday factor (Sat/Sun 1.25) × (shelf / reference)^elasticity`. Sell
      `min(demand, available)`; the rest is lost sales.
   4. Orders (`OrderGenerator`):
      - Units sold are split into FULFILLED orders of 1–3 units, plus about 2% CANCELLED orders on top.
      - Orders are placed between 06:00 and 23:59:59Z. Channel is ECOM 55% / STORE 45%; segment is NEW / REGULAR /
        LOYALTY.
      - The day's markdowns are recorded on every order with `coupon_presented = false`. 12% of orders present a
        valid coupon (15% of those present a second), which `PromotionStackResolver` applies or rejects.
   5. Shrinkage of −1 unit with probability 0.5%.
   6. Replenishment (`ReplenishmentPolicy`, (s, S)): lead time 3–10 days, doubled for the 5% of SKUs with fragile
      supply.
   7. Write `inventory_snapshot`, with `days_of_cover`, `stockout_flag` and `lost_sales_units` derived.
6. **Finalise.**
   - Each SKU's last decision is written with status APPLIED; earlier ones were written as SUPERSEDED when replaced.
   - Every table is sorted by primary key and written.

Anomaly injection, ground truth, the manifest and checksums come in a later step (PLAN.md §9, §11).

## Derived, never drawn

These values are always computed from source records, never drawn at random:

- landed cost and `change_pct`;
- floor, ceiling, competitor reference and fresh/stale counts;
- `previous_list_price` and shelf price;
- the inventory flow, `days_of_cover`, `stockout_flag` and `lost_sales_units`;
- order gross, discount, net and margin;
- `decision_type`, `margin_pct` and `velocity_ratio`;
- final product, promotion and decision statuses (from their events).

`DerivedValuesTest` recomputes all of them from the generated rows.

## Memory

`CsvRowSink` keeps each row as a formatted line. A table that reaches 250,000 lines is sorted and spilled to a
temporary run file, and the runs are merged when the sink closes (an external merge sort). Spilled or not, the output
bytes are the same (`GeneratorDeterminismTest`). The simulator holds one `SkuState` per SKU and that day's decision
events.

At full scale, `generate` takes about 1 minute and also completes with `-Xmx768m`. The CSV output is about 760 MB,
of which `price_decision` is about 300 MB, mostly the explanation JSON.

## Starter run (seed 20261022)

| Table | Rows |
|---|---|
| product | 200 |
| product_status_event | 213 |
| cost_record | 587 |
| promotion / promotion_rule / promotion_product / promotion_status_event | 80 / 217 / 2,328 / 295 |
| competitor_observation | 7,143 |
| price_decision | 1,983 |
| price_decision_promotion / price_decision_observation / price_decision_status_event | 574 / 3,759 / 7,739 |
| price_history / inventory_snapshot | 11,525 each |
| customer_order / order_promotion | 13,217 / 3,768 |

Operational counts printed by `generate`: 109 decisions with COMPETITOR_DATA_STALE, 1 GUARDRAIL_CONFLICT_ESCALATED,
518 stockout SKU-days (4.5%) and 264 rejected coupons (21.9% of 1,203 presented).

Against the PLAN.md §7.5 realism bands:

| Metric | Value | Band |
|---|---|---|
| Stockout SKU-days | 4.5% | 2–6% |
| SKU-days under a markdown | 17.2% | 15–30% |
| Coupon presentations rejected | 21.9% | 10–30% |
| Realised gross margin | 39.8% | 15–40% |
| List-price changes per SKU-month | 1.9 | 0.5–3 |
| Top 20% of SKUs' share of units | 70% | 55–85% |
| Considered observations stale at decision time | 21% | 5–15% (**outside**) |

The stale share is above its band because GAMMA observes weekly, so its latest observation is older than 72 h on
about 4 of 7 days. The evaluation step will report this as a WARN unless the cadence or the band changes.
