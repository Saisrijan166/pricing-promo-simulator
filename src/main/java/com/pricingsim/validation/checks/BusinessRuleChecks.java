package com.pricingsim.validation.checks;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.DetectedException;
import com.pricingsim.detection.DetectionRule;
import com.pricingsim.detection.rules.BusinessRules;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.pricing.PricingPolicy;
import com.pricingsim.validation.CheckCategory;
import com.pricingsim.validation.ValidationCheck;

/**
 * BUSINESS_RULE BR-001..005 (PLAN.md §10): the five anomaly rules, run with the same detectors as {@code detect}.
 * Findings are WARN, not FAIL; {@code evaluate} reconciles them with the ground truth.
 */
public final class BusinessRuleChecks {

    private BusinessRuleChecks() {
    }

    /** A business-rule check and the issue type it counts. */
    public interface RuleCheck extends ValidationCheck.Computed {
        IssueType issueType();
    }

    public static List<ValidationCheck> all() {
        List<ValidationCheck> out = new ArrayList<>();
        for (DetectionRule rule : BusinessRules.all()) {
            out.add(new RuleCheck() {
                @Override
                public IssueType issueType() {
                    return rule.issueType();
                }

                @Override
                public CheckCategory category() {
                    return CheckCategory.BUSINESS_RULE;
                }

                @Override
                public String table() {
                    return switch (rule.issueType()) {
                        case PROMO_STACK_VIOLATION -> "customer_order";
                        case PRICE_CHANGE_WITHOUT_REASON -> "price_history";
                        default -> "price_decision";
                    };
                }

                @Override
                public String description() {
                    return rule.issueType() + ": " + rule.description();
                }

                @Override
                public List<String> violations(JdbcTemplate jdbc, PricingPolicy policy) {
                    return rule.detect(jdbc, policy).stream().map(DetectedException::entityId).toList();
                }
            });
        }
        return out;
    }
}
