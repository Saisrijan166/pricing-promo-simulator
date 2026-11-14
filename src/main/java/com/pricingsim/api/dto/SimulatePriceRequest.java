package com.pricingsim.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.pricingsim.domain.enums.Competitor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/pricing/simulate}: price a SKU on a date from the loaded data, with optional what-if overrides.
 *
 * @param skuId     e.g. {@code SKU-000873}
 * @param asOfDate  the decision date (decision time 06:00Z)
 * @param overrides optional replacements for stored inputs
 */
public record SimulatePriceRequest(
        @NotNull @Pattern(regexp = "^SKU-\\d{6}$", message = "must look like SKU-000001") String skuId,
        @NotNull LocalDate asOfDate,
        @Valid Overrides overrides) {

    /**
     * @param landedCost       replaces the cost record's landed cost
     * @param onHandUnits      replaces the stock at decision time
     * @param competitorPrices replaces all stored competitor observations
     * @param promotionIds     restricts the markdowns considered to these (must include the SKU)
     */
    public record Overrides(
            @Positive BigDecimal landedCost,
            @PositiveOrZero Integer onHandUnits,
            @Size(max = 20) List<@Valid CompetitorPrice> competitorPrices,
            @Size(max = 20) List<@Pattern(regexp = "^PROMO-\\d{4}-\\d{6}$") String> promotionIds) {
    }

    /** A what-if competitor observation (ingested when captured; confidence 1.0 unless given). */
    public record CompetitorPrice(
            @NotNull Competitor competitor,
            @NotNull @Positive BigDecimal price,
            @NotNull Instant capturedAt,
            @DecimalMin("0") @DecimalMax("1") BigDecimal matchConfidence) {
    }
}
