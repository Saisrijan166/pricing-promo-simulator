package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.PromoType;
import com.pricingsim.domain.enums.RuleType;
import com.pricingsim.domain.model.Promotion;
import com.pricingsim.domain.model.PromotionRule;

/**
 * A promotion with its rules and cancellation time, as the stack resolver sees it.
 *
 * @param promotion   the promotion row
 * @param rules       its rules (each applies only on dates inside the rule's effective window)
 * @param cancelledAt when it was cancelled, or {@code null}; nothing is applied at or after this instant
 */
public record PromotionOffer(Promotion promotion, List<PromotionRule> rules, Instant cancelledAt) {

    public PromotionOffer {
        Objects.requireNonNull(promotion, "promotion");
        rules = List.copyOf(rules);
        for (PromotionRule rule : rules) {
            if (!rule.promotionId().equals(promotion.promotionId())) {
                throw new IllegalArgumentException("rule " + rule.ruleId() + " belongs to another promotion");
            }
        }
    }

    public String promotionId() {
        return promotion.promotionId();
    }

    public boolean isMarkdown() {
        return promotion.promoType() == PromoType.MARKDOWN_PCT || promotion.promoType() == PromoType.MARKDOWN_AMOUNT;
    }

    public boolean isPercent() {
        return promotion.promoType() == PromoType.MARKDOWN_PCT || promotion.promoType() == PromoType.COUPON_PCT;
    }

    /**
     * Eligible on {@code date} at instant {@code at} for {@code channel}: the date is inside the promotion window, it
     * is not cancelled by then, and its channel covers the requested one. (Whether the SKU is in the promotion is the
     * caller's job: offers are looked up per SKU.)
     */
    public boolean eligible(LocalDate date, Instant at, Channel channel) {
        Promotion p = promotion;
        boolean inWindow = !date.isBefore(p.startDate()) && !date.isAfter(p.endDate());
        boolean notCancelled = cancelledAt == null || at.isBefore(cancelledAt);
        return inWindow && notCancelled && p.channel().covers(channel);
    }

    /** {@code true} if the promotion has an EXCLUSIVE rule effective on {@code date}. */
    public boolean exclusive(LocalDate date) {
        return rulesOn(date, RuleType.EXCLUSIVE).findAny().isPresent();
    }

    /** {@code true} if this promotion excludes {@code otherId} on {@code date}. */
    public boolean excludes(String otherId, LocalDate date) {
        return rulesOn(date, RuleType.EXCLUDES_PROMOTION).anyMatch(r -> otherId.equals(r.targetPromotionId()));
    }

    /** Smallest numeric parameter of a rule type effective on {@code date}, if any. */
    public Optional<BigDecimal> param(RuleType type, LocalDate date) {
        return rulesOn(date, type).map(PromotionRule::paramNumeric).filter(Objects::nonNull).min(BigDecimal::compareTo);
    }

    private java.util.stream.Stream<PromotionRule> rulesOn(LocalDate date, RuleType type) {
        return rules.stream().filter(r -> r.ruleType() == type
                && !date.isBefore(r.effectiveFrom()) && !date.isAfter(r.effectiveTo()));
    }
}
