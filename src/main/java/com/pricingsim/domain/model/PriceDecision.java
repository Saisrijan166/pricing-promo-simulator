package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import com.pricingsim.domain.enums.DecisionSource;
import com.pricingsim.domain.enums.DecisionStatus;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.DecisionType;
import com.pricingsim.domain.enums.ReasonCode;

/**
 * Table {@code price_decision} (PLAN.md §5.9). Stores every input the engine used, the outcome, the reason codes
 * (pipe-delimited) and the explanation (JSON array of explanation steps), so each price is explainable.
 */
public record PriceDecision(
        String decisionId,
        String skuId,
        Instant decisionTs,
        LocalDate effectiveDate,
        DecisionTrigger trigger,
        DecisionSource decisionSource,
        String previousDecisionId,
        String costRecordId,
        BigDecimal landedCost,
        BigDecimal prevListPrice,
        BigDecimal floorPrice,
        BigDecimal ceilingPrice,
        BigDecimal competitorRefPrice,
        int competitorFreshCount,
        int competitorStaleCount,
        int onHandUnits,
        int demand7dUnits,
        int demand28dUnits,
        BigDecimal velocityRatio,
        BigDecimal daysOfCover,
        BigDecimal targetPrice,
        BigDecimal finalListPrice,
        BigDecimal markdownDiscountPct,
        BigDecimal expectedNetPrice,
        BigDecimal marginPct,
        BigDecimal requiredMarginPct,
        DecisionType decisionType,
        ReasonCode primaryReasonCode,
        String reasonCodes,
        String explanation,
        String ruleVersion,
        DecisionStatus status) {

    /** Separator used in {@link #reasonCodes()}. */
    public static final String REASON_CODE_SEPARATOR = "|";

    /** Parses {@link #reasonCodes()} into enum values, in stored order. */
    public List<ReasonCode> reasonCodeList() {
        if (reasonCodes == null || reasonCodes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(reasonCodes.split("\\|")).map(ReasonCode::valueOf).toList();
    }

    /** Joins reason codes into the stored pipe-delimited form. */
    public static String joinReasonCodes(List<ReasonCode> codes) {
        return String.join(REASON_CODE_SEPARATOR, codes.stream().map(Enum::name).toList());
    }
}
