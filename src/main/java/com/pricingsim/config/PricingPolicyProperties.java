package com.pricingsim.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.pricingsim.pricing.PricingPolicy;

/**
 * {@code pricing.policy.*} from {@code application.yml} (PLAN.md §8 policy defaults). The pricing package stays free of
 * Spring: this class only converts configuration into an immutable {@link PricingPolicy}.
 */
@ConfigurationProperties("pricing.policy")
public record PricingPolicyProperties(
        @DefaultValue("72") int freshnessHours,
        @DefaultValue("0.80") BigDecimal minMatchConfidence,
        @DefaultValue("0.08") BigDecimal maxStepChangePct,
        @DefaultValue("0.10") BigDecimal autoApproveMaxChangePct,
        @DefaultValue("60") BigDecimal overstockDays,
        @DefaultValue("7") BigDecimal lowCoverDays,
        @DefaultValue("1.2") BigDecimal highVelocityRatio,
        @DefaultValue("-0.03") BigDecimal overstockAdj,
        @DefaultValue("0.03") BigDecimal lowCoverAdj,
        @DefaultValue("-0.03") BigDecimal valueOffset,
        @DefaultValue("0") BigDecimal matchOffset,
        @DefaultValue("0.05") BigDecimal premiumOffset,
        @DefaultValue("2") int defaultMaxStack,
        @DefaultValue("0.40") BigDecimal defaultMaxCombinedDiscount,
        @DefaultValue("0.95") BigDecimal initialTargetRatio,
        @DefaultValue("7") int minHistoryDays) {

    public PricingPolicy toPolicy() {
        return new PricingPolicy(freshnessHours, minMatchConfidence, maxStepChangePct, autoApproveMaxChangePct,
                overstockDays, lowCoverDays, highVelocityRatio, overstockAdj, lowCoverAdj, valueOffset, matchOffset,
                premiumOffset, defaultMaxStack, defaultMaxCombinedDiscount, initialTargetRatio, minHistoryDays);
    }
}
