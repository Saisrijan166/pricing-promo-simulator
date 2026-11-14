package com.pricingsim.detection.rules;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.DetectedException;
import com.pricingsim.detection.DetectionRule;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.pricing.CompetitorReferenceResolver;
import com.pricingsim.pricing.PricingPolicy;

/**
 * STALE_OBSERVATION_AS_CURRENT: a decision that USED an observation captured more than the freshness window
 * (72 h) before {@code decision_ts}. The age is recomputed from the timestamps, not taken from {@code age_hours}.
 */
public final class StaleObservationDetector implements DetectionRule {

    @Override
    public IssueType issueType() {
        return IssueType.STALE_OBSERVATION_AS_CURRENT;
    }

    @Override
    public String description() {
        return "Decision observation USED although captured more than 72 h before decision_ts";
    }

    @Override
    public List<DetectedException> detect(JdbcTemplate jdbc, PricingPolicy policy) {
        List<DetectedException> out = new ArrayList<>();
        String[] last = {null};
        jdbc.query("SELECT b.decision_id, b.observation_id, d.sku_id, d.effective_date, d.decision_ts,"
                + " o.source_captured_at FROM price_decision_observation b"
                + " JOIN price_decision d ON d.decision_id = b.decision_id"
                + " JOIN competitor_observation o ON o.observation_id = b.observation_id"
                + " WHERE b.usage = 'USED' ORDER BY b.decision_id, b.observation_id", rs -> {
                    OffsetDateTime ts = rs.getObject("decision_ts", OffsetDateTime.class);
                    OffsetDateTime captured = rs.getObject("source_captured_at", OffsetDateTime.class);
                    long age = Duration.between(captured.toInstant(), ts.toInstant()).getSeconds();
                    String decisionId = rs.getString("decision_id");
                    if (age > policy.freshnessSeconds() && !decisionId.equals(last[0])) {
                        last[0] = decisionId;
                        out.add(DetectedException.of(issueType(), "price_decision", decisionId, rs.getString("sku_id"),
                                rs.getObject("effective_date", LocalDate.class),
                                List.of(rs.getString("observation_id")),
                                CompetitorReferenceResolver.ageHours(age).toPlainString(),
                                Integer.toString(policy.freshnessHours()),
                                "observation " + rs.getString("observation_id") + " used at age "
                                        + CompetitorReferenceResolver.ageHours(age).toPlainString() + " h"));
                    }
                });
        return out;
    }
}
