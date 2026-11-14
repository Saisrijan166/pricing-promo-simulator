package com.pricingsim.domain.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.pricingsim.domain.id.IdFactory.Sequence;
import com.pricingsim.domain.id.IdFactory.SequenceId;

class IdFactoryTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);

    // --- Formatting matches the brief's nomenclature ----------------------------------------------------------------

    @Test
    void formatsTheBriefExamplesExactly() {
        assertThat(IdFactory.sku(1)).isEqualTo("SKU-000001");
        assertThat(IdFactory.costRecord("SKU-000001", YearMonth.of(2026, 1))).isEqualTo("COST-SKU-000001-2026-01");
        assertThat(IdFactory.priceHistory("SKU-000001", JAN_1)).isEqualTo("PRC-SKU-000001-20260101");
        assertThat(IdFactory.promotion(2026, 1)).isEqualTo("PROMO-2026-000001");
        assertThat(IdFactory.decision(2026, 1)).isEqualTo("DEC-2026-000001");
    }

    @Test
    void formatsEveryOtherIdType() {
        assertThat(IdFactory.inventorySnapshot("SKU-000873", LocalDate.of(2026, 2, 9))).isEqualTo("INV-SKU-000873-20260209");
        assertThat(IdFactory.customerRef(42)).isEqualTo("CUST-0000042");
        assertThat(IdFactory.rule(2026, 12)).isEqualTo("RULE-2026-000012");
        assertThat(IdFactory.observation(2026, 1)).isEqualTo("OBS-2026-0000001");
        assertThat(IdFactory.order(2026, 1)).isEqualTo("ORD-2026-0000001");
        assertThat(IdFactory.coupon(2026, 7)).isEqualTo("CPN-2026-000007");
        assertThat(IdFactory.productEvent(2026, 1)).isEqualTo("PEV-2026-000001");
        assertThat(IdFactory.promotionEvent(2026, 1)).isEqualTo("PMEV-2026-000001");
        assertThat(IdFactory.decisionEvent(2026, 1)).isEqualTo("DEV-2026-0000001");
        assertThat(IdFactory.groundTruth(2026, 1)).isEqualTo("GT-2026-000001");
        assertThat(IdFactory.detectedException(2026, 1)).isEqualTo("EXC-2026-0000001");
    }

    @ParameterizedTest
    @EnumSource(Sequence.class)
    void everySequenceRoundTripsAndMatchesItsPattern(Sequence sequence) {
        for (long n : new long[] {1, 9, 10, 4512, sequence.capacity()}) {
            String id = IdFactory.sequenceId(sequence, 2026, n);
            assertThat(IdPatterns.matches(sequence.pattern(), id)).as(id).isTrue();
            assertThat(id).hasSize(sequence.prefix().length() + 1 + 4 + 1 + sequence.width());
            assertThat(IdFactory.parseSequenceId(sequence, id)).isEqualTo(new SequenceId(sequence, 2026, n));
        }
    }

    @Test
    void zeroPadsToTheConfiguredWidth() {
        assertThat(IdFactory.sku(873)).isEqualTo("SKU-000873");
        assertThat(IdFactory.sku(999_999)).isEqualTo("SKU-999999");
        assertThat(IdFactory.decision(2026, 4512)).isEqualTo("DEC-2026-004512");
        assertThat(SequenceCounter.pad(5, 3)).isEqualTo("005");
    }

    // --- Overflow -------------------------------------------------------------------------------------------------

    @Test
    void formattingANumberThatDoesNotFitThrows() {
        assertThatThrownBy(() -> IdFactory.sku(1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.decision(2026, 1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.order(2026, 10_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.sku(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.promotion(26, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterIssuesSequentiallyAndThrowsOnOverflow() {
        SequenceCounter counter = new SequenceCounter("T", 2);
        assertThat(counter.capacity()).isEqualTo(99);
        assertThat(counter.nextId()).isEqualTo("01");
        for (int i = 2; i <= 99; i++) {
            counter.next();
        }
        assertThat(counter.issued()).isEqualTo(99);
        assertThat(counter.remaining()).isZero();
        assertThatThrownBy(counter::next).isInstanceOf(IllegalStateException.class).hasMessageContaining("overflow");
    }

    @Test
    void sequenceCounterFormatsIdsAndFailsFastOnCapacity() {
        SequenceCounter decisions = SequenceCounter.forSequence(Sequence.DECISION, 2026);
        assertThat(decisions.nextId()).isEqualTo("DEC-2026-000001");
        assertThat(decisions.nextId()).isEqualTo("DEC-2026-000002");
        assertThat(decisions.capacity()).isEqualTo(999_999);
        decisions.ensureCapacity(150_000);
        assertThatThrownBy(() -> decisions.ensureCapacity(999_998)).isInstanceOf(IllegalStateException.class);

        SequenceCounter skus = SequenceCounter.forSku();
        assertThat(skus.nextId()).isEqualTo("SKU-000001");
        assertThat(SequenceCounter.forCustomer().nextId()).isEqualTo("CUST-0000001");
    }

    // --- Embedded-field extraction ----------------------------------------------------------------------------------

    @Test
    void extractsEmbeddedSkuMonthAndDate() {
        assertThat(IdFactory.skuOf("COST-SKU-000873-2026-02")).isEqualTo("SKU-000873");
        assertThat(IdFactory.skuOf("PRC-SKU-000873-20260209")).isEqualTo("SKU-000873");
        assertThat(IdFactory.skuOf("INV-SKU-000873-20260209")).isEqualTo("SKU-000873");
        assertThat(IdFactory.costMonthOf("COST-SKU-000873-2026-02")).isEqualTo(YearMonth.of(2026, 2));
        assertThat(IdFactory.dateOf("PRC-SKU-000873-20260209")).isEqualTo(LocalDate.of(2026, 2, 9));
        assertThat(IdFactory.dateOf("INV-SKU-000873-20260629")).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(IdFactory.skuNumber("SKU-000873")).isEqualTo(873);
        assertThat(IdFactory.costMonth(YearMonth.of(2026, 3))).isEqualTo("2026-03");
    }

    @Test
    void naturalKeyIdsRoundTrip() {
        LocalDate d = LocalDate.of(2026, 6, 29);
        String prc = IdFactory.priceHistory("SKU-004999", d);
        assertThat(IdFactory.skuOf(prc)).isEqualTo("SKU-004999");
        assertThat(IdFactory.dateOf(prc)).isEqualTo(d);
        String cost = IdFactory.costRecord("SKU-004999", YearMonth.of(2026, 12));
        assertThat(IdFactory.costMonthOf(cost)).isEqualTo(YearMonth.of(2026, 12));
    }

    // --- Invalid input ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "SKU-1", "SKU-0000001", "sku-000001", "SKU_000001", " SKU-000001", "SKU-00000A"})
    void rejectsInvalidSkus(String bad) {
        assertThat(IdPatterns.matches(IdPatterns.SKU, bad)).isFalse();
        assertThatThrownBy(() -> IdFactory.skuNumber(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidNaturalKeyIds() {
        // month 13, impossible calendar date, wrong prefix, short SKU, null
        assertThatThrownBy(() -> IdFactory.costMonthOf("COST-SKU-000001-2026-13")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.dateOf("PRC-SKU-000001-20260230")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.dateOf("DEC-2026-000001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.skuOf("PRC-SKU-0001-20260101")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.skuOf(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.costRecord("SKU-1", YearMonth.of(2026, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.priceHistory("SKU-000001", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsSequenceIdsWithWrongWidthPrefixOrYear() {
        assertThatThrownBy(() -> IdFactory.parseSequenceId(Sequence.DECISION, "DEC-2026-0000001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.parseSequenceId(Sequence.ORDER, "ORD-2026-000001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.parseSequenceId(Sequence.PROMOTION, "PROMO-26-000001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.parseSequenceId(Sequence.PRODUCT_EVENT, "PMEV-2026-000001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdFactory.parseSequenceId(Sequence.GROUND_TRUTH, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
