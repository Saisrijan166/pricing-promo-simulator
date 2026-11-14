package com.pricingsim.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.pricingsim.config.ScaleProfile;
import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.DecisionSource;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.domain.enums.ObservationUsage;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.CustomerOrder;
import com.pricingsim.domain.model.InventorySnapshot;
import com.pricingsim.domain.model.OrderPromotion;
import com.pricingsim.domain.model.PriceDecision;
import com.pricingsim.domain.model.PriceDecisionObservation;
import com.pricingsim.domain.model.PriceHistory;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.anomaly.AnomalyPlanner;
import com.pricingsim.generator.anomaly.GroundTruthRecord;
import com.pricingsim.generator.sink.InMemoryRowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.pricing.GuardrailCalculator;
import com.pricingsim.pricing.MarginCalculator;

/**
 * Anomaly injection (PLAN.md §9, §12): exactly N labels per type, every labelled record exists and breaks exactly its
 * rule with consistent arithmetic, and records of SKUs without an anomaly are untouched.
 */
class AnomalyInjectionTest {

    private static InMemoryRowSink injected;
    private static InMemoryRowSink clean;
    private static List<GroundTruthRecord> labels;

    @BeforeAll
    static void generate() {
        GenerationSettings starter = TestSettings.forScale("starter");
        injected = new InMemoryRowSink();
        labels = new DatasetGenerator().generate(starter, injected).groundTruth();
        ScaleProfile p = starter.profile();
        GenerationSettings zero = new GenerationSettings(starter.seed(), "starter",
                p.withOverrides(null, null, null, null, 0), starter.referenceTs(), starter.policy(), List.of());
        clean = new InMemoryRowSink();
        assertThat(new DatasetGenerator().generate(zero, clean).groundTruth()).isEmpty();
    }

    private static <T, K> Map<K, T> index(List<T> rows, Function<T, K> key) {
        return rows.stream().collect(Collectors.toMap(key, r -> r, (a, b) -> a, TreeMap::new));
    }

    private static List<GroundTruthRecord> of(IssueType type) {
        return labels.stream().filter(l -> l.issueType() == type).toList();
    }

