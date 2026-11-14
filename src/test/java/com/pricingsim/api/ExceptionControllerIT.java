package com.pricingsim.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

/** {@code GET /api/v1/exceptions}: filters, paging and stable sort (PLAN.md §13). */
class ExceptionControllerIT extends ApiTestSupport {

    @Test
    void anomalyFilterReturnsOneFindingPerType() throws Exception {
        mvc.perform(get("/api/v1/exceptions").param("category", "ANOMALY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[*].category", everyItem(is("ANOMALY"))));
        mvc.perform(get("/api/v1/exceptions").param("issueType", "PROMO_STACK_VIOLATION"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].entityType").value("customer_order"));
    }

    @Test
    void skuAndDateFilters() throws Exception {
        String sku = jdbc().queryForObject("SELECT sku_id FROM detected_exception ORDER BY exception_id LIMIT 1",
                String.class);
        long expected = jdbc().queryForObject("SELECT COUNT(*) FROM detected_exception WHERE sku_id = ? AND"
                + " event_date BETWEEN '2026-01-03' AND '2026-01-10'", Long.class, sku);
        mvc.perform(get("/api/v1/exceptions").param("skuId", sku).param("from", "2026-01-03").param("to",
                        "2026-01-10").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value((int) expected))
                .andExpect(jsonPath("$.content[*].skuId", everyItem(is(sku))));
    }

    @Test
    void pagingIsStableAndCoversEverything() throws Exception {
        long total = jdbc().queryForObject("SELECT COUNT(*) FROM detected_exception", Long.class);
        assertThat(total).isGreaterThan(6);
        List<String> seen = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        int pages = (int) ((total + 2) / 3);
        for (int page = 0; page < pages; page++) {
            String body = mvc.perform(get("/api/v1/exceptions").param("page", Integer.toString(page))
                            .param("size", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalPages").value(pages))
                    .andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(body, "$.content[*].exceptionId");
            List<String> d = JsonPath.read(body, "$.content[*].eventDate");
            seen.addAll(ids);
            dates.addAll(d);
        }
        assertThat(seen).hasSize((int) total).doesNotHaveDuplicates();
        assertThat(dates).isSorted();
        mvc.perform(get("/api/v1/exceptions").param("page", Integer.toString(pages)).param("size", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void invalidParametersAre400ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/exceptions").param("size", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/exceptions").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exceptions").param("issueType", "NOT_A_TYPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exceptions").param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exceptions").param("skuId", "SKU-1")).andExpect(status().isBadRequest());
    }
}
