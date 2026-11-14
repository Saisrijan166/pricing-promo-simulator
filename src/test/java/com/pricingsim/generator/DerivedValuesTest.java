package com.pricingsim.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.CostChangeReason;
import com.pricingsim.domain.enums.DecisionStatus;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.ObservationUsage;
import com.pricingsim.domain.enums.OrderStatus;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.CustomerOrder;
import com.pricingsim.domain.model.OrderPromotion;
import com.pricingsim.domain.model.PriceDecision;
import com.pricingsim.domain.model.PriceDecisionObservation;
import com.pricingsim.domain.model.PriceDecisionStatusEvent;
import com.pricingsim.domain.model.InventorySnapshot;
import com.pricingsim.domain.model.PriceHistory;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.sink.InMemoryRowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.pricing.GuardrailCalculator;
import com.pricingsim.pricing.PricePointRounder;

/**
 * Derived values reconcile with their source records in a clean generated starter dataset (PLAN.md §7.3, §12). Every
 * expected value is recomputed here from the rows themselves, the way the validator will.
 */
class DerivedValuesTest {

    private static GenerationSettings settings;
    private static Map<String, Product> products;
    private static Map<String, List<CostRecord>> costsBySku;
    private static Map<String, List<PriceHistory>> pricesBySku;
    private static Map<String, List<InventorySnapshot>> inventoryBySku;
    private static Map<String, PriceDecision> decisions;
    private static List<CustomerOrder> orders;
    private static Map<String, List<OrderPromotion>> orderPromotions;
    private static List<PriceDecisionObservation> decisionObservations;
    private static List<PriceDecisionStatusEvent> decisionEvents;

    @BeforeAll
    static void generateStarter() {
        settings = TestSettings.clean("starter"); // business rules hold everywhere in a clean run
        InMemoryRowSink sink = new InMemoryRowSink();
        new DatasetGenerator().generate(settings, sink);
        products = sink.<Product>rows(TableRegistry.PRODUCT).stream()
                .collect(Collectors.toMap(Product::skuId, p -> p, (a, b) -> a, TreeMap::new));
        // Rows come back in PK order, which is (sku, month) / (sku, date) for these natural-key IDs.
        costsBySku = groupBySku(sink.rows(TableRegistry.COST_RECORD), CostRecord::skuId);
        pricesBySku = groupBySku(sink.rows(TableRegistry.PRICE_HISTORY), PriceHistory::skuId);
        inventoryBySku = groupBySku(sink.rows(TableRegistry.INVENTORY_SNAPSHOT), InventorySnapshot::skuId);
        decisions = sink.<PriceDecision>rows(TableRegistry.PRICE_DECISION).stream()
                .collect(Collectors.toMap(PriceDecision::decisionId, d -> d, (a, b) -> a, TreeMap::new));
        orders = sink.rows(TableRegistry.CUSTOMER_ORDER);
        orderPromotions = groupBySku(sink.rows(TableRegistry.ORDER_PROMOTION), OrderPromotion::orderId);
        decisionObservations = sink.rows(TableRegistry.PRICE_DECISION_OBSERVATION);
        decisionEvents = sink.rows(TableRegistry.PRICE_DECISION_STATUS_EVENT);
    }

    // --- product ----------------------------------------------------------------------------------------------------

    @Test
    void productPolicyFieldsAreConsistent() {
        assertThat(products.values()).allSatisfy(p -> {
            assertThat(p.minPromoMarginPct()).isGreaterThanOrEqualTo(new BigDecimal("0.0200"))
                    .isLessThanOrEqualTo(p.minMarginPct());
            assertThat(p.minMarginPct()).isBetween(new BigDecimal("0.05"), new BigDecimal("0.60"));
            assertThat(p.mrp()).isPositive();
            assertThat(PricePointRounder.isPricePoint(p.mrp())).as("mrp is a price point").isTrue();
            if (p.mapPrice() != null) {
                assertThat(p.mapPrice()).isPositive().isLessThan(p.mrp());
            }
            assertThat(p.createdAt()).isBefore(p.launchDate().atStartOfDay(ZoneOffset.UTC).toInstant());
            if (p.discontinuedDate() != null) {
                assertThat(p.discontinuedDate()).isAfter(p.launchDate());
            }
        });
    }

    // --- cost_record ------------------------------------------------------------------------------------------------

