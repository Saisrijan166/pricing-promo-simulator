package com.pricingsim.generator.master;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import com.pricingsim.domain.enums.ActorRole;
import com.pricingsim.domain.enums.Category;
import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.FundingSource;
import com.pricingsim.domain.enums.PromoType;
import com.pricingsim.domain.enums.PromotionStatus;
import com.pricingsim.domain.enums.RuleType;
import com.pricingsim.domain.enums.StackGroup;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.Product;
import com.pricingsim.domain.model.Promotion;
import com.pricingsim.domain.model.PromotionProduct;
import com.pricingsim.domain.model.PromotionRule;
import com.pricingsim.domain.model.PromotionStatusEvent;
import com.pricingsim.generator.GenerationContext;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.pricing.PromotionOffer;

/**
 * Generates {@code promotion}, {@code promotion_rule}, {@code promotion_product} and {@code promotion_status_event}
 * (PLAN.md §7.2 step 3).
 *
 * <ul>
 *   <li>60% markdowns (shelf-level, channel ALL), Monday to Sunday for 1–4 whole weeks; 40% coupons (customer-presented,
 *       any channel, 3–21 days, with a coupon code). The mix is apportioned exactly.</li>
 *   <li>Targets: a SKU list from one category or from any category, 5–150 SKUs. Markdown lists are small (about 0.1–0.4%
 *       of SKUs, at least 5) so that 15–30% of SKU-days carry a markdown (PLAN.md §7.5); coupon lists are larger
 *       (20–150).</li>
 *   <li>Rules: every promotion has MAX_STACK_COUNT (2 or 3) and MAX_COMBINED_DISCOUNT_PCT (0.30–0.45); 30% are
 *       EXCLUSIVE; 25% get an EXCLUDES_PROMOTION pair with an overlapping promotion that shares SKUs (the conflicting
 *       coupons); 15% of coupons need MIN_ORDER_QTY 2 (a markdown prices one unit, so the rule is coupon-only).</li>
 *   <li>Lifecycle DRAFT → SCHEDULED → ACTIVE (start 00:00Z) → EXPIRED (end + 1 00:00Z). 3% are cancelled before they
 *       start and 1% while active. An active markdown is stopped at a Monday 00:00Z, when the weekly review re-prices,
 *       so no shelf price carries a cancelled markdown. Events after the last simulated day are not written, so the
 *       status is the as-of status on that day.</li>
 *   <li>IDs: PROMO in (start_date, promo_type, generation index) order; RULE in promotion order, then rule order; CPN in
 *       promotion order; PMEV in (event_ts, promotion_id) order (PLAN.md §6).</li>
 * </ul>
 */
public final class PromotionGenerator {

    static final double[] TYPE_WEIGHTS = {0.45, 0.15, 0.24, 0.16}; // MARKDOWN_PCT, MARKDOWN_AMOUNT, COUPON_PCT, COUPON_AMOUNT
    static final double[] CANCEL_WEIGHTS = {0.96, 0.03, 0.01};     // none, before start, while active
    static final List<BigDecimal> MARKDOWN_PCTS = decimals("0.05", "0.10", "0.15", "0.20", "0.25", "0.30");
    static final List<BigDecimal> COUPON_PCTS = decimals("0.05", "0.10", "0.15", "0.20");
    static final List<BigDecimal> COMBINED_CAPS = decimals("0.30", "0.35", "0.40", "0.45");
    static final double EXCLUSIVE_SHARE = 0.30;
    static final double PAIR_SHARE = 0.25;
    static final double MIN_QTY_SHARE = 0.15;
    static final double CATEGORY_TARGET_SHARE = 0.45;
    static final double SITEWIDE_SHARE = 0.05;
    static final double VENDOR_SHARE = 0.40;

    private final GenerationContext ctx;

    public PromotionGenerator(GenerationContext ctx) {
        this.ctx = ctx;
    }

    private static final class Draft {
        int index;
        PromoType type;
        StackGroup group;
        LocalDate start;
        LocalDate end;
        List<String> skus;
        BigDecimal pct;
        BigDecimal amount;
        int priority;
        Channel channel;
        FundingSource funding;
        Instant createdAt;
        Instant scheduledAt;
        boolean exclusive;
        boolean wantsPair;
        int maxStack;
        BigDecimal cap;
        boolean minQty;
        String name;
        // assigned later
        String id;
        String couponCode;
        Instant cancelledAt;
        boolean cancelledBeforeStart;
        String pairTarget;

