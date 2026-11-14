package com.pricingsim.detection.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.DetectedException;
import com.pricingsim.detection.DetectionRule;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.pricing.GuardrailCalculator;
import com.pricingsim.pricing.PricingPolicy;

/**
 * PRICE_BELOW_FLOOR: a decision whose {@code final_list_price} is below the floor recomputed from its cost record and
 * the product (PLAN.md §8 S2, with the same {@link GuardrailCalculator} as the engine). A guardrail conflict prices at
 * the floor, so it is not a finding.
 */
public final class PriceBelowFloorDetector implements DetectionRule {

    @Override
    public IssueType issueType() {
        return IssueType.PRICE_BELOW_FLOOR;
    }

    @Override
    public String description() {
        return "Decision final_list_price below the floor recomputed from its cost record and product";
    }

    @Override
    public List<DetectedException> detect(JdbcTemplate jdbc, PricingPolicy policy) {
        List<DetectedException> out = new ArrayList<>();
        jdbc.query("SELECT d.decision_id, d.sku_id, d.effective_date, d.final_list_price, d.cost_record_id,"
                + " c.landed_cost, p.min_margin_pct, p.map_price FROM price_decision d"
                + " JOIN cost_record c ON c.cost_record_id = d.cost_record_id JOIN product p ON p.sku_id = d.sku_id"
                + " ORDER BY d.decision_id", rs -> {
                    BigDecimal floor = GuardrailCalculator.floor(rs.getBigDecimal("landed_cost"),
                            rs.getBigDecimal("min_margin_pct"), rs.getBigDecimal("map_price"));
                    BigDecimal list = rs.getBigDecimal("final_list_price");
                    if (list.compareTo(floor) < 0) {
                        out.add(DetectedException.of(issueType(), "price_decision", rs.getString("decision_id"),
                                rs.getString("sku_id"), rs.getObject("effective_date", LocalDate.class),
                                List.of(rs.getString("cost_record_id")), list.toPlainString(), floor.toPlainString(),
                                "final_list_price " + list.toPlainString() + " < floor " + floor.toPlainString()));
                    }
                });
        return out;
    }
}
