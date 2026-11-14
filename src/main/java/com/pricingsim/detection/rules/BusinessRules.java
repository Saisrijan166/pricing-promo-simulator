package com.pricingsim.detection.rules;

import java.util.List;

import com.pricingsim.detection.DetectionRule;

/** The five business-rule detectors (PLAN.md §9), shared by the validator's BUSINESS_RULE checks and the detector. */
public final class BusinessRules {

    private BusinessRules() {
    }

    public static List<DetectionRule> all() {
        return List.of(new PriceBelowFloorDetector(), new PromoStackViolationDetector(), new StaleObservationDetector(),
                new MarginBasisDetector(), new UnexplainedPriceChangeDetector());
    }
}
