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
import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.CostRecord;
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
    }

    @Test
    void writesExactlyTheMasterTables() {
        assertThat(result.rowCounts().keySet()).containsExactlyInAnyOrder("product", "product_status_event",
                "cost_record", "competitor_observation");
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
