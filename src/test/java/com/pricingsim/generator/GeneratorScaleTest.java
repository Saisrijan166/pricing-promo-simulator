package com.pricingsim.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.pricingsim.config.ScaleProfile;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.PriceDecision;
import com.pricingsim.domain.model.Promotion;
import com.pricingsim.domain.model.PromotionProduct;
import com.pricingsim.domain.model.PromotionRule;
import com.pricingsim.domain.model.PromotionStatusEvent;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.InventorySnapshot;
import com.pricingsim.domain.model.PriceHistory;
import com.pricingsim.domain.model.Product;
import com.pricingsim.domain.model.ProductStatusEvent;
import com.pricingsim.generator.sink.InMemoryRowSink;
import com.pricingsim.io.TableRegistry;

/** Row counts follow the tiny profile (PLAN.md §7.1, §12). Expected counts are derived here from product dates. */
class GeneratorScaleTest {

    private static GenerationSettings settings;
    private static InMemoryRowSink sink;
    private static DatasetGenerator.Result result;
    private static List<Product> products;

    @BeforeAll
    static void generateTiny() {
        settings = TestSettings.forScale("tiny");
        sink = new InMemoryRowSink();
        result = new DatasetGenerator().generate(settings, sink);
        products = sink.rows(TableRegistry.PRODUCT);
    }

