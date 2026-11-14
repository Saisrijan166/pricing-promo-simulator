package com.pricingsim.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DemandSignalCalculatorTest {

    @Test
    void velocityRatioWithFullHistory() {
        // (14/7) / (56/28) = 1.0; (21/7) / (56/28) = 1.5
        assertThat(DemandSignalCalculator.velocityRatio(14, 7, 56, 28)).isEqualByComparingTo("1.0000");
        assertThat(DemandSignalCalculator.velocityRatio(21, 7, 56, 28)).isEqualByComparingTo("1.5000");
    }

    @Test
    void velocityRatioUsesDaysWithHistory() {
        // Launched 10 days ago, selling 2/day: (14/7) / (20/10) = 1.0, not (14/7)/(20/28) = 2.8.
        assertThat(DemandSignalCalculator.velocityRatio(14, 7, 20, 10)).isEqualByComparingTo("1.0000");
    }

    @Test
    void zeroSalesOrNoHistoryIsNeutralWithoutDivisionByZero() {
        assertThat(DemandSignalCalculator.velocityRatio(0, 7, 0, 28)).isEqualByComparingTo("1.0000");
        assertThat(DemandSignalCalculator.velocityRatio(0, 0, 0, 0)).isEqualByComparingTo("1.0000");
    }

    @Test
    void daysOfCoverFloorsTheDailyRateAt0point1() {
        // 56 units in 28 days = 2/day → 50 on hand = 25 days.
        assertThat(DemandSignalCalculator.daysOfCover(50, 56, 28)).isEqualByComparingTo("25.00");
        // 1 unit in 28 days = 0.036/day < 0.1 → 50 / 0.1 = 500.
        assertThat(DemandSignalCalculator.daysOfCover(50, 1, 28)).isEqualByComparingTo("500.00");
        assertThat(DemandSignalCalculator.daysOfCover(7, 0, 0)).isEqualByComparingTo("70.00");
        // Exactly 0.1/day uses the floor too.
        assertThat(DemandSignalCalculator.daysOfCover(3, 1, 10)).isEqualByComparingTo("30.00");
        assertThat(DemandSignalCalculator.daysOfCover(0, 56, 28)).isEqualByComparingTo("0.00");
    }

    @Test
    void signalsCarryHistoryDays() {
        DemandSignalCalculator.Signals s = DemandSignalCalculator.signals(10, 3, 3, 3, 3);
        assertThat(s.historyDays()).isEqualTo(3);
        assertThat(s.velocityRatio()).isEqualByComparingTo("1.0000");
        assertThat(s.daysOfCover()).isEqualByComparingTo("10.00");
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> DemandSignalCalculator.signals(-1, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemandSignalCalculator.signals(1, 5, 7, 4, 28))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
