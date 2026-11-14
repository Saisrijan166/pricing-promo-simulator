package com.pricingsim.api.dto;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** One detected exception (a detector finding, never a label). */
public record ExceptionView(String exceptionId, String runId, String category, String issueType, String entityType,
                            String entityId, String skuId, LocalDate eventDate, List<String> relatedIds,
                            String violatedRule, String observedValue, String expectedValue, String recommendedAction,
                            String detail) {

    public static ExceptionView of(Map<String, Object> r) {
        String related = (String) r.get("related_ids");
        return new ExceptionView((String) r.get("exception_id"), (String) r.get("run_id"), (String) r.get("category"),
                (String) r.get("issue_type"), (String) r.get("entity_type"), (String) r.get("entity_id"),
                (String) r.get("sku_id"), (LocalDate) r.get("event_date"),
                related == null ? List.of() : Arrays.asList(related.split("\\|")), (String) r.get("violated_rule"),
                (String) r.get("observed_value"), (String) r.get("expected_value"),
                (String) r.get("recommended_action"), (String) r.get("detail"));
    }
}
