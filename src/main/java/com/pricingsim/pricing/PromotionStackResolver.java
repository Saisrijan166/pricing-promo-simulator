package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.enums.RuleType;

/**
 * Promotion stacking and exclusion (PLAN.md §8 S10), shared by price decisions (markdowns) and orders (coupons).
 *
 * <p>Candidates are evaluated in a <b>total order</b>: priority ascending (1 is highest), then promotion ID. The result
 * therefore never depends on input order. Each candidate is added greedily unless, checked in this order:
 * <ol>
 *   <li>its MIN_ORDER_QTY exceeds the quantity → PROMO_REJECTED_MIN_QTY (a price decision prices one unit);</li>
 *   <li>it is EXCLUSIVE and something is already applied, or an applied promotion is EXCLUSIVE →
 *       PROMO_REJECTED_EXCLUSIVE;</li>
 *   <li>it and an applied promotion exclude each other (EXCLUDES_PROMOTION, either direction) →
 *       PROMO_REJECTED_EXCLUDED_PAIR;</li>
 *   <li>the stack count would exceed the minimum MAX_STACK_COUNT over the applied set plus the candidate →
 *       PROMO_REJECTED_STACK_LIMIT;</li>
 *   <li>the combined discount would exceed the minimum MAX_COMBINED_DISCOUNT_PCT → PROMO_REJECTED_DISCOUNT_CAP.</li>
 * </ol>
 * When the SKU is stocked out every candidate is rejected with PROMO_SUPPRESSED_STOCKOUT.
 *
 * <p>Discounts apply <b>sequentially on the running price</b>: first any seeded (already applied) discounts, then
 * percentage promotions, then fixed amounts; each step is rounded to the paisa (HALF_UP), so the discounts always sum
 * exactly to {@code base − net}.
 */
public final class PromotionStackResolver {

    /** Evaluation order: priority ascending, then promotion ID ascending. */
    public static final Comparator<PromotionOffer> PRECEDENCE = Comparator
            .comparingInt((PromotionOffer o) -> o.promotion().priority())
            .thenComparing(PromotionOffer::promotionId);

    private PromotionStackResolver() {
    }

    /** A promotion applied to a line and the amount it took off. */
    public record Applied(PromotionOffer offer, BigDecimal discount) {
    }

    /** The outcome for one candidate; {@code discount} is 0 when rejected. */
    public record Evaluation(PromotionOffer offer, int sequenceNo, ApplicationStatus status, ReasonCode reason,
                             BigDecimal discount) {
        public String promotionId() {
            return offer.promotionId();
        }
    }

