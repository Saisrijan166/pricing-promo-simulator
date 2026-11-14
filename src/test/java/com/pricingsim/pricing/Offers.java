package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.FundingSource;
import com.pricingsim.domain.enums.PromoType;
import com.pricingsim.domain.enums.PromotionStatus;
import com.pricingsim.domain.enums.RuleType;
import com.pricingsim.domain.enums.StackGroup;
import com.pricingsim.domain.model.Promotion;
import com.pricingsim.domain.model.PromotionRule;

/** Fluent test fixture for {@link PromotionOffer}s; the default window is all of January 2026. */
public final class Offers {

    public static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    public static final LocalDate JAN_31 = LocalDate.of(2026, 1, 31);

    private final String id;
    private PromoType type;
    private BigDecimal pct;
    private BigDecimal amount;
    private int priority = 50;
    private Channel channel = Channel.ALL;
    private LocalDate start = JAN_1;
    private LocalDate end = JAN_31;
    private Instant cancelledAt;
    private final List<Object[]> rules = new ArrayList<>();

    private Offers(String id, PromoType type) {
        this.id = id;
        this.type = type;
    }

    /** Percentage markdown, e.g. {@code markdownPct("PROMO-2026-000001", "0.10")}. */
    public static Offers markdownPct(String id, String pct) {
        Offers o = new Offers(id, PromoType.MARKDOWN_PCT);
        o.pct = new BigDecimal(pct);
        return o;
    }

    public static Offers markdownAmount(String id, String amount) {
        Offers o = new Offers(id, PromoType.MARKDOWN_AMOUNT);
        o.amount = new BigDecimal(amount);
        return o;
    }

    public static Offers couponPct(String id, String pct) {
        Offers o = new Offers(id, PromoType.COUPON_PCT);
        o.pct = new BigDecimal(pct);
        return o;
    }

    public static Offers couponAmount(String id, String amount) {
        Offers o = new Offers(id, PromoType.COUPON_AMOUNT);
        o.amount = new BigDecimal(amount);
        return o;
    }

    public Offers priority(int priority) {
        this.priority = priority;
        return this;
    }

    public Offers channel(Channel channel) {
        this.channel = channel;
        return this;
    }

    public Offers window(LocalDate start, LocalDate end) {
        this.start = start;
        this.end = end;
        return this;
    }

    public Offers cancelledAt(Instant at) {
        this.cancelledAt = at;
        return this;
    }

    public Offers exclusive() {
        rules.add(new Object[] {RuleType.EXCLUSIVE, null, null});
        return this;
    }

    public Offers excludes(String otherId) {
        rules.add(new Object[] {RuleType.EXCLUDES_PROMOTION, otherId, null});
        return this;
    }

    public Offers maxStack(int n) {
        rules.add(new Object[] {RuleType.MAX_STACK_COUNT, null, BigDecimal.valueOf(n)});
        return this;
    }

    public Offers maxCombined(String pct) {
        rules.add(new Object[] {RuleType.MAX_COMBINED_DISCOUNT_PCT, null, new BigDecimal(pct)});
        return this;
    }

    public Offers minQty(int n) {
        rules.add(new Object[] {RuleType.MIN_ORDER_QTY, null, BigDecimal.valueOf(n)});
        return this;
    }

    public PromotionOffer build() {
        boolean coupon = type == PromoType.COUPON_PCT || type == PromoType.COUPON_AMOUNT;
        Promotion p = new Promotion(id, "Test " + id, type, coupon ? StackGroup.COUPON : StackGroup.SKU, pct, amount,
                coupon ? "CPN-2026-" + id.substring(id.length() - 6) : null, start, end, priority, channel,
                FundingSource.RETAILER, PromotionStatus.ACTIVE, start.minusDays(10).atStartOfDay().toInstant(
                        java.time.ZoneOffset.UTC));
        List<PromotionRule> list = new ArrayList<>();
        int n = 1;
        for (Object[] r : rules) {
            list.add(new PromotionRule("RULE-2026-" + id.substring(id.length() - 3) + String.format("%03d", n++), id,
                    (RuleType) r[0], (String) r[1], (BigDecimal) r[2], start, end));
        }
        return new PromotionOffer(p, list, cancelledAt);
    }
}