        boolean markdown() {
            return type == PromoType.MARKDOWN_PCT || type == PromoType.MARKDOWN_AMOUNT;
        }
    }

    public PromotionCatalog generate(List<Product> products, RowSink sink) {
        int n = ctx.profile().promotions();
        if (n == 0) {
            return PromotionCatalog.empty();
        }
        List<PromoType> types = new ArrayList<>(n);
        int[] typeCounts = Distributions.apportion(n, TYPE_WEIGHTS);
        for (int t = 0; t < PromoType.values().length; t++) {
            for (int k = 0; k < typeCounts[t]; k++) {
                types.add(PromoType.values()[t]);
            }
        }
        Distributions.shuffle(ctx.streams().of("promotion-type"), types);

        List<Draft> drafts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            drafts.add(draft(i, types.get(i), products));
        }

        // PROMO IDs: (start_date, promo_type, generation index). Coupon codes in promotion order.
        drafts.sort(Comparator.comparing((Draft d) -> d.start).thenComparing(d -> d.type).thenComparingInt(d -> d.index));
        for (Draft d : drafts) {
            d.id = ctx.nextId(IdFactory.Sequence.PROMOTION, d.start.getYear());
        }
        for (Draft d : drafts) {
            if (!d.markdown()) {
                d.couponCode = ctx.nextId(IdFactory.Sequence.COUPON, d.start.getYear());
            }
        }
        assignCancellations(drafts);
        assignPairs(drafts);

