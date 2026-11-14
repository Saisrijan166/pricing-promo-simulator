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

import com.pricingsim.domain.enums.CostChangeReason;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.InventorySnapshot;
import com.pricingsim.domain.model.PriceHistory;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.sink.InMemoryRowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.pricing.PricePointRounder;

/**
 * Derived values reconcile with their source records in a generated starter dataset (PLAN.md §7.3, §12). Every
 * expected value is recomputed here from the rows themselves, the way the validator will.
 */
class DerivedValuesTest {

    private static GenerationSettings settings;
    private static Map<String, Product> products;
    private static Map<String, List<CostRecord>> costsBySku;
    private static Map<String, List<PriceHistory>> pricesBySku;
    private static Map<String, List<InventorySnapshot>> inventoryBySku;

    @BeforeAll
    static void generateStarter() {
        settings = TestSettings.forScale("starter");
        InMemoryRowSink sink = new InMemoryRowSink();
        new DatasetGenerator().generate(settings, sink);
        products = sink.<Product>rows(TableRegistry.PRODUCT).stream()
                .collect(Collectors.toMap(Product::skuId, p -> p, (a, b) -> a, TreeMap::new));
        // Rows come back in PK order, which is (sku, month) / (sku, date) for these natural-key IDs.
        costsBySku = groupBySku(sink.rows(TableRegistry.COST_RECORD), CostRecord::skuId);
        pricesBySku = groupBySku(sink.rows(TableRegistry.PRICE_HISTORY), PriceHistory::skuId);
        inventoryBySku = groupBySku(sink.rows(TableRegistry.INVENTORY_SNAPSHOT), InventorySnapshot::skuId);
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