    @Test
    void landedCostIsUnitPlusFreight() {
        forEachCost(c -> {
            assertThat(c.unitCost()).as(c.costRecordId()).isPositive();
            assertThat(c.freightCost()).as(c.costRecordId()).isNotNegative();
            assertThat(c.landedCost()).as(c.costRecordId()).isEqualByComparingTo(c.unitCost().add(c.freightCost()));
            assertThat(c.landedCost()).as("landed < mrp").isLessThan(products.get(c.skuId()).mrp());
        });
    }

    @Test
    void costPeriodsHaveNoGapsOrOverlaps() {
        assertThat(costsBySku.keySet()).containsExactlyElementsOf(products.keySet());
        costsBySku.forEach((sku, costs) -> {
            Product p = products.get(sku);
            YearMonth first = YearMonth.from(activeFrom(p));
            YearMonth last = YearMonth.from(activeTo(p));
            assertThat(costs).as(sku).hasSize((int) (first.until(last, java.time.temporal.ChronoUnit.MONTHS) + 1));
            for (int i = 0; i < costs.size(); i++) {
                CostRecord c = costs.get(i);
                YearMonth month = first.plusMonths(i);
                assertThat(c.costMonth()).isEqualTo(month.toString());
                assertThat(c.costRecordId()).isEqualTo(IdFactory.costRecord(sku, month));
                LocalDate expectedFrom = p.launchDate().isAfter(month.atDay(1)) ? p.launchDate() : month.atDay(1);
                assertThat(c.effectiveFrom()).as(c.costRecordId()).isEqualTo(expectedFrom);
                if (i + 1 < costs.size()) {
                    // effective_to is exclusive and equals the next record's effective_from: no gap, no overlap.
                    assertThat(c.effectiveTo()).as(c.costRecordId()).isEqualTo(costs.get(i + 1).effectiveFrom());
                    assertThat(c.effectiveTo()).isAfter(c.effectiveFrom());
                } else {
                    assertThat(c.effectiveTo()).as("latest record is open-ended").isNull();
                }
            }
        });
    }