    /**
     * @param base       line amount before any discount (unit list price for a decision; quantity × list for an order)
     * @param seeded     discounts already applied (for an order: the markdowns of the day's decision), fixed amounts
     * @param candidates promotions to evaluate (any order)
     * @param date       business date (rules apply only inside their effective window)
     * @param quantity   units on the line (1 for a decision)
     * @param suppressed reject everything with PROMO_SUPPRESSED_STOCKOUT
     */
    public record Request(BigDecimal base, List<Applied> seeded, List<PromotionOffer> candidates, LocalDate date,
                          int quantity, boolean suppressed, PricingPolicy policy) {
        public Request {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(policy, "policy");
            seeded = List.copyOf(seeded);
            candidates = List.copyOf(candidates);
            if (base.signum() <= 0) {
                throw new IllegalArgumentException("base must be > 0");
            }
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be >= 1");
            }
        }
    }

    /**
     * @param evaluations every candidate in evaluation order
     * @param applied     newly applied candidates with their final discounts, in application order (percent first)
     * @param net         line amount after seeded and newly applied discounts
     */
    public record Resolution(List<Evaluation> evaluations, List<Applied> applied, BigDecimal net) {
        public List<PromotionOffer> appliedOffers() {
            return applied.stream().map(Applied::offer).toList();
        }
    }

    public static Resolution resolve(Request request) {
        List<PromotionOffer> ordered = new ArrayList<>(request.candidates());
        ordered.sort(PRECEDENCE);
        List<PromotionOffer> seededOffers = request.seeded().stream().map(Applied::offer).toList();
        BigDecimal seededTotal = total(request.seeded());

        List<PromotionOffer> chosen = new ArrayList<>();
        List<ReasonCode> reasons = new ArrayList<>();
        for (PromotionOffer candidate : ordered) {
            ReasonCode reason = request.suppressed() ? ReasonCode.PROMO_SUPPRESSED_STOCKOUT
                    : check(request, seededOffers, seededTotal, chosen, candidate);
            reasons.add(reason);
            if (reason == ReasonCode.PROMO_APPLIED) {
                chosen.add(candidate);
            }
        }

        List<Applied> applied = discounts(request.base(), seededTotal, chosen);
        List<Evaluation> evaluations = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            PromotionOffer offer = ordered.get(i);
            ReasonCode reason = reasons.get(i);
            boolean ok = reason == ReasonCode.PROMO_APPLIED;
            BigDecimal discount = ok ? discountOf(applied, offer) : zero();
            evaluations.add(new Evaluation(offer, i + 1, ok ? ApplicationStatus.APPLIED : ApplicationStatus.REJECTED,
                    reason, discount));
        }
        return new Resolution(List.copyOf(evaluations), applied,
                request.base().subtract(seededTotal).subtract(total(applied)));
    }

    private static ReasonCode check(Request request, List<PromotionOffer> seeded, BigDecimal seededTotal,
                                    List<PromotionOffer> chosen, PromotionOffer candidate) {
        LocalDate date = request.date();
        List<PromotionOffer> current = new ArrayList<>(seeded);
        current.addAll(chosen);

        BigDecimal minQty = candidate.param(RuleType.MIN_ORDER_QTY, date).orElse(null);
        if (minQty != null && BigDecimal.valueOf(request.quantity()).compareTo(minQty) < 0) {
            return ReasonCode.PROMO_REJECTED_MIN_QTY;
        }
        if ((candidate.exclusive(date) && !current.isEmpty()) || current.stream().anyMatch(o -> o.exclusive(date))) {
            return ReasonCode.PROMO_REJECTED_EXCLUSIVE;
        }
        for (PromotionOffer other : current) {
            if (candidate.excludes(other.promotionId(), date) || other.excludes(candidate.promotionId(), date)) {
                return ReasonCode.PROMO_REJECTED_EXCLUDED_PAIR;
            }
        }
        List<PromotionOffer> withCandidate = new ArrayList<>(current);
        withCandidate.add(candidate);
        int maxStack = withCandidate.stream()
                .map(o -> o.param(RuleType.MAX_STACK_COUNT, date))
                .flatMap(java.util.Optional::stream)
                .map(BigDecimal::intValue)
                .min(Integer::compare)
                .orElse(request.policy().defaultMaxStack());
        if (withCandidate.size() > maxStack) {
            return ReasonCode.PROMO_REJECTED_STACK_LIMIT;
        }
        BigDecimal cap = withCandidate.stream()
                .map(o -> o.param(RuleType.MAX_COMBINED_DISCOUNT_PCT, date))
                .flatMap(java.util.Optional::stream)
                .min(BigDecimal::compareTo)
                .orElse(request.policy().defaultMaxCombinedDiscount());
        List<PromotionOffer> trial = new ArrayList<>(chosen);
        trial.add(candidate);
        BigDecimal discount = seededTotal.add(total(discounts(request.base(), seededTotal, trial)));
        if (discount.compareTo(cap.multiply(request.base())) > 0) {
            return ReasonCode.PROMO_REJECTED_DISCOUNT_CAP;
        }
        return ReasonCode.PROMO_APPLIED;
    }

    /**
     * Discounts of {@code offers} on {@code base − seededTotal}: percentage promotions first, then amounts, each in the
     * given order, each step rounded to the paisa. The result is in application order.
     */
    public static List<Applied> discounts(BigDecimal base, BigDecimal seededTotal, List<PromotionOffer> offers) {
        BigDecimal running = base.subtract(seededTotal);
        List<Applied> out = new ArrayList<>(offers.size());
        for (PromotionOffer o : offers) {
            if (o.isPercent()) {
                BigDecimal d = running.multiply(o.promotion().discountPct()).setScale(2, RoundingMode.HALF_UP);
                running = running.subtract(d);
                out.add(new Applied(o, d));
            }
        }
        for (PromotionOffer o : offers) {
            if (!o.isPercent()) {
                BigDecimal d = o.promotion().discountAmount().setScale(2, RoundingMode.HALF_UP);
                running = running.subtract(d);
                out.add(new Applied(o, d));
            }
        }
        return List.copyOf(out);
    }

    public static BigDecimal total(List<Applied> applied) {
        BigDecimal sum = zero();
        for (Applied a : applied) {
            sum = sum.add(a.discount());
        }
        return sum;
    }

    private static BigDecimal discountOf(List<Applied> applied, PromotionOffer offer) {
        return applied.stream().filter(a -> a.offer().promotionId().equals(offer.promotionId()))
                .map(Applied::discount).findFirst().orElseThrow();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }
}