        Map<String, List<PromotionOffer>> markdowns = new TreeMap<>();
        Map<String, List<PromotionOffer>> coupons = new TreeMap<>();
        List<Promotion> promotions = new ArrayList<>(n);
        List<PromotionStatusEvent> events = new ArrayList<>();
        for (Draft d : drafts) {
            List<PromotionStatusEvent> lifecycle = lifecycle(d);
            events.addAll(lifecycle);
            PromotionStatus status = lifecycle.get(lifecycle.size() - 1).toStatus();
            Promotion p = new Promotion(d.id, d.name, d.type, d.group, d.pct, d.amount, d.couponCode, d.start, d.end,
                    d.priority, d.channel, d.funding, status, d.createdAt);
            promotions.add(p);
            sink.accept(TableRegistry.PROMOTION, p);

            List<PromotionRule> rules = rules(d);
            rules.forEach(r -> sink.accept(TableRegistry.PROMOTION_RULE, r));
            PromotionOffer offer = new PromotionOffer(p, rules, d.cancelledAt);
            for (String sku : d.skus) {
                sink.accept(TableRegistry.PROMOTION_PRODUCT, new PromotionProduct(d.id, sku, d.createdAt));
                (d.markdown() ? markdowns : coupons).computeIfAbsent(sku, k -> new ArrayList<>()).add(offer);
            }
        }
        events.sort(Comparator.comparing(PromotionStatusEvent::eventTs).thenComparing(PromotionStatusEvent::promotionId));
        for (PromotionStatusEvent e : events) {
            int year = e.eventTs().atZone(ZoneOffset.UTC).getYear();
            sink.accept(TableRegistry.PROMOTION_STATUS_EVENT, new PromotionStatusEvent(
                    ctx.nextId(IdFactory.Sequence.PROMOTION_EVENT, year), e.promotionId(), e.fromStatus(),
                    e.toStatus(), e.eventTs(), e.actorRole(), e.note()));
        }
        markdowns.replaceAll((k, v) -> sortedById(v));
        coupons.replaceAll((k, v) -> sortedById(v));
        return new PromotionCatalog(promotions, markdowns, coupons);
    }

    private Draft draft(int index, PromoType type, List<Product> products) {
        Random r = ctx.streams().of("promotion", "G" + index);
        Draft d = new Draft();
        d.index = index;
        d.type = type;
        LocalDate windowStart = ctx.windowStart();
        LocalDate windowEnd = ctx.windowEnd();

        // Fixed draw order for every promotion.
        double targetU = r.nextDouble();
        int weeks = Distributions.uniformInt(r, 1, 4);
        int couponOffset = Distributions.uniformInt(r, 0, ctx.profile().days() - 1);
        int couponDays = Distributions.uniformInt(r, 3, 21);
        double sizeU = r.nextDouble();
        double markdownShare = Distributions.uniform(r, 0.001, 0.004);
        BigDecimal pct = Distributions.choice(r, d.markdown() ? MARKDOWN_PCTS : COUPON_PCTS);
        double amountRatio = d.markdown() ? Distributions.uniform(r, 0.05, 0.15) : Distributions.uniform(r, 0.05, 0.12);
        double channelU = r.nextDouble();
        boolean vendor = Distributions.bernoulli(r, VENDOR_SHARE);
        int createdLead = Distributions.uniformInt(r, 7, 30);
        int createdSecond = Distributions.uniformInt(r, 9 * 3600, 18 * 3600 - 1);
        int scheduleLag = Distributions.uniformInt(r, 1, 5);
        d.exclusive = Distributions.bernoulli(r, EXCLUSIVE_SHARE);
        d.wantsPair = Distributions.bernoulli(r, PAIR_SHARE);
        d.maxStack = Distributions.uniformInt(r, 2, 3);
        d.cap = Distributions.choice(r, COMBINED_CAPS);
        d.minQty = !d.markdown() && Distributions.bernoulli(r, MIN_QTY_SHARE);
        int priorityDraw = r.nextInt(1_000_000);

        if (d.markdown()) {
            List<LocalDate> mondays = new ArrayList<>();
            for (LocalDate m = windowStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)); !m.isAfter(windowEnd);
                 m = m.plusWeeks(1)) {
                mondays.add(m);
            }
            if (mondays.isEmpty()) {
                mondays.add(windowStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            }
            d.start = mondays.get(r.nextInt(mondays.size()));
            d.end = d.start.plusWeeks(weeks).minusDays(1); // Sunday
            d.group = targetU < SITEWIDE_SHARE ? StackGroup.SITEWIDE
                    : targetU < SITEWIDE_SHARE + CATEGORY_TARGET_SHARE ? StackGroup.CATEGORY : StackGroup.SKU;
        } else {
            r.nextInt(); // keep the draw count equal for both kinds
            d.start = windowStart.plusDays(couponOffset);
            d.end = d.start.plusDays(couponDays - 1L);
            d.group = StackGroup.COUPON;
        }

        // Target SKUs: active on at least one simulated day of the promotion.
        LocalDate from = d.start.isBefore(windowStart) ? windowStart : d.start;
        LocalDate to = d.end.isAfter(windowEnd) ? windowEnd : d.end;
        List<Product> eligible = new ArrayList<>();
        for (Product p : products) {
            if (!ctx.activeFrom(p).isAfter(to) && !ctx.activeTo(p).isBefore(from) && ctx.activeDays(p) > 0) {
                eligible.add(p);
            }
        }
        boolean categoryTarget = d.markdown() ? d.group == StackGroup.CATEGORY
                : targetU < CATEGORY_TARGET_SHARE;
        Category category = null;
        if (categoryTarget && !eligible.isEmpty()) {
            category = eligible.get(r.nextInt(eligible.size())).category();
            Category c = category;
            eligible.removeIf(p -> p.category() != c);
        } else {
            r.nextInt();
        }
        int size = d.markdown()
                ? StrictMath.max(5, (int) StrictMath.round(ctx.profile().skus() * markdownShare))
                : 20 + (int) StrictMath.floor(StrictMath.pow(sizeU, 1.5) * 130);
        size = StrictMath.min(StrictMath.min(size, 150), eligible.size());
        List<Product> pool = new ArrayList<>(eligible);
        Distributions.shuffle(r, pool);
        List<Product> chosen = new ArrayList<>(pool.subList(0, size));
        chosen.sort(Comparator.comparing(Product::skuId));
        d.skus = chosen.stream().map(Product::skuId).toList();

        boolean percent = type == PromoType.MARKDOWN_PCT || type == PromoType.COUPON_PCT;
        if (percent) {
            d.pct = pct;
        } else {
            List<BigDecimal> mrps = chosen.stream().map(Product::mrp).sorted().toList();
            double median = mrps.isEmpty() ? 100.0 : mrps.get(mrps.size() / 2).doubleValue();
            long tens = StrictMath.max(1L, StrictMath.round(median * amountRatio / 10.0));
            d.amount = BigDecimal.valueOf(tens * 10).setScale(2, RoundingMode.UNNECESSARY);
        }
        d.channel = d.markdown() ? Channel.ALL
                : channelU < 0.50 ? Channel.ALL : channelU < 0.85 ? Channel.ECOM : Channel.STORE;
        d.funding = vendor ? FundingSource.VENDOR : FundingSource.RETAILER;
        d.priority = switch (d.group) {
            case SKU -> 10 + priorityDraw % 31;        // 10–40
            case CATEGORY -> 30 + priorityDraw % 31;   // 30–60
            case SITEWIDE -> 50 + priorityDraw % 31;   // 50–80
            case COUPON -> 20 + priorityDraw % 71;     // 20–90
        };
        d.createdAt = d.start.minusDays(createdLead).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(createdSecond);
        d.scheduledAt = d.createdAt.plusSeconds(scheduleLag * 86_400L);
        String what = percent ? d.pct.movePointRight(2).stripTrailingZeros().toPlainString() + "% off"
                : "INR " + d.amount.stripTrailingZeros().toPlainString() + " off";
        String scope = category != null ? category.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT)
                : d.group == StackGroup.SITEWIDE ? "sitewide selection" : "selected products";
        d.name = (d.markdown() ? "Weekly markdown " : "Coupon ") + what + " on " + scope;
        return d;
    }

    /** 3% cancelled before start, 1% while active (exact counts, shuffled assignment). */
    private void assignCancellations(List<Draft> drafts) {
        int[] counts = Distributions.apportion(drafts.size(), CANCEL_WEIGHTS);
        List<Draft> order = new ArrayList<>(drafts);
        Distributions.shuffle(ctx.streams().of("promotion-cancel"), order);
        int before = counts[1];
        int active = counts[2];
        Instant asOf = ctx.windowEnd().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        for (Draft d : order) {
            Random r = ctx.streams().of("promotion-cancel", d.id);
            if (before > 0) {
                Instant startTs = startOfDay(d.start);
                boolean inDraft = Distributions.bernoulli(r, 0.5);
                Instant lo = inDraft ? d.createdAt : d.scheduledAt;
                Instant hi = inDraft ? d.scheduledAt : startTs;
                long span = hi.getEpochSecond() - lo.getEpochSecond();
                if (span > 2) {
                    d.cancelledAt = lo.plusSeconds(1 + (long) (r.nextDouble() * (span - 2)));
                    d.cancelledBeforeStart = true;
                    before--;
                    continue;
                }
            }
            if (active > 0) {
                LocalDate lastDay = d.end.isAfter(ctx.windowEnd()) ? ctx.windowEnd() : d.end;
                List<Instant> points = new ArrayList<>();
                if (d.markdown()) {
                    for (LocalDate m = d.start.plusWeeks(1); !m.isAfter(lastDay); m = m.plusWeeks(1)) {
                        points.add(startOfDay(m));
                    }
                } else {
                    for (LocalDate day = d.start.plusDays(1); !day.isAfter(lastDay); day = day.plusDays(1)) {
                        points.add(startOfDay(day).plusSeconds(Distributions.uniformInt(r, 8 * 3600, 20 * 3600)));
                    }
                }
                points.removeIf(p -> !p.isBefore(asOf));
                if (!points.isEmpty()) {
                    d.cancelledAt = points.get(r.nextInt(points.size()));
                    active--;
                }
            }
            if (before == 0 && active == 0) {
                break;
            }
        }
    }

    /** 25% want an EXCLUDES_PROMOTION pair: an overlapping promotion that shares at least one SKU. */
    private void assignPairs(List<Draft> drafts) {
        Map<String, List<Draft>> bySku = new TreeMap<>();
        for (Draft d : drafts) {
            for (String sku : d.skus) {
                bySku.computeIfAbsent(sku, k -> new ArrayList<>()).add(d);
            }
        }
        for (Draft d : drafts) { // drafts are in promotion-ID order
            if (!d.wantsPair) {
                continue;
            }
            TreeSet<String> candidateIds = new TreeSet<>();
            Map<String, Draft> byId = new TreeMap<>();
            for (String sku : d.skus) {
                for (Draft other : bySku.get(sku)) {
                    if (other != d && !other.start.isAfter(d.end) && !d.start.isAfter(other.end)) {
                        candidateIds.add(other.id);
                        byId.put(other.id, other);
                    }
                }
            }
            if (!candidateIds.isEmpty()) {
                List<String> ids = new ArrayList<>(candidateIds);
                d.pairTarget = ids.get(ctx.streams().of("promotion-pair", d.id).nextInt(ids.size()));
            }
        }
    }

    private List<PromotionRule> rules(Draft d) {
        List<PromotionRule> rules = new ArrayList<>(5);
        int year = d.start.getYear();
        if (d.exclusive) {
            rules.add(rule(d, year, RuleType.EXCLUSIVE, null, null));
        }
        if (d.pairTarget != null) {
            rules.add(rule(d, year, RuleType.EXCLUDES_PROMOTION, d.pairTarget, null));
        }
        rules.add(rule(d, year, RuleType.MAX_STACK_COUNT, null, BigDecimal.valueOf(d.maxStack)));
        rules.add(rule(d, year, RuleType.MAX_COMBINED_DISCOUNT_PCT, null, d.cap));
        if (d.minQty) {
            rules.add(rule(d, year, RuleType.MIN_ORDER_QTY, null, BigDecimal.valueOf(2)));
        }
        return rules;
    }

    private PromotionRule rule(Draft d, int year, RuleType type, String target, BigDecimal param) {
        return new PromotionRule(ctx.nextId(IdFactory.Sequence.RULE, year), d.id, type, target,
                param == null ? null : param.setScale(4, RoundingMode.UNNECESSARY), d.start, d.end);
    }

    private List<PromotionStatusEvent> lifecycle(Draft d) {
        Instant asOf = ctx.windowEnd().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<PromotionStatusEvent> events = new ArrayList<>(4);
        events.add(event(d, null, PromotionStatus.DRAFT, d.createdAt, ActorRole.MERCH_ANALYST, "Created"));
        if (d.cancelledBeforeStart && d.cancelledAt.isBefore(d.scheduledAt)) {
            events.add(event(d, PromotionStatus.DRAFT, PromotionStatus.CANCELLED, d.cancelledAt, ActorRole.MERCH_ANALYST,
                    "Cancelled before scheduling"));
            return clip(events, asOf);
        }
        events.add(event(d, PromotionStatus.DRAFT, PromotionStatus.SCHEDULED, d.scheduledAt, ActorRole.MERCH_ANALYST,
                "Scheduled"));
        if (d.cancelledBeforeStart) {
            events.add(event(d, PromotionStatus.SCHEDULED, PromotionStatus.CANCELLED, d.cancelledAt,
                    ActorRole.MERCH_ANALYST, "Cancelled before start"));
            return clip(events, asOf);
        }
        events.add(event(d, PromotionStatus.SCHEDULED, PromotionStatus.ACTIVE, startOfDay(d.start), ActorRole.SYSTEM,
                "Start date reached"));
        if (d.cancelledAt != null) {
            events.add(event(d, PromotionStatus.ACTIVE, PromotionStatus.CANCELLED, d.cancelledAt, ActorRole.OPS_MANAGER,
                    "Stopped early"));
        } else {
            events.add(event(d, PromotionStatus.ACTIVE, PromotionStatus.EXPIRED, startOfDay(d.end.plusDays(1)),
                    ActorRole.SYSTEM, "End date passed"));
        }
        return clip(events, asOf);
    }

    /** Keeps the events that happened before the end of the last simulated day (the as-of status). */
    private static List<PromotionStatusEvent> clip(List<PromotionStatusEvent> events, Instant asOf) {
        List<PromotionStatusEvent> out = new ArrayList<>(events);
        out.removeIf(e -> !e.eventTs().isBefore(asOf));
        if (out.isEmpty()) {
            throw new IllegalStateException("promotion created after the window: " + events.get(0).promotionId());
        }
        return out;
    }

    private static PromotionStatusEvent event(Draft d, PromotionStatus from, PromotionStatus to, Instant ts,
                                              ActorRole actor, String note) {
        return new PromotionStatusEvent(null, d.id, from, to, ts, actor, note);
    }

    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static List<PromotionOffer> sortedById(List<PromotionOffer> offers) {
        List<PromotionOffer> sorted = new ArrayList<>(offers);
        sorted.sort(Comparator.comparing(PromotionOffer::promotionId));
        return Collections.unmodifiableList(sorted);
    }

    private static List<BigDecimal> decimals(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }
}