    @Test
    void changePctAndReasonFollowLandedCost() {
        int shocks = 0;
        for (List<CostRecord> costs : costsBySku.values()) {
            for (int i = 0; i < costs.size(); i++) {
                CostRecord c = costs.get(i);
                if (i == 0) {
                    assertThat(c.changePct()).as(c.costRecordId()).isNull();
                    assertThat(c.changeReason()).isEqualTo(CostChangeReason.INITIAL);
                    continue;
                }
                BigDecimal prev = costs.get(i - 1).landedCost();
                BigDecimal expected = c.landedCost().divide(prev, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                        .setScale(4, RoundingMode.HALF_UP);
                assertThat(c.changePct()).as(c.costRecordId()).isEqualByComparingTo(expected);
                switch (c.changeReason()) {
                    case NO_CHANGE -> assertThat(c.changePct().signum()).as(c.costRecordId()).isZero();
                    case SUPPLIER_INCREASE -> assertThat(c.changePct().signum()).as(c.costRecordId()).isPositive();
                    case SUPPLIER_DECREASE -> assertThat(c.changePct().signum()).as(c.costRecordId()).isNegative();
                    case FREIGHT_CHANGE -> {
                        assertThat(c.changePct().signum()).as(c.costRecordId()).isNotZero();
                        assertThat(c.unitCost()).isEqualByComparingTo(costs.get(i - 1).unitCost());
                    }
                    case INITIAL -> throw new AssertionError("INITIAL on a later record: " + c.costRecordId());
                }
                if (c.changePct().compareTo(new BigDecimal("0.0950")) >= 0) {
                    shocks++;
                }
            }
        }
        assertThat(shocks).as("supplier shocks of +10–20% occur").isPositive();
    }

    // --- price_history ----------------------------------------------------------------------------------------------

    @Test
    void priceHistoryLinksToTheEffectiveCostAndPreviousDay() {
        pricesBySku.forEach((sku, prices) -> {
            Map<String, CostRecord> costById = costsBySku.get(sku).stream()
                    .collect(Collectors.toMap(CostRecord::costRecordId, c -> c));
            for (int i = 0; i < prices.size(); i++) {
                PriceHistory ph = prices.get(i);
                assertThat(IdFactory.skuOf(ph.priceHistoryId())).isEqualTo(sku);
                assertThat(IdFactory.dateOf(ph.priceHistoryId())).isEqualTo(ph.priceDate());
                CostRecord cost = costById.get(ph.costRecordId());
                assertThat(cost).as(ph.priceHistoryId()).isNotNull();
                assertThat(cost.costMonth()).isEqualTo(YearMonth.from(ph.priceDate()).toString());
                assertThat(ph.shelfPrice()).isLessThanOrEqualTo(ph.listPrice()).isPositive();
                if (i == 0) {
                    assertThat(ph.previousListPrice()).isNull();
                    assertThat(ph.changeReason()).isEqualTo(ReasonCode.INITIAL_PRICE);
                } else {
                    PriceHistory prev = prices.get(i - 1);
                    assertThat(ph.priceDate()).isEqualTo(prev.priceDate().plusDays(1));
                    assertThat(ph.previousListPrice()).isEqualByComparingTo(prev.listPrice());
                    boolean changed = ph.listPrice().compareTo(prev.listPrice()) != 0;
                    assertThat(ph.changeReason() == ReasonCode.NO_CHANGE).as(ph.priceHistoryId()).isEqualTo(!changed);
                }
            }
        });
    }

    @Test
    void initialPriceIsAPricePointBetweenFloorAndMrp() {
        pricesBySku.forEach((sku, prices) -> {
            Product p = products.get(sku);
            PriceHistory first = prices.get(0);
            CostRecord cost = costsBySku.get(sku).get(0);
            BigDecimal floor = cost.landedCost()
                    .divide(BigDecimal.ONE.subtract(p.minMarginPct()), 10, RoundingMode.HALF_UP)
                    .max(p.mapPrice() == null ? BigDecimal.ZERO : p.mapPrice())
                    .setScale(2, RoundingMode.CEILING);
            assertThat(first.listPrice()).as(sku).isLessThanOrEqualTo(p.mrp().max(floor))
                    .isGreaterThanOrEqualTo(floor);
            if (floor.compareTo(p.mrp()) <= 0 && PricePointRounder.pointAtOrAbove(floor).compareTo(p.mrp()) <= 0) {
                assertThat(PricePointRounder.isPricePoint(first.listPrice())).as(sku + " " + first.listPrice()).isTrue();
            }
        });
    }

    // --- inventory_snapshot -----------------------------------------------------------------------------------------

    @Test
    void inventoryFlowEquationHolds() {
        forEachSnapshot(s -> {
            assertThat(s.closingOnHand()).as(s.inventorySnapshotId())
                    .isEqualTo(s.openingOnHand() + s.receiptsUnits() - s.unitsSold() + s.adjustmentUnits());
            assertThat(s.closingOnHand()).isNotNegative();
            assertThat(s.openingOnHand()).isNotNegative();
            assertThat(s.receiptsUnits()).isNotNegative();
            assertThat(s.onOrderUnits()).isNotNegative();
            assertThat(s.adjustmentUnits()).isBetween(-1, 0);
            assertThat(s.unitsSold()).isBetween(0, s.demandUnits());
            assertThat(s.lostSalesUnits()).isEqualTo(s.demandUnits() - s.unitsSold());
            assertThat(s.stockoutFlag()).as(s.inventorySnapshotId())
                    .isEqualTo(s.closingOnHand() == 0 || s.lostSalesUnits() > 0);
            assertThat(IdFactory.skuOf(s.inventorySnapshotId())).isEqualTo(s.skuId());
            assertThat(IdFactory.dateOf(s.inventorySnapshotId())).isEqualTo(s.snapshotDate());
        });
    }

    @Test
    void openingEqualsPreviousClosing() {
        inventoryBySku.forEach((sku, rows) -> {
            for (int i = 1; i < rows.size(); i++) {
                assertThat(rows.get(i).snapshotDate()).isEqualTo(rows.get(i - 1).snapshotDate().plusDays(1));
                assertThat(rows.get(i).openingOnHand()).as(rows.get(i).inventorySnapshotId())
                        .isEqualTo(rows.get(i - 1).closingOnHand());
            }
        });
    }

    @Test
    void daysOfCoverUsesTrailing28DayAverageSales() {
        inventoryBySku.forEach((sku, rows) -> {
            for (int i = 0; i < rows.size(); i++) {
                int from = Math.max(0, i - 27);
                int sum = 0;
                for (int j = from; j <= i; j++) {
                    sum += rows.get(j).unitsSold();
                }
                int count = i - from + 1;
                int closing = rows.get(i).closingOnHand();
                // closing / max(sum / count, 0.1), evaluated exactly (a rounded average can flip the 2nd decimal).
                BigDecimal expected = sum * 10 <= count
                        ? BigDecimal.valueOf(closing * 10L).setScale(2, RoundingMode.UNNECESSARY)
                        : BigDecimal.valueOf((long) closing * count).divide(BigDecimal.valueOf(sum), 2,
                                RoundingMode.HALF_UP);
                assertThat(rows.get(i).daysOfCover()).as(rows.get(i).inventorySnapshotId())
                        .isEqualByComparingTo(expected);
            }
        });
    }

    @Test
    void inventoryAndPriceCoverTheSameSkuDays() {
        assertThat(inventoryBySku.keySet()).isEqualTo(pricesBySku.keySet());
        inventoryBySku.forEach((sku, rows) -> assertThat(rows).extracting(InventorySnapshot::snapshotDate)
                .containsExactlyElementsOf(pricesBySku.get(sku).stream().map(PriceHistory::priceDate).toList()));
    }

    @Test
    void stockoutsOccurWithinThePlanBand() {
        long skuDays = inventoryBySku.values().stream().mapToLong(List::size).sum();
        long stockouts = inventoryBySku.values().stream().flatMap(List::stream)
                .filter(InventorySnapshot::stockoutFlag).count();
        // PLAN.md §7.5 realism band: 2–6% of SKU-days.
        assertThat(stockouts / (double) skuDays).isBetween(0.02, 0.06);
    }

    // --- price_decision ---------------------------------------------------------------------------------------------

    @Test
    void everyPriceHistoryRowMatchesItsDecision() {
        pricesBySku.forEach((sku, prices) -> {
            for (PriceHistory ph : prices) {
                assertThat(ph.sourceDecisionId()).as(ph.priceHistoryId()).isNotNull();
                PriceDecision d = decisions.get(ph.sourceDecisionId());
                assertThat(d).as(ph.priceHistoryId()).isNotNull();
                assertThat(d.skuId()).isEqualTo(sku);
                assertThat(d.effectiveDate()).isBeforeOrEqualTo(ph.priceDate());
                assertThat(ph.listPrice()).as(ph.priceHistoryId()).isEqualByComparingTo(d.finalListPrice());
                assertThat(ph.shelfPrice()).as(ph.priceHistoryId()).isEqualByComparingTo(d.expectedNetPrice());
            }
        });
        // The decision in effect is the latest one on or before the date.
        Map<String, List<PriceDecision>> bySku = groupBySku(new ArrayList<>(decisions.values()), PriceDecision::skuId);
        pricesBySku.forEach((sku, prices) -> {
            for (PriceHistory ph : prices) {
                PriceDecision latest = bySku.get(sku).stream().filter(d -> !d.effectiveDate().isAfter(ph.priceDate()))
                        .reduce((a, b) -> b).orElseThrow();
                assertThat(ph.sourceDecisionId()).as(ph.priceHistoryId()).isEqualTo(latest.decisionId());
            }
        });
    }

    @Test
    void cleanRunHasNoPriceBelowFloorOrAboveMrp() {
        pricesBySku.forEach((sku, prices) -> {
            Product p = products.get(sku);
            for (PriceHistory ph : prices) {
                PriceDecision d = decisions.get(ph.sourceDecisionId());
                CostRecord decisionCost = costsBySku.get(sku).stream()
                        .filter(c -> c.costRecordId().equals(d.costRecordId())).findFirst().orElseThrow();
                BigDecimal floor = GuardrailCalculator.floor(decisionCost.landedCost(), p.minMarginPct(), p.mapPrice());
                assertThat(d.floorPrice()).as(d.decisionId()).isEqualByComparingTo(floor);
                assertThat(ph.listPrice()).as(ph.priceHistoryId() + " below floor").isGreaterThanOrEqualTo(floor);
                if (!d.reasonCodeList().contains(ReasonCode.GUARDRAIL_CONFLICT_ESCALATED)) {
                    // PLAN.md §8 S3: only an escalated guardrail conflict (floor > MRP) prices above MRP, at the floor.
                    assertThat(ph.listPrice()).as(ph.priceHistoryId() + " above MRP").isLessThanOrEqualTo(p.mrp());
                } else {
                    assertThat(ph.listPrice()).isEqualByComparingTo(floor);
                    assertThat(d.status()).isIn(DecisionStatus.APPLIED, DecisionStatus.SUPERSEDED);
                }
            }
        });
    }

    @Test
    void decisionStoredInputsReconcile() {
        Map<String, Long> fresh = decisionObservations.stream().filter(o -> o.usage() == ObservationUsage.USED)
                .collect(Collectors.groupingBy(PriceDecisionObservation::decisionId, TreeMap::new, Collectors.counting()));
        Map<String, Long> stale = decisionObservations.stream()
                .filter(o -> o.usage() == ObservationUsage.EXCLUDED_STALE)
                .collect(Collectors.groupingBy(PriceDecisionObservation::decisionId, TreeMap::new, Collectors.counting()));
        Map<String, List<PriceDecisionStatusEvent>> events = groupBySku(decisionEvents.stream()
                .sorted(java.util.Comparator.comparing(PriceDecisionStatusEvent::eventId)).toList(),
                PriceDecisionStatusEvent::decisionId);
        Map<String, Long> initials = new TreeMap<>();
        for (PriceDecision d : decisions.values()) {
            CostRecord cost = costsBySku.get(d.skuId()).stream()
                    .filter(c -> c.costRecordId().equals(d.costRecordId())).findFirst().orElseThrow();
            assertThat(cost.costMonth()).as(d.decisionId()).isEqualTo(YearMonth.from(d.effectiveDate()).toString());
            assertThat(d.landedCost()).isEqualByComparingTo(cost.landedCost());
            assertThat(d.ceilingPrice()).isEqualByComparingTo(products.get(d.skuId()).mrp());
            assertThat((long) d.competitorFreshCount()).as(d.decisionId()).isEqualTo(fresh.getOrDefault(d.decisionId(), 0L));
            assertThat((long) d.competitorStaleCount()).as(d.decisionId()).isEqualTo(stale.getOrDefault(d.decisionId(), 0L));
            assertThat(d.decisionTs()).isEqualTo(d.effectiveDate().atTime(6, 0).toInstant(ZoneOffset.UTC));
            assertThat(d.trigger() == DecisionTrigger.INITIAL).isEqualTo(d.previousDecisionId() == null);
            assertThat(d.reasonCodeList()).isNotEmpty().contains(d.primaryReasonCode());
            assertThat(d.ruleVersion()).isEqualTo("pricing-rules-v1");
            // Final status = last event; event IDs follow (event_ts, decision_id).
            List<PriceDecisionStatusEvent> ev = events.get(d.decisionId());
            assertThat(ev.get(0).fromStatus()).isNull();
            assertThat(ev.get(0).toStatus()).isEqualTo(DecisionStatus.PROPOSED);
            assertThat(ev.get(ev.size() - 1).toStatus()).as(d.decisionId()).isEqualTo(d.status());
            for (int i = 1; i < ev.size(); i++) {
                assertThat(ev.get(i).fromStatus()).isEqualTo(ev.get(i - 1).toStatus());
                assertThat(ev.get(i).eventTs()).isAfter(ev.get(i - 1).eventTs());
            }
            if (d.trigger() == DecisionTrigger.INITIAL) {
                initials.merge(d.skuId(), 1L, Long::sum);
            }
            if (d.previousDecisionId() != null) {
                assertThat(decisions.get(d.previousDecisionId()).status()).isEqualTo(DecisionStatus.SUPERSEDED);
            }
        }
        // Exactly one INITIAL decision per SKU, and the last decision of each SKU stays APPLIED.
        assertThat(initials.keySet()).isEqualTo(products.keySet());
        assertThat(initials.values()).containsOnly(1L);
        long applied = decisions.values().stream().filter(d -> d.status() == DecisionStatus.APPLIED).count();
        assertThat(applied).isEqualTo(products.size());
        List<String> eventIds = decisionEvents.stream()
                .sorted(java.util.Comparator.comparing(PriceDecisionStatusEvent::eventTs)
                        .thenComparing(PriceDecisionStatusEvent::decisionId))
                .map(PriceDecisionStatusEvent::eventId).toList();
        assertThat(eventIds).isSorted();
    }

    // --- customer_order ---------------------------------------------------------------------------------------------

    @Test
    void orderTotalsReconcile() {
        Map<String, PriceHistory> prc = new TreeMap<>();
        pricesBySku.values().forEach(l -> l.forEach(ph -> prc.put(ph.priceHistoryId(), ph)));
        assertThat(orders).isNotEmpty();
        for (CustomerOrder o : orders) {
            BigDecimal qty = BigDecimal.valueOf(o.quantity());
            PriceHistory ph = prc.get(o.priceHistoryId());
            assertThat(ph).as(o.orderId()).isNotNull();
            assertThat(ph.skuId()).isEqualTo(o.skuId());
            assertThat(ph.priceDate()).isEqualTo(o.orderDate());
            assertThat(o.orderTs().atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(o.orderDate());
            assertThat(o.costRecordId()).isEqualTo(ph.costRecordId());
            assertThat(o.quantity()).isBetween(1, 10);
            assertThat(o.unitListPrice()).isEqualByComparingTo(ph.listPrice());
            assertThat(o.grossAmount()).as(o.orderId()).isEqualByComparingTo(qty.multiply(o.unitListPrice()));
            List<OrderPromotion> promos = orderPromotions.getOrDefault(o.orderId(), List.of());
            BigDecimal applied = promos.stream().filter(p -> p.applicationStatus() == ApplicationStatus.APPLIED)
                    .map(OrderPromotion::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(o.discountAmount()).as(o.orderId()).isEqualByComparingTo(applied);
            assertThat(o.netAmount()).as(o.orderId()).isEqualByComparingTo(o.grossAmount().subtract(o.discountAmount()))
                    .isPositive();
            CostRecord cost = costsBySku.get(o.skuId()).stream()
                    .filter(c -> c.costRecordId().equals(o.costRecordId())).findFirst().orElseThrow();
            assertThat(o.unitLandedCost()).isEqualByComparingTo(cost.landedCost());
            assertThat(o.grossMarginAmount()).as(o.orderId())
                    .isEqualByComparingTo(o.netAmount().subtract(qty.multiply(o.unitLandedCost())));
            // Markdown rows (not presented by the customer) add up to qty × (list − shelf).
            BigDecimal markdown = promos.stream().filter(p -> !p.couponPresented())
                    .map(OrderPromotion::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(markdown).as(o.orderId())
                    .isEqualByComparingTo(qty.multiply(ph.listPrice().subtract(ph.shelfPrice())));
            for (OrderPromotion p : promos) {
                boolean rejected = p.applicationStatus() == ApplicationStatus.REJECTED;
                assertThat(p.rejectionReason() != null).as(o.orderId()).isEqualTo(rejected);
                if (rejected) {
                    assertThat(p.discountAmount()).isEqualByComparingTo("0.00");
                    assertThat(p.couponPresented()).isTrue();
                }
            }
            assertThat(promos).extracting(OrderPromotion::sequenceNo).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1, promos.size()).boxed().toList());
        }
    }

    @Test
    void unitsSoldEqualsFulfilledOrderQuantity() {
        Map<String, Integer> fulfilled = new TreeMap<>();
        for (CustomerOrder o : orders) {
            if (o.orderStatus() == OrderStatus.FULFILLED) {
                fulfilled.merge(IdFactory.inventorySnapshot(o.skuId(), o.orderDate()), o.quantity(), Integer::sum);
            }
        }
        forEachSnapshot(s -> assertThat(fulfilled.getOrDefault(s.inventorySnapshotId(), 0)).as(s.inventorySnapshotId())
                .isEqualTo(s.unitsSold()));
        long cancelled = orders.stream().filter(o -> o.orderStatus() == OrderStatus.CANCELLED).count();
        assertThat(cancelled / (double) orders.size()).isBetween(0.01, 0.03);
    }

    @Test
    void noOrdersOutsideTheActiveWindow() {
        for (CustomerOrder o : orders) {
            Product p = products.get(o.skuId());
            assertThat(o.orderDate()).as(o.orderId()).isBetween(activeFrom(p), activeTo(p));
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static LocalDate activeFrom(Product p) {
        return p.launchDate().isAfter(settings.windowStart()) ? p.launchDate() : settings.windowStart();
    }

    private static LocalDate activeTo(Product p) {
        LocalDate to = settings.windowEnd();
        if (p.discontinuedDate() != null && p.discontinuedDate().minusDays(1).isBefore(to)) {
            to = p.discontinuedDate().minusDays(1);
        }
        return to;
    }

    private static void forEachCost(java.util.function.Consumer<CostRecord> check) {
        costsBySku.values().forEach(list -> list.forEach(check));
    }

    private static void forEachSnapshot(java.util.function.Consumer<InventorySnapshot> check) {
        inventoryBySku.values().forEach(list -> list.forEach(check));
    }

    private static <T> Map<String, List<T>> groupBySku(List<T> rows, java.util.function.Function<T, String> sku) {
        Map<String, List<T>> out = new TreeMap<>();
        for (T row : rows) {
            out.computeIfAbsent(sku.apply(row), k -> new ArrayList<>()).add(row);
        }
        return out;
    }
}
