package com.pricingsim.detection.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.DetectedException;
import com.pricingsim.detection.DetectionRule;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.pricing.PricingPolicy;

/**
 * PRICE_CHANGE_WITHOUT_REASON: a {@code price_history} row whose list price differs from the SKU's previous day and
 * that has no {@code change_reason} or no {@code source_decision_id}. Only the first row of such a span changes the
 * price, so a span is reported once.
 */
public final class UnexplainedPriceChangeDetector implements DetectionRule {

    @Override
    public IssueType issueType() {
        return IssueType.PRICE_CHANGE_WITHOUT_REASON;
    }

    @Override
    public String description() {
        return "List price changed from the previous day without a change_reason or a source decision";
    }

    @Override
    public List<DetectedException> detect(JdbcTemplate jdbc, PricingPolicy policy) {
        List<DetectedException> out = new ArrayList<>();
        String[] prevSku = {null};
        BigDecimal[] prevList = {null};
        jdbc.query("SELECT price_history_id, sku_id, price_date, list_price, change_reason, source_decision_id"
                + " FROM price_history ORDER BY sku_id, price_date", rs -> {
                    String sku = rs.getString("sku_id");
                    BigDecimal list = rs.getBigDecimal("list_price");
                    boolean changed = sku.equals(prevSku[0]) && list.compareTo(prevList[0]) != 0;
                    boolean unexplained = rs.getString("change_reason") == null
                            || rs.getString("source_decision_id") == null;
                    if (changed && unexplained) {
                        out.add(DetectedException.of(issueType(), "price_history", rs.getString("price_history_id"),
                                sku, rs.getObject("price_date", LocalDate.class), List.of(), list.toPlainString(),
                                prevList[0].toPlainString(), "list price " + prevList[0].toPlainString() + " -> "
                                        + list.toPlainString() + " with no reason or decision"));
                    }
                    prevSku[0] = sku;
                    prevList[0] = list;
                });
        out.sort(java.util.Comparator.comparing(DetectedException::entityId));
        return out;
    }
}
