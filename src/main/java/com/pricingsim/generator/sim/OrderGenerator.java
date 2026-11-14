package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.CustomerSegment;
import com.pricingsim.domain.enums.OrderStatus;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.CustomerOrder;
import com.pricingsim.domain.model.OrderPromotion;
import com.pricingsim.generator.GenerationContext;
import com.pricingsim.generator.master.PromotionCatalog;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.pricing.MarginCalculator;
import com.pricingsim.pricing.PricingPolicy;
import com.pricingsim.pricing.PromotionOffer;
import com.pricingsim.pricing.PromotionStackResolver;

/**
 * Splits a SKU-day's units sold into {@code customer_order} rows and resolves coupons into {@code order_promotion}
 * (PLAN.md §7.2 steps 5.7–5.8, §8 order-level coupons).
 *
 * <ul>
 *   <li>FULFILLED orders of quantity 1–3 (60/30/10%) add up exactly to {@code units_sold}. About 2% of orders are
 *       CANCELLED; they come on top and are excluded from {@code units_sold}.</li>
 *   <li>Order time 06:00–23:59:59Z (after the day's 06:00 decision); channel ECOM 55% / STORE 45%; segment NEW 20% /
 *       REGULAR 55% / LOYALTY 25%; an opaque customer token from a pool of 40 per SKU (at least 1,000).</li>
 *   <li>The day's applied markdowns are recorded on every order (coupon_presented = false). 12% of orders present a
 *       coupon that is valid for the SKU, channel and time, and 15% of those present a second one. Coupons go through
 *       {@link PromotionStackResolver} seeded with the markdowns, so conflicting coupons are rejected with a reason.
 *       Order margin is not a rejection criterion for coupons (PLAN.md §8).</li>
 *   <li>Amounts: gross = qty × list; discount = Σ APPLIED order_promotion.discount_amount; net = gross − discount;
 *       gross margin = net − qty × landed cost.</li>
 *   <li>IDs follow (order_date, sku_id, order_ts) because the simulator visits SKUs in ID order each day.</li>
 * </ul>
 */
public final class OrderGenerator {

    static final double[] QUANTITY_WEIGHTS = {0.60, 0.30, 0.10};
    static final double CANCEL_SHARE = 0.02;
    static final double ECOM_SHARE = 0.55;
    static final double[] SEGMENT_WEIGHTS = {0.20, 0.55, 0.25};
    static final double COUPON_SHARE = 0.12;
    static final double SECOND_COUPON_SHARE = 0.15;
    static final int CUSTOMERS_PER_SKU = 40;
    static final int MIN_CUSTOMERS = 1_000;
    static final int FIRST_ORDER_SECOND = 6 * 3600;

    /** Order counts for the run summary. */
    public static final class Stats {
        long orders;
        long cancelled;
        long couponsPresented;
        long couponsApplied;
        long couponsRejected;

        public long orders() {
            return orders;
        }

        public long cancelled() {
            return cancelled;
        }

        public long couponsPresented() {
            return couponsPresented;
        }

        public long couponsApplied() {
            return couponsApplied;
        }

        public long couponsRejected() {
            return couponsRejected;
        }
    }

    private record Draft(int quantity, OrderStatus status, int secondOfDay, Channel channel, CustomerSegment segment,
                         int customer, List<PromotionOffer> coupons) {
    }

    /** Coupon rejections that are stacking conflicts (the PROMO_STACK_VIOLATION eligibility, PLAN.md §9). */
    public static final java.util.Set<ReasonCode> CONFLICT_REJECTIONS = java.util.EnumSet.of(
            ReasonCode.PROMO_REJECTED_EXCLUSIVE, ReasonCode.PROMO_REJECTED_EXCLUDED_PAIR,
            ReasonCode.PROMO_REJECTED_STACK_LIMIT, ReasonCode.PROMO_REJECTED_DISCOUNT_CAP);

    private final GenerationContext ctx;
    private final PromotionCatalog promotions;
    private final PricingPolicy policy;
    private final RowSink sink;
    private final SimulationHooks hooks;
    private final int customerPool;
    private final Stats stats = new Stats();

