package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.DecisionSource;
import com.pricingsim.domain.enums.DecisionStatus;
import com.pricingsim.domain.enums.DecisionType;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.model.PriceDecision;

/**
 * Engine output (PLAN.md §8): every value stored on {@code price_decision}, the observation and promotion outcomes
 * for its bridges, and the S1–S12 explanation.
 *
 * @param status                APPROVED, or ESCALATED (guardrail conflict or a change above the auto-approve limit)
 * @param targetPrice           S5–S6 target (before the step limit)
 * @param candidatePrice        S8 candidate (after step limit and clamp, before price-point rounding)
 * @param promotions            every markdown considered, final status after S10 and S11
 * @param appliedMarkdowns      applied markdowns with their per-unit discounts, in application order
 * @param markdownDiscountPct   {@code (final − net) / final}, 4 dp
 * @param expectedNetPrice      final list price minus applied markdowns = the shelf price
 */
public record PricingResult(
        BigDecimal floorPrice,
        BigDecimal ceilingPrice,
        boolean guardrailConflict,
        CompetitorReferenceResolver.Result competitor,
        DemandSignalCalculator.Signals signals,
        BigDecimal targetPrice,
        BigDecimal candidatePrice,
        BigDecimal finalListPrice,
        List<PromotionStackResolver.Evaluation> promotions,
        List<PromotionStackResolver.Applied> appliedMarkdowns,
        BigDecimal markdownDiscountPct,
        BigDecimal expectedNetPrice,
        BigDecimal marginPct,
        BigDecimal requiredMarginPct,
        DecisionType decisionType,
        DecisionSource decisionSource,
        DecisionStatus status,
        ReasonCode primaryReasonCode,
        List<ReasonCode> reasonCodes,
        List<ExplanationStep> explanation) {

    public PricingResult {
        promotions = List.copyOf(promotions);
        appliedMarkdowns = List.copyOf(appliedMarkdowns);
        reasonCodes = List.copyOf(reasonCodes);
        explanation = List.copyOf(explanation);
    }

    public String ruleVersion() {
        return PricingPolicy.RULE_VERSION;
    }

    /** Pipe-delimited reason codes, as stored. */
    public String reasonCodesJoined() {
        return PriceDecision.joinReasonCodes(reasonCodes);
    }

    public String explanationJson() {
        return ExplanationStep.toJson(explanation);
    }

    public boolean escalated() {
        return status == DecisionStatus.ESCALATED;
    }

    public List<PromotionStackResolver.Evaluation> appliedPromotions() {
        return promotions.stream().filter(e -> e.status() == ApplicationStatus.APPLIED).toList();
    }

    /** Effective discount fraction a promotion contributed: its per-unit discount / final list price, 4 dp. */
    public BigDecimal effectiveDiscountPct(PromotionStackResolver.Evaluation evaluation) {
        return evaluation.discount().divide(finalListPrice, 4, RoundingMode.HALF_UP);
    }
}
