package com.pricingsim.api;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Decision, explain, price-history and report endpoints (PLAN.md §13). */
class DecisionControllerIT extends ApiTestSupport {

    private String decisionWithObservationsAndPromotions() {
        return jdbc().queryForObject("SELECT d.decision_id FROM price_decision d WHERE EXISTS (SELECT 1 FROM"
                + " price_decision_observation b WHERE b.decision_id = d.decision_id) ORDER BY (SELECT COUNT(*) FROM"
                + " price_decision_promotion p WHERE p.decision_id = d.decision_id) DESC, d.decision_id LIMIT 1",
                String.class);
    }

    @Test
    void storedDecision() throws Exception {
        mvc.perform(get("/api/v1/decisions/DEC-2026-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision_id").value("DEC-2026-000001"))
                .andExpect(jsonPath("$.trigger").value("INITIAL"))
                .andExpect(jsonPath("$.decision_ts").value("2026-01-01T06:00:00Z"))
                .andExpect(jsonPath("$.explanation", hasSize(12)));
    }

    @Test
    void explainReturnsTheFullChain() throws Exception {
        String id = decisionWithObservationsAndPromotions();
        mvc.perform(get("/api/v1/decisions/" + id + "/explain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.decision_id").value(id))
                .andExpect(jsonPath("$.product.sku_id", notNullValue()))
                .andExpect(jsonPath("$.costRecord.cost_record_id", notNullValue()))
                .andExpect(jsonPath("$.observations.length()", greaterThan(0)))
                .andExpect(jsonPath("$.observations[0].usage", notNullValue()))
                .andExpect(jsonPath("$.observations[0].age_hours", notNullValue()))
                .andExpect(jsonPath("$.explanation", hasSize(12)))
                .andExpect(jsonPath("$.explanation[11].step").value("S12"))
                .andExpect(jsonPath("$.priceHistory.days", greaterThan(0)))
                .andExpect(jsonPath("$.priceHistory.rows[*].list_price", everyItem(notNullValue())))
                .andExpect(jsonPath("$.statusEvents[0].to_status").value("PROPOSED"))
                .andExpect(jsonPath("$.detectedExceptions", notNullValue()));
    }

    @Test
    void explainLinksDetectorFindings() throws Exception {
        String id = jdbc().queryForObject("SELECT entity_id FROM detected_exception WHERE issue_type ="
                + " 'PRICE_BELOW_FLOOR'", String.class);
        mvc.perform(get("/api/v1/decisions/" + id + "/explain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectedExceptions[0].issue_type").value("PRICE_BELOW_FLOOR"))
                .andExpect(jsonPath("$.decision.decision_source").value("MANUAL_OVERRIDE"));
    }

    @Test
    void unknownDecisionIs404AndMalformedIdIs400() throws Exception {
        mvc.perform(get("/api/v1/decisions/DEC-2026-999999/explain"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Unknown decision DEC-2026-999999"));
        mvc.perform(get("/api/v1/decisions/not-an-id")).andExpect(status().isBadRequest());
    }

    @Test
    void priceHistoryWithRangeAndErrors() throws Exception {
        mvc.perform(get("/api/v1/products/SKU-000001/price-history").param("from", "2026-01-02").param("to",
                        "2026-01-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].priceDate").value("2026-01-02"))
                .andExpect(jsonPath("$[0].sourceDecisionId", notNullValue()));
        mvc.perform(get("/api/v1/products/SKU-999999/price-history")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/SKU-000001/price-history").param("from", "2026-02-01").param("to",
                "2026-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products/SKU-000001/price-history").param("from", "yesterday"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reports() throws Exception {
        mvc.perform(get("/api/v1/reports/validation-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.failed").value(0))
                .andExpect(jsonPath("$.business_rule_findings.PRICE_BELOW_FLOOR").value(1));
        mvc.perform(get("/api/v1/reports/evaluation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anomaly_detection.PRICE_BELOW_FLOOR.recall").value(1.0))
                .andExpect(jsonPath("$.micro.precision", is(1.0)));
    }
}
