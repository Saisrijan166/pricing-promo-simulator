package com.pricingsim.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.pricingsim.persistence.Rows;

/** {@code POST /api/v1/pricing/simulate} (PLAN.md §13). */
class PricingControllerIT extends ApiTestSupport {

    private ResultActions simulate(String body) throws Exception {
        return mvc.perform(post("/api/v1/pricing/simulate").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> weeklyDecision() {
        return Rows.normalize(jdbc().queryForMap("SELECT * FROM price_decision WHERE trigger = 'WEEKLY_REVIEW'"
                + " AND decision_source = 'ENGINE' ORDER BY decision_id LIMIT 1"));
    }

    @Test
    void simulatingAStoredDecisionDateReproducesItAndPersistsNothing() throws Exception {
        Map<String, Object> d = weeklyDecision();
        long before = jdbc().queryForObject("SELECT COUNT(*) FROM price_decision", Long.class);
        simulate("{\"skuId\":\"" + d.get("sku_id") + "\",\"asOfDate\":\"" + d.get("effective_date") + "\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.trigger").value("WEEKLY_REVIEW"))
                .andExpect(jsonPath("$.candidateListPrice").value(((BigDecimal) d.get("final_list_price")).doubleValue()))
                .andExpect(jsonPath("$.primaryReasonCode").value(d.get("primary_reason_code")))
                .andExpect(jsonPath("$.floorPrice").value(((BigDecimal) d.get("floor_price")).doubleValue()))
                .andExpect(jsonPath("$.explanation", hasSize(12)))
                .andExpect(jsonPath("$.ruleVersion").value("pricing-rules-v1"))
                .andExpect(jsonPath("$.persisted").value(false));
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM price_decision", Long.class)).isEqualTo(before);
    }

    @Test
    void overridesAreRespected() throws Exception {
        Map<String, Object> d = weeklyDecision();
        String head = "{\"skuId\":\"" + d.get("sku_id") + "\",\"asOfDate\":\"" + d.get("effective_date") + "\",";
        simulate(head + "\"overrides\":{\"onHandUnits\":0}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs.onHandUnits").value(0))
                .andExpect(jsonPath("$.reasonCodes", hasItem("STOCKOUT_HOLD")));
        simulate(head + "\"overrides\":{\"landedCost\":100000}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs.landedCost").value(100000.0))
                .andExpect(jsonPath("$.status").value("ESCALATED"))
                .andExpect(jsonPath("$.primaryReasonCode").value("GUARDRAIL_CONFLICT_ESCALATED"));
        String ts = d.get("effective_date") + "T01:00:00Z";
        simulate(head + "\"overrides\":{\"competitorPrices\":[{\"competitor\":\"COMP_ALPHA\",\"price\":"
                + d.get("ceiling_price") + ",\"capturedAt\":\"" + ts + "\"}]}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observations", hasSize(1)))
                .andExpect(jsonPath("$.observations[0].observationId").value("SIM-OBS-1"))
                .andExpect(jsonPath("$.observations[0].usage").value("USED"))
                .andExpect(jsonPath("$.observations[0].ageHours").value(5.0))
                .andExpect(jsonPath("$.reasonCodes", hasItem("COMPETITOR_ALIGN")));
        simulate(head + "\"overrides\":{\"promotionIds\":[]}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotions", hasSize(0)));
    }

    @Test
    void unknownSkuOrPromotionOrDateOutsideDataIs404ProblemDetail() throws Exception {
        simulate("{\"skuId\":\"SKU-999999\",\"asOfDate\":\"2026-01-05\"}")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Unknown SKU SKU-999999"));
        simulate("{\"skuId\":\"SKU-000001\",\"asOfDate\":\"2027-06-01\"}").andExpect(status().isNotFound());
        simulate("{\"skuId\":\"SKU-000001\",\"asOfDate\":\"2026-01-05\",\"overrides\":{\"promotionIds\":"
                + "[\"PROMO-2026-999999\"]}}").andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsAre400ProblemDetail() throws Exception {
        simulate("{\"skuId\":\"SKU-000001\",\"asOfDate\":\"2026-13-01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        simulate("{\"skuId\":\"bad\",\"asOfDate\":\"2026-01-05\"}").andExpect(status().isBadRequest());
        simulate("{\"skuId\":\"SKU-000001\"}").andExpect(status().isBadRequest());
        simulate("{\"skuId\":\"SKU-000001\",\"asOfDate\":\"2026-01-05\",\"overrides\":{\"onHandUnits\":-1}}")
                .andExpect(status().isBadRequest());
        simulate("{\"skuId\":\"SKU-000001\",\"asOfDate\":\"2026-01-05\",\"overrides\":{\"competitorPrices\":"
                + "[{\"competitor\":\"COMP_OMEGA\",\"price\":10,\"capturedAt\":\"2026-01-04T00:00:00Z\"}]}}")
                .andExpect(status().isBadRequest());
        simulate("not json").andExpect(status().isBadRequest());
    }
}