    @Test
    void profilesInApplicationYmlMatchPlan() {
        assertThat(TestSettings.properties().seed()).isEqualTo(20261022L);
        assertThat(TestSettings.properties().defaultScale()).isEqualTo("starter");
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        assertThat(TestSettings.properties().scale("tiny")).isEqualTo(new ScaleProfile(20, 14, 8, jan1, 1, 3));
        assertThat(TestSettings.properties().scale("starter")).isEqualTo(new ScaleProfile(200, 60, 80, jan1, 5, 3));
        assertThat(TestSettings.properties().scale("full")).isEqualTo(new ScaleProfile(5000, 180, 2000, jan1, 50, 3));
        assertThat(TestSettings.properties().scale("starter").endDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(TestSettings.properties().scale("full").endDate()).isEqualTo(LocalDate.of(2026, 6, 29));
        // pricing.policy.* in application.yml equals the PLAN.md §8 defaults.
        assertThat(TestSettings.policy()).isEqualTo(com.pricingsim.pricing.PricingPolicy.defaults());
    }

    @Test
    void writesAllSixteenDataTables() {
        assertThat(result.rowCounts().keySet())
                .containsExactlyElementsOf(TableRegistry.loadOrder().stream().map(t -> t.name()).toList())
                .hasSize(16);
        assertThat(result.rowCounts()).allSatisfy((table, rows) -> assertThat(rows).as(table).isPositive());
    }

    @Test
    void promotionsFollowTheProfile() {
        List<Promotion> promotions = sink.rows(TableRegistry.PROMOTION);
        assertThat(promotions).hasSize(settings.profile().promotions());
        // 60% markdowns / 40% coupons, apportioned exactly: 5 / 3 of 8.
        assertThat(promotions.stream().filter(p -> p.couponCode() == null).count()).isEqualTo(5);
        Map<String, Long> rules = sink.<PromotionRule>rows(TableRegistry.PROMOTION_RULE).stream()
                .collect(Collectors.groupingBy(PromotionRule::promotionId, TreeMap::new, Collectors.counting()));
        Map<String, Long> skus = sink.<PromotionProduct>rows(TableRegistry.PROMOTION_PRODUCT).stream()
                .collect(Collectors.groupingBy(PromotionProduct::promotionId, TreeMap::new, Collectors.counting()));
        for (Promotion p : promotions) {
            assertThat(rules.get(p.promotionId())).as(p.promotionId()).isGreaterThanOrEqualTo(2L);
            assertThat(skus.get(p.promotionId())).as(p.promotionId()).isPositive();
            if (p.couponCode() == null) {
                assertThat(p.startDate().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
                assertThat(p.endDate().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
                assertThat(p.channel()).isEqualTo(com.pricingsim.domain.enums.Channel.ALL);
            }
            assertThat(p.createdAt()).isBefore(p.startDate().atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        // PROMO IDs follow (start_date, promo_type).
        List<Promotion> byId = promotions.stream().sorted(java.util.Comparator.comparing(Promotion::promotionId)).toList();
        assertThat(byId).isSortedAccordingTo(java.util.Comparator.comparing(Promotion::startDate)
                .thenComparing(Promotion::promoType));
        // Final status = last status event.
        Map<String, PromotionStatusEvent> last = new TreeMap<>();
        sink.<PromotionStatusEvent>rows(TableRegistry.PROMOTION_STATUS_EVENT).stream()
                .sorted(java.util.Comparator.comparing(PromotionStatusEvent::eventTs))
                .forEach(e -> last.put(e.promotionId(), e));
        assertThat(promotions).allSatisfy(p -> assertThat(last.get(p.promotionId()).toStatus()).isEqualTo(p.status()));
    }

    @Test
    void oneInitialDecisionPerSkuOnItsFirstActiveDay() {
        List<PriceDecision> decisions = sink.rows(TableRegistry.PRICE_DECISION);
        Map<String, PriceDecision> initial = decisions.stream().filter(d -> d.trigger() == DecisionTrigger.INITIAL)
                .collect(Collectors.toMap(PriceDecision::skuId, d -> d, (a, b) -> a, TreeMap::new));
        assertThat(initial).hasSize(products.size());
        for (Product p : products) {
            assertThat(initial.get(p.skuId()).effectiveDate()).isEqualTo(activeFrom(p));
        }
        assertThat(decisions).allSatisfy(d -> assertThat(d.effectiveDate().getDayOfWeek() == java.time.DayOfWeek.MONDAY
                || d.trigger() != DecisionTrigger.WEEKLY_REVIEW).isTrue());
    }

    @Test
    void productCountAndLifecycleMix() {
        assertThat(products).hasSize(settings.profile().skus());
        assertThat(products).extracting(Product::skuId)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 20)
                        .mapToObj(i -> String.format("SKU-%06d", i)).toList());
        // 92% / 5% / 3% apportioned exactly: 18 / 1 / 1 for 20 SKUs.
        long launchedInWindow = products.stream().filter(p -> !p.launchDate().isBefore(start())).count();
        long discontinued = products.stream().filter(p -> p.discontinuedDate() != null).count();
        assertThat(launchedInWindow).isEqualTo(1);
        assertThat(discontinued).isEqualTo(1);
        assertThat(products).allSatisfy(p -> assertThat(p.launchDate()).isBeforeOrEqualTo(end()));
    }

    @Test
    void statusEventCountFollowsLifecycle() {
        long expected = 0;
        for (Product p : products) {
            if (p.discontinuedDate() != null) {
                expected += 2; // ∅→ACTIVE, ACTIVE→DISCONTINUED
            } else if (!p.launchDate().isBefore(start())) {
                expected += p.launchDate().plusDays(14).isAfter(end()) ? 1 : 2; // ∅→NEW (, NEW→ACTIVE)
            } else {
                expected += 1; // ∅→ACTIVE
            }
        }
        List<ProductStatusEvent> events = sink.rows(TableRegistry.PRODUCT_STATUS_EVENT);
        assertThat(events).hasSize((int) expected);
        // Final status equals the last event per SKU.
        Map<String, ProductStatusEvent> last = new TreeMap<>();
        events.stream().sorted(java.util.Comparator.comparing(ProductStatusEvent::eventTs))
                .forEach(e -> last.put(e.skuId(), e));
        assertThat(products).allSatisfy(p -> assertThat(last.get(p.skuId()).toStatus()).isEqualTo(p.status()));
    }

    @Test
    void oneCostRecordPerSkuPerActiveMonth() {
        long expected = products.stream().mapToLong(p ->
                ChronoUnit.MONTHS.between(YearMonth.from(activeFrom(p)), YearMonth.from(activeTo(p))) + 1).sum();
        List<CostRecord> costs = sink.rows(TableRegistry.COST_RECORD);
        assertThat(costs).hasSize((int) expected);
        // Tiny is 01-01 → 14-01: one month per SKU.
        assertThat(costs).extracting(CostRecord::costMonth).containsOnly("2026-01");
    }

    @Test
    void everySkuHasExactlyItsActiveDaysOfPriceAndInventory() {
        long expected = products.stream().mapToLong(p -> ChronoUnit.DAYS.between(activeFrom(p), activeTo(p)) + 1).sum();
        List<PriceHistory> prices = sink.rows(TableRegistry.PRICE_HISTORY);
        List<InventorySnapshot> inventory = sink.rows(TableRegistry.INVENTORY_SNAPSHOT);
        assertThat(prices).hasSize((int) expected);
        assertThat(inventory).hasSize((int) expected);
        assertThat(result.simulation().skuDays()).isEqualTo(expected);

        Map<String, List<LocalDate>> priceDays = prices.stream().collect(Collectors.groupingBy(PriceHistory::skuId,
                TreeMap::new, Collectors.mapping(PriceHistory::priceDate, Collectors.toList())));
        Map<String, List<LocalDate>> stockDays = inventory.stream().collect(Collectors.groupingBy(
                InventorySnapshot::skuId, TreeMap::new,
                Collectors.mapping(InventorySnapshot::snapshotDate, Collectors.toList())));
        for (Product p : products) {
            List<LocalDate> days = activeFrom(p).datesUntil(activeTo(p).plusDays(1)).toList();
            assertThat(priceDays.get(p.skuId())).as(p.skuId()).containsExactlyElementsOf(days);
            assertThat(stockDays.get(p.skuId())).as(p.skuId()).containsExactlyElementsOf(days);
        }
    }

    @Test
    void observationsOnlyForTrackedSkusOnActiveDays() {
        Map<String, Product> bySku = products.stream()
                .collect(Collectors.toMap(Product::skuId, p -> p, (a, b) -> a, TreeMap::new));
        List<CompetitorObservation> observations = sink.rows(TableRegistry.COMPETITOR_OBSERVATION);
        assertThat(observations).isNotEmpty();
        for (CompetitorObservation o : observations) {
            Product p = bySku.get(o.skuId());
            assertThat(p.competitorTracked()).as(o.observationId()).isTrue();
            LocalDate captured = o.sourceCapturedAt().atZone(ZoneOffset.UTC).toLocalDate();
            assertThat(captured).isBetween(activeFrom(p), activeTo(p));
            assertThat(o.ingestedAt()).isAfterOrEqualTo(o.sourceCapturedAt());
        }
        // At most one observation per SKU, competitor and day.
        long upperBound = products.stream().filter(Product::competitorTracked)
                .mapToLong(p -> 3L * (ChronoUnit.DAYS.between(activeFrom(p), activeTo(p)) + 1)).sum();
        assertThat((long) observations.size()).isLessThanOrEqualTo(upperBound);
    }

    @Test
    void starterRowCountsFollowTheProfile() {
        GenerationSettings starter = TestSettings.clean("starter");
        InMemoryRowSink s = new InMemoryRowSink();
        new DatasetGenerator().generate(starter, s);
        List<Product> ps = s.rows(TableRegistry.PRODUCT);
        assertThat(ps).hasSize(200);
        assertThat(s.count(TableRegistry.PROMOTION)).isEqualTo(80);
        LocalDate start = starter.windowStart();
        LocalDate end = starter.windowEnd();
        long days = 0;
        long months = 0;
        for (Product p : ps) {
            LocalDate from = p.launchDate().isAfter(start) ? p.launchDate() : start;
            LocalDate to = p.discontinuedDate() != null && p.discontinuedDate().minusDays(1).isBefore(end)
                    ? p.discontinuedDate().minusDays(1) : end;
            days += ChronoUnit.DAYS.between(from, to) + 1;
            months += ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;
        }
        assertThat(s.count(TableRegistry.PRICE_HISTORY)).isEqualTo(days);
        assertThat(s.count(TableRegistry.INVENTORY_SNAPSHOT)).isEqualTo(days);
        assertThat(s.count(TableRegistry.COST_RECORD)).isEqualTo(months);
        assertThat(s.<CostRecord>rows(TableRegistry.COST_RECORD)).extracting(CostRecord::costMonth)
                .containsOnly("2026-01", "2026-02", "2026-03");
        // 92% / 5% / 3% of 200 SKUs.
        assertThat(ps.stream().filter(p -> !p.launchDate().isBefore(start)).count()).isEqualTo(10);
        assertThat(ps.stream().filter(p -> p.discontinuedDate() != null).count()).isEqualTo(6);
    }

    @Test
    void rowCountsReportedMatchSink() {
        for (Map.Entry<String, Long> e : result.rowCounts().entrySet()) {
            assertThat(e.getValue()).as(e.getKey()).isEqualTo(sink.count(TableRegistry.byName(e.getKey())));
        }
    }

    private static LocalDate start() {
        return settings.windowStart();
    }

    private static LocalDate end() {
        return settings.windowEnd();
    }

    private static LocalDate activeFrom(Product p) {
        return p.launchDate().isAfter(start()) ? p.launchDate() : start();
    }

    private static LocalDate activeTo(Product p) {
        LocalDate to = end();
        if (p.discontinuedDate() != null && p.discontinuedDate().minusDays(1).isBefore(to)) {
            to = p.discontinuedDate().minusDays(1);
        }
        return to;
    }
}
