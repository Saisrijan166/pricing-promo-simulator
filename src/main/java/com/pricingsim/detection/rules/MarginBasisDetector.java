package com.pricingsim.detection.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.DetectedException;
import com.pricingsim.detection.DetectionRule;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.pricing.MarginCalculator;
import com.pricingsim.pricing.PricingPolicy;

/**
 * MARGIN_BEFORE_DISCOUNT: the margin recomputed on the net price ({@link MarginCalculator}) differs from the stored
 * {@code margin_pct} and is below {@code required_margin_pct}, i.e. the decision passed on the wrong basis.
 */
public final class MarginBasisDetector implements DetectionRule {

    @Override
    public IssueType issueType() {
        return IssueType.MARGIN_BEFORE_DISCOUNT;
    }

    @Override
    public String description() {
        return "Stored margin_pct differs from the net-price margin, which is below the required margin";
    }

    @Override
    public List<DetectedException> detect(JdbcTemplate jdbc, PricingPolicy policy) {
        List<DetectedException> out = new ArrayList<>();
        jdbc.query("SELECT decision_id, sku_id, effective_date, expected_net_price, landed_cost, margin_pct,"
                + " required_margin_pct FROM price_decision ORDER BY decision_id", rs -> {
                    BigDecimal net = rs.getBigDecimal("expected_net_price");
                    BigDecimal stored = rs.getBigDecimal("margin_pct");
                    BigDecimal required = rs.getBigDecimal("required_margin_pct");
                    BigDecimal onNet = MarginCalculator.netMargin(net, rs.getBigDecimal("landed_cost"));
                    if (onNet.compareTo(stored) != 0 && onNet.compareTo(required) < 0) {
                        out.add(DetectedException.of(issueType(), "price_decision", rs.getString("decision_id"),
                                rs.getString("sku_id"), rs.getObject("effective_date", LocalDate.class), List.of(),
                                stored.toPlainString(), onNet.toPlainString(), "stored margin " + stored.toPlainString()
                                        + ", net margin " + onNet.toPlainString() + " < required "
                                        + required.toPlainString()));
                    }
                });
        return out;
    }
}
