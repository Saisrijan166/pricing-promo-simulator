package com.pricingsim.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricingsim.api.dto.DecisionExplanationResponse;
import com.pricingsim.api.dto.PriceHistoryView;
import com.pricingsim.persistence.DecisionQueryDao;
import com.pricingsim.persistence.ExceptionDao;

/**
 * Stored decisions and their traceability chain: price_history → decision (inputs + explanation) → cost record,
 * observations considered, markdowns considered with their rules, status events, and detector findings on the
 * decision or its price-history rows (PLAN.md §8, §13).
 */
@Service
@Profile("!offline")
public class DecisionExplainService {

    private final DecisionQueryDao decisions;
    private final ExceptionDao exceptions;
    private final ObjectMapper mapper;

    public DecisionExplainService(DecisionQueryDao decisions, ExceptionDao exceptions, ObjectMapper mapper) {
        this.decisions = decisions;
        this.exceptions = exceptions;
        this.mapper = mapper;
    }

    public Map<String, Object> decision(String decisionId) {
        Map<String, Object> d = decisions.decision(decisionId)
                .orElseThrow(() -> new NotFoundException("Unknown decision " + decisionId));
        d.put("explanation", json((String) d.get("explanation")));
        return d;
    }

    public DecisionExplanationResponse explain(String decisionId) {
        Map<String, Object> d = decisions.decision(decisionId)
                .orElseThrow(() -> new NotFoundException("Unknown decision " + decisionId));
        JsonNode explanation = json((String) d.remove("explanation"));
        List<Map<String, Object>> prices = decisions.priceHistoryOf(decisionId);
        LocalDate from = prices.isEmpty() ? null : (LocalDate) prices.get(0).get("price_date");
        LocalDate to = prices.isEmpty() ? null : (LocalDate) prices.get(prices.size() - 1).get("price_date");
        List<String> entities = new ArrayList<>();
        entities.add(decisionId);
        prices.forEach(p -> entities.add((String) p.get("price_history_id")));
        return new DecisionExplanationResponse(d, decisions.product((String) d.get("sku_id")),
                decisions.costRecord((String) d.get("cost_record_id")), decisions.observations(decisionId),
                decisions.promotions(decisionId), explanation,
                new DecisionExplanationResponse.PriceHistoryRange(from, to, prices.size(), prices),
                decisions.statusEvents(decisionId), (String) d.get("previous_decision_id"),
                decisions.successor(decisionId).orElse(null), exceptions.forEntities(entities));
    }

    public List<PriceHistoryView> priceHistory(String skuId, LocalDate from, LocalDate to) {
        if (!decisions.productExists(skuId)) {
            throw new NotFoundException("Unknown SKU " + skuId);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        return decisions.priceHistory(skuId, from, to).stream().map(PriceHistoryView::of).toList();
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Stored explanation is not valid JSON", e);
        }
    }
}
