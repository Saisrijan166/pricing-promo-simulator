package com.pricingsim.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricingsim.api.dto.SimulatePriceRequest;
import com.pricingsim.api.dto.SimulatePriceResponse;
import com.pricingsim.domain.enums.ObservationSource;
import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.persistence.PricingInputDao;
import com.pricingsim.pricing.PricingContext;
import com.pricingsim.pricing.PricingEngine;
import com.pricingsim.pricing.PricingResult;

/**
 * {@code POST /pricing/simulate}: builds the context from the database with {@link PricingInputDao}, applies the
 * what-if overrides and runs the same {@link PricingEngine} as the generator. Read-only: nothing is written, so the
 * generated dataset stays reproducible. Without overrides, simulating a stored decision's date reproduces it.
 */
@Service
@Profile("!offline")
public class SimulationService {

    private final PricingInputDao inputs;
    private final PricingEngine engine;
    private final ObjectMapper mapper;

    public SimulationService(PricingInputDao inputs, PricingEngine engine, ObjectMapper mapper) {
        this.inputs = inputs;
        this.engine = engine;
        this.mapper = mapper;
    }

    public SimulatePriceResponse simulate(SimulatePriceRequest request) {
        SimulatePriceRequest.Overrides o = request.overrides();
        List<CompetitorObservation> observations = null;
        if (o != null && o.competitorPrices() != null) {
            observations = new ArrayList<>();
            int n = 1;
            for (SimulatePriceRequest.CompetitorPrice c : o.competitorPrices()) {
                observations.add(new CompetitorObservation("SIM-OBS-" + n++, request.skuId(), c.competitor(),
                        c.price().setScale(2, RoundingMode.HALF_UP), c.capturedAt(), c.capturedAt(),
                        ObservationSource.MANUAL_AUDIT, true,
                        c.matchConfidence() == null ? new BigDecimal("1.000") : c.matchConfidence()));
            }
        }
        PricingInputDao.Overrides overrides = o == null ? PricingInputDao.Overrides.NONE
                : new PricingInputDao.Overrides(o.landedCost() == null ? null
                        : o.landedCost().setScale(2, RoundingMode.HALF_UP), o.onHandUnits(), observations,
                        o.promotionIds());
        PricingContext ctx = inputs.load(request.skuId(), request.asOfDate(), overrides);
        PricingResult r = engine.price(ctx);
        return new SimulatePriceResponse(ctx.skuId(), ctx.effectiveDate(), ctx.decisionTs(), ctx.trigger().name(),
                new SimulatePriceResponse.Inputs(ctx.costRecordId(), ctx.landedCost(), ctx.prevListPrice(),
                        ctx.previousDecisionId(), ctx.onHandUnits(), ctx.demand7dUnits(), ctx.demand28dUnits(),
                        r.signals().velocityRatio(), r.signals().daysOfCover()),
                r.finalListPrice(), r.expectedNetPrice(), r.floorPrice(), r.ceilingPrice(), r.targetPrice(),
                r.competitor().referencePrice(), r.markdownDiscountPct(), r.marginPct(), r.requiredMarginPct(),
                r.decisionType().name(), r.decisionSource().name(), r.status().name(), r.primaryReasonCode().name(),
                r.reasonCodes().stream().map(Enum::name).toList(),
                r.competitor().assessments().stream().map(a -> new SimulatePriceResponse.Observation(
                        a.observationId(), a.observation().competitor().name(), a.observation().observedPrice(),
                        a.observation().sourceCapturedAt(), a.ageHours(), a.usage().name())).toList(),
                r.promotions().stream().map(e -> new SimulatePriceResponse.Promotion(e.promotionId(), e.status().name(),
                        e.reason().name(), e.discount(), r.effectiveDiscountPct(e))).toList(),
                json(r.explanationJson()), r.ruleVersion(), false);
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Explanation is not valid JSON", e);
        }
    }
}