    @Test
    void exactlyNLabelsPerTypeWithOrderedIds() {
        for (IssueType type : AnomalyPlanner.TYPES) {
            assertThat(of(type)).as(type.name()).hasSize(5);
        }
        assertThat(labels).hasSize(25);
        assertThat(labels).extracting(GroundTruthRecord::gtId)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 25)
                        .mapToObj(i -> String.format("GT-2026-%06d", i)).toList());
        assertThat(labels).extracting(GroundTruthRecord::skuId).doesNotHaveDuplicates();
        assertThat(labels).allSatisfy(l -> {
            assertThat(l.expectedDetection()).isTrue();
            assertThat(l.violatedRule()).isNotBlank();
            assertThat(l.baselineValue()).isNotEqualTo(l.injectedValue());
        });
    }

    @Test
    void priceBelowFloorDecisionsAndTheirDays() {
        Map<String, PriceDecision> decisions = index(injected.rows(TableRegistry.PRICE_DECISION), PriceDecision::decisionId);
        Map<String, Product> products = index(injected.rows(TableRegistry.PRODUCT), Product::skuId);
        Map<String, CostRecord> costs = index(injected.rows(TableRegistry.COST_RECORD), CostRecord::costRecordId);
        Map<String, PriceHistory> prices = index(injected.rows(TableRegistry.PRICE_HISTORY), PriceHistory::priceHistoryId);
        for (GroundTruthRecord l : of(IssueType.PRICE_BELOW_FLOOR)) {
            PriceDecision d = decisions.get(l.entityId());
            Product p = products.get(d.skuId());
            BigDecimal floor = GuardrailCalculator.floor(costs.get(d.costRecordId()).landedCost(), p.minMarginPct(),
                    p.mapPrice());
            assertThat(d.finalListPrice()).as(l.gtId()).isLessThan(floor);
            assertThat(d.floorPrice()).isEqualByComparingTo(floor);
            assertThat(d.decisionSource()).isEqualTo(DecisionSource.MANUAL_OVERRIDE);
            assertThat(d.primaryReasonCode()).isEqualTo(ReasonCode.MANUAL_OVERRIDE);
            assertThat(d.finalListPrice().toPlainString()).isEqualTo(l.injectedValue());
            List<String> covered = l.relatedIds().stream().filter(id -> id.startsWith("PRC-")).toList();
            assertThat(covered).isNotEmpty();
            for (String id : covered) {
                assertThat(prices.get(id).listPrice()).isEqualByComparingTo(d.finalListPrice());
                assertThat(prices.get(id).sourceDecisionId()).isEqualTo(d.decisionId());
            }
        }
    }

    @Test
    void promoStackViolationOrdersStayArithmeticallyConsistent() {
        Map<String, CustomerOrder> orders = index(injected.rows(TableRegistry.CUSTOMER_ORDER), CustomerOrder::orderId);
        Map<String, List<OrderPromotion>> promos = injected.<OrderPromotion>rows(TableRegistry.ORDER_PROMOTION).stream()
                .collect(Collectors.groupingBy(OrderPromotion::orderId, TreeMap::new, Collectors.toList()));
        for (GroundTruthRecord l : of(IssueType.PROMO_STACK_VIOLATION)) {
            CustomerOrder o = orders.get(l.entityId());
            String promotionId = l.relatedIds().get(0);
            OrderPromotion forced = promos.get(o.orderId()).stream()
                    .filter(p -> p.promotionId().equals(promotionId)).findFirst().orElseThrow();
            assertThat(forced.applicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
            assertThat(forced.couponPresented()).isTrue();
            assertThat(forced.rejectionReason()).isNull();
            assertThat(forced.discountAmount()).isPositive();
            BigDecimal applied = promos.get(o.orderId()).stream()
                    .filter(p -> p.applicationStatus() == ApplicationStatus.APPLIED)
                    .map(OrderPromotion::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(o.discountAmount()).isEqualByComparingTo(applied);
            assertThat(o.netAmount()).isEqualByComparingTo(o.grossAmount().subtract(applied)).isPositive();
            assertThat(o.grossMarginAmount()).isEqualByComparingTo(
                    o.netAmount().subtract(o.unitLandedCost().multiply(BigDecimal.valueOf(o.quantity()))));
        }
    }

    @Test
    void staleObservationsUsedAsCurrent() {
        Map<String, PriceDecision> decisions = index(injected.rows(TableRegistry.PRICE_DECISION), PriceDecision::decisionId);
        List<PriceDecisionObservation> bridge = injected.rows(TableRegistry.PRICE_DECISION_OBSERVATION);
        for (GroundTruthRecord l : of(IssueType.STALE_OBSERVATION_AS_CURRENT)) {
            String obsId = l.relatedIds().stream().filter(id -> id.startsWith("OBS-")).findFirst().orElseThrow();
            PriceDecisionObservation row = bridge.stream().filter(b -> b.decisionId().equals(l.entityId())
                    && b.observationId().equals(obsId)).findFirst().orElseThrow();
            assertThat(row.usage()).isEqualTo(ObservationUsage.USED);
            assertThat(row.ageHours()).isGreaterThan(new BigDecimal("72"));
            long used = bridge.stream().filter(b -> b.decisionId().equals(l.entityId())
                    && b.usage() == ObservationUsage.USED).count();
            assertThat((long) decisions.get(l.entityId()).competitorFreshCount()).isEqualTo(used);
            assertThat(decisions.get(l.entityId()).competitorRefPrice()).isNotNull();
        }
    }

    @Test
    void marginStoredOnListBasis() {
        Map<String, PriceDecision> decisions = index(injected.rows(TableRegistry.PRICE_DECISION), PriceDecision::decisionId);
        for (GroundTruthRecord l : of(IssueType.MARGIN_BEFORE_DISCOUNT)) {
            PriceDecision d = decisions.get(l.entityId());
            BigDecimal netMargin = MarginCalculator.netMargin(d.expectedNetPrice(), d.landedCost());
            assertThat(d.marginPct()).as(l.gtId()).isNotEqualByComparingTo(netMargin);
            assertThat(netMargin).isLessThan(d.requiredMarginPct());
            assertThat(d.marginPct()).isEqualByComparingTo(d.finalListPrice().subtract(d.landedCost())
                    .divide(d.finalListPrice(), 4, java.math.RoundingMode.HALF_UP));
            assertThat(d.expectedNetPrice()).isLessThan(d.finalListPrice());
            assertThat(d.reasonCodeList()).doesNotContain(ReasonCode.PROMO_REJECTED_MARGIN);
        }
    }

    @Test
    void priceChangesWithoutReasonSpanUntilTheNextDecision() {
        Map<String, PriceHistory> prices = index(injected.rows(TableRegistry.PRICE_HISTORY), PriceHistory::priceHistoryId);
        List<CustomerOrder> orders = injected.rows(TableRegistry.CUSTOMER_ORDER);
        for (GroundTruthRecord l : of(IssueType.PRICE_CHANGE_WITHOUT_REASON)) {
            PriceHistory first = prices.get(l.entityId());
            assertThat(first.listPrice()).isNotEqualByComparingTo(first.previousListPrice());
            assertThat(first.changeReason()).isNull();
            assertThat(first.sourceDecisionId()).isNull();
            for (String id : l.relatedIds().stream().filter(x -> x.startsWith("PRC-")).toList()) {
                PriceHistory ph = prices.get(id);
                assertThat(ph.listPrice()).isEqualByComparingTo(first.listPrice());
                assertThat(ph.changeReason()).isNull();
                assertThat(ph.sourceDecisionId()).isNull();
            }
            orders.stream().filter(o -> o.priceHistoryId().equals(first.priceHistoryId()))
                    .forEach(o -> assertThat(o.unitListPrice()).isEqualByComparingTo(first.listPrice()));
        }
    }

    @Test
    void skusWithoutAnAnomalyAreUntouched() {
        Set<String> anomalous = labels.stream().map(GroundTruthRecord::skuId).collect(Collectors.toCollection(TreeSet::new));
        Map<String, InventorySnapshot> a = index(injected.rows(TableRegistry.INVENTORY_SNAPSHOT),
                InventorySnapshot::inventorySnapshotId);
        Map<String, InventorySnapshot> b = index(clean.rows(TableRegistry.INVENTORY_SNAPSHOT),
                InventorySnapshot::inventorySnapshotId);
        assertThat(a.keySet()).isEqualTo(b.keySet());
        a.forEach((id, row) -> {
            if (!anomalous.contains(row.skuId())) {
                assertThat(row).as(id).isEqualTo(b.get(id));
            }
        });
        Map<String, PriceHistory> pa = index(injected.rows(TableRegistry.PRICE_HISTORY), PriceHistory::priceHistoryId);
        Map<String, PriceHistory> pb = index(clean.rows(TableRegistry.PRICE_HISTORY), PriceHistory::priceHistoryId);
        pa.forEach((id, row) -> {
            if (!anomalous.contains(row.skuId())) {
                assertThat(row.listPrice()).as(id).isEqualByComparingTo(pb.get(id).listPrice());
                assertThat(row.shelfPrice()).as(id).isEqualByComparingTo(pb.get(id).shelfPrice());
                assertThat(row.changeReason()).as(id).isEqualTo(pb.get(id).changeReason());
            }
        });
    }

    @Test
    void cleanRunHasNoInjectedPatterns() {
        assertThat(clean.<PriceHistory>rows(TableRegistry.PRICE_HISTORY))
                .allSatisfy(p -> assertThat(p.sourceDecisionId()).isNotNull());
        assertThat(clean.<PriceDecisionObservation>rows(TableRegistry.PRICE_DECISION_OBSERVATION))
                .noneMatch(o -> o.usage() == ObservationUsage.USED && o.ageHours().compareTo(new BigDecimal("72")) > 0);
        assertThat(clean.<PriceDecision>rows(TableRegistry.PRICE_DECISION)).allSatisfy(d ->
                assertThat(d.marginPct()).isEqualByComparingTo(MarginCalculator.netMargin(d.expectedNetPrice(),
                        d.landedCost())));
    }
}