    public OrderGenerator(GenerationContext ctx, PromotionCatalog promotions, PricingPolicy policy, RowSink sink,
                          SimulationHooks hooks) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.promotions = Objects.requireNonNull(promotions, "promotions");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.customerPool = StrictMath.max(MIN_CUSTOMERS, ctx.profile().skus() * CUSTOMERS_PER_SKU);
    }

    public Stats stats() {
        return stats;
    }

    /** Writes the SKU-day's orders; their FULFILLED quantities add up to {@code unitsSold}. */
    void generate(SkuState state, LocalDate day, int unitsSold, PriceSetter.DailyPrice price, CostRecord cost,
                  String priceHistoryId) {
        Random r = state.orderRandom();
        List<Integer> quantities = new ArrayList<>();
        for (int remaining = unitsSold; remaining > 0; ) {
            int q = StrictMath.min(remaining, 1 + Distributions.weightedIndex(r, QUANTITY_WEIGHTS));
            quantities.add(q);
            remaining -= q;
        }
        List<Draft> drafts = new ArrayList<>();
        int fulfilled = quantities.size();
        for (int i = 0; i < fulfilled; i++) {
            drafts.add(draft(r, quantities.get(i), OrderStatus.FULFILLED, state.skuId(), day));
            if (Distributions.bernoulli(r, CANCEL_SHARE / (1 - CANCEL_SHARE))) {
                int q = 1 + Distributions.weightedIndex(r, QUANTITY_WEIGHTS);
                drafts.add(draft(r, q, OrderStatus.CANCELLED, state.skuId(), day));
            }
        }
        // Intra-day order: by time, then by generation order (total).
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            index.add(i);
        }
        index.sort(Comparator.comparingInt((Integer i) -> drafts.get(i).secondOfDay()).thenComparingInt(i -> i));
        int orderIndex = 0;
        for (int i : index) {
            write(drafts.get(i), state.skuId(), day, orderIndex++, price, cost, priceHistoryId);
        }
    }

    /**
     * The evaluations with {@code forced} switched to APPLIED and every applied coupon's discount recomputed in the
     * resolver's order (percentages first, then amounts, on the running amount after the seeded markdowns).
     */
    public static List<PromotionStackResolver.Evaluation> withForced(List<PromotionStackResolver.Evaluation> evaluations,
            PromotionStackResolver.Evaluation forced, BigDecimal gross, BigDecimal seededTotal) {
        List<com.pricingsim.pricing.PromotionOffer> offers = new ArrayList<>();
        for (PromotionStackResolver.Evaluation e : evaluations) {
            if (e.status() == ApplicationStatus.APPLIED || e.promotionId().equals(forced.promotionId())) {
                offers.add(e.offer());
            }
        }
        List<PromotionStackResolver.Applied> discounts = PromotionStackResolver.discounts(gross, seededTotal, offers);
        List<PromotionStackResolver.Evaluation> out = new ArrayList<>(evaluations.size());
        for (PromotionStackResolver.Evaluation e : evaluations) {
            PromotionStackResolver.Applied a = discounts.stream()
                    .filter(x -> x.offer().promotionId().equals(e.promotionId())).findFirst().orElse(null);
            out.add(a == null ? e : new PromotionStackResolver.Evaluation(e.offer(), e.sequenceNo(),
                    ApplicationStatus.APPLIED, ReasonCode.PROMO_APPLIED, a.discount()));
        }
        return out;
    }

    /**
     * The first coupon rejected for a stacking conflict whose forced application still leaves a positive net amount,
     * or null.
     */
    public static PromotionStackResolver.Evaluation forceableConflict(PromotionStackResolver.Resolution resolution,
                                                                      BigDecimal gross, BigDecimal seededTotal) {
        for (PromotionStackResolver.Evaluation e : resolution.evaluations()) {
            if (e.status() == ApplicationStatus.REJECTED && CONFLICT_REJECTIONS.contains(e.reason())) {
                BigDecimal discount = seededTotal;
                for (PromotionStackResolver.Evaluation f : withForced(resolution.evaluations(), e, gross, seededTotal)) {
                    if (f.status() == ApplicationStatus.APPLIED) {
                        discount = discount.add(f.discount());
                    }
                }
                if (gross.subtract(discount).signum() > 0) {
                    return e;
                }
            }
        }
        return null;
    }

    private Draft draft(Random r, int quantity, OrderStatus status, String skuId, LocalDate day) {
        int second = Distributions.uniformInt(r, FIRST_ORDER_SECOND, 86_399);
        Channel channel = Distributions.bernoulli(r, ECOM_SHARE) ? Channel.ECOM : Channel.STORE;
        CustomerSegment segment = CustomerSegment.values()[Distributions.weightedIndex(r, SEGMENT_WEIGHTS)];
        int customer = 1 + r.nextInt(customerPool);
        boolean presents = Distributions.bernoulli(r, COUPON_SHARE);
        boolean second2 = Distributions.bernoulli(r, SECOND_COUPON_SHARE);
        int pick1 = r.nextInt(1_000_000);
        int pick2 = r.nextInt(1_000_000);
        List<PromotionOffer> coupons = new ArrayList<>(2);
        if (presents) {
            Instant at = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(second);
            List<PromotionOffer> valid = promotions.couponsFor(skuId).stream()
                    .filter(o -> o.eligible(day, at, channel)).toList();
            if (!valid.isEmpty()) {
                coupons.add(valid.get(pick1 % valid.size()));
                if (second2 && valid.size() > 1) {
                    List<PromotionOffer> rest = new ArrayList<>(valid);
                    rest.remove(coupons.get(0));
                    coupons.add(rest.get(pick2 % rest.size()));
                }
            }
        }
        return new Draft(quantity, status, second, channel, segment, customer, coupons);
    }

    private void write(Draft d, String skuId, LocalDate day, int orderIndex, PriceSetter.DailyPrice price,
                       CostRecord cost, String priceHistoryId) {
        String orderId = ctx.nextId(IdFactory.Sequence.ORDER, day.getYear());
        BigDecimal qty = BigDecimal.valueOf(d.quantity());
        BigDecimal gross = price.listPrice().multiply(qty);

        List<PromotionStackResolver.Applied> seeded = new ArrayList<>();
        for (PromotionStackResolver.Applied m : price.markdowns()) {
            seeded.add(new PromotionStackResolver.Applied(m.offer(), m.discount().multiply(qty)));
        }
        PromotionStackResolver.Resolution res = PromotionStackResolver.resolve(new PromotionStackResolver.Request(
                gross, seeded, d.coupons(), day, d.quantity(), false, policy));
        BigDecimal seededTotal = PromotionStackResolver.total(seeded);
        List<PromotionStackResolver.Evaluation> evaluations = res.evaluations();
        PromotionStackResolver.Evaluation forced = hooks.couponToForce(skuId, day, orderIndex, res, gross, seededTotal);
        if (forced != null) {
            evaluations = withForced(evaluations, forced, gross, seededTotal);
        }

        int seq = 0;
        BigDecimal discount = BigDecimal.ZERO.setScale(2);
        for (PromotionStackResolver.Applied m : seeded) {
            sink.accept(TableRegistry.ORDER_PROMOTION, new OrderPromotion(orderId, m.offer().promotionId(), ++seq,
                    false, ApplicationStatus.APPLIED, null, m.discount()));
            discount = discount.add(m.discount());
        }
        for (PromotionStackResolver.Evaluation e : evaluations) {
            boolean applied = e.status() == ApplicationStatus.APPLIED;
            sink.accept(TableRegistry.ORDER_PROMOTION, new OrderPromotion(orderId, e.promotionId(), ++seq, true,
                    e.status(), applied ? null : e.reason(), e.discount()));
            discount = discount.add(e.discount());
            stats.couponsPresented++;
            if (applied) {
                stats.couponsApplied++;
            } else {
                stats.couponsRejected++;
            }
        }
        BigDecimal net = gross.subtract(discount);
        if (net.signum() <= 0) {
            throw new IllegalStateException(orderId + ": non-positive net amount " + net);
        }
        Instant ts = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(d.secondOfDay());
        CustomerOrder order = new CustomerOrder(orderId, ts, day, skuId,
                IdFactory.customerRef(d.customer()), d.segment(), d.channel(), d.quantity(), priceHistoryId,
                cost.costRecordId(), price.listPrice(), gross, discount, net, cost.landedCost(),
                MarginCalculator.grossMarginAmount(net, d.quantity(), cost.landedCost()), d.status());
        sink.accept(TableRegistry.CUSTOMER_ORDER, order);
        hooks.afterOrder(order, forced == null ? null : forced.promotionId());
        stats.orders++;
        if (d.status() == OrderStatus.CANCELLED) {
            stats.cancelled++;
        }
    }
}
