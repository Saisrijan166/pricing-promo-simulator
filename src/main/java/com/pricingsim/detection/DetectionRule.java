package com.pricingsim.detection;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.pricing.PricingPolicy;

/**
 * One rule-based detector over the loaded data (PLAN.md §9 "Detection rule"). Rules re-derive the business rules from
 * stored inputs with the same calculators the engine uses; they never read labels.
 */
public interface DetectionRule {

    IssueType issueType();

    /** One-line description of what is checked. */
    String description();

    /** Findings, one per anomalous entity, in entity-ID order. */
    List<DetectedException> detect(JdbcTemplate jdbc, PricingPolicy policy);
}
