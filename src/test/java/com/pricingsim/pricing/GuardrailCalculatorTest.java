package com.pricingsim.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class GuardrailCalculatorTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void floorFromMinimumMargin() {
        // 80 / (1 - 0.20) = 100.00 exactly.
        assertThat(GuardrailCalculator.floor(d("80.00"), d("0.2000"), null)).isEqualByComparingTo("100.00");
    }

    @Test
    void floorRoundsUpToThePaisa() {
        // 100 / 0.85 = 117.647… → 117.65 (up), so the margin at the floor is never below the minimum.
        BigDecimal floor = GuardrailCalculator.floor(d("100.00"), d("0.15"), null);
        assertThat(floor).isEqualByComparingTo("117.65");
        assertThat(MarginCalculator.meetsMargin(floor, d("100.00"), d("0.15"))).isTrue();
        // 10 / 0.7 = 14.2857… → 14.29, not 14.28.
        assertThat(GuardrailCalculator.floor(d("10.00"), d("0.30"), null)).isEqualByComparingTo("14.29");
    }

    @Test
    void mapDominatesWhenHigher() {
        assertThat(GuardrailCalculator.floor(d("80.00"), d("0.20"), d("120.00"))).isEqualByComparingTo("120.00");
        assertThat(GuardrailCalculator.floor(d("80.00"), d("0.20"), d("90.00"))).isEqualByComparingTo("100.00");
    }

    @Test
    void ceilingIsMrp() {
        assertThat(GuardrailCalculator.ceiling(d("499.00"))).isEqualByComparingTo("499.00");
    }

    @Test
    void ceilingBelowFloorIsAConflictAndTheFloorWins() {
        GuardrailCalculator.Guardrails g = GuardrailCalculator.guardrails(d("90.00"), d("0.20"), null, d("99.00"));
        assertThat(g.floor()).isEqualByComparingTo("112.50");
        assertThat(g.conflict()).isTrue();
        assertThat(g.clamp(d("95.00"))).isEqualByComparingTo("112.50");
    }

    @Test
    void clampWithoutConflict() {
        GuardrailCalculator.Guardrails g = GuardrailCalculator.guardrails(d("80.00"), d("0.20"), null, d("199.00"));
        assertThat(g.conflict()).isFalse();
        assertThat(g.clamp(d("90.00"))).isEqualByComparingTo("100.00");
        assertThat(g.clamp(d("150.00"))).isEqualByComparingTo("150.00");
        assertThat(g.clamp(d("250.00"))).isEqualByComparingTo("199.00");
        // Floor equal to ceiling is not a conflict.
        assertThat(GuardrailCalculator.guardrails(d("80.00"), d("0.20"), null, d("100.00")).conflict()).isFalse();
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> GuardrailCalculator.floor(d("0"), d("0.2"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailCalculator.floor(d("10"), d("1.0"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailCalculator.ceiling(d("-1"))).isInstanceOf(IllegalArgumentException.class);
    }
}
