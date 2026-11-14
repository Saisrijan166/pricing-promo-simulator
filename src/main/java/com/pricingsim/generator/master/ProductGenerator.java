package com.pricingsim.generator.master;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.pricingsim.domain.enums.ActorRole;
import com.pricingsim.domain.enums.Category;
import com.pricingsim.domain.enums.PricePosition;
import com.pricingsim.domain.enums.ProductStatus;
import com.pricingsim.domain.enums.Uom;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.Product;
import com.pricingsim.domain.model.ProductStatusEvent;
import com.pricingsim.generator.Amounts;
import com.pricingsim.generator.GenerationContext;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.pricing.PricePointRounder;

/**
 * Generates {@code product} and {@code product_status_event} (PLAN.md §7.2 step 1).
 *
 * <ul>
 *   <li>Categories are apportioned exactly by weight, then shuffled (stream {@code product-category}); SKU numbers
 *       follow that generation order.</li>
 *   <li>Lifecycle is apportioned exactly: 92% launched before the window, 5% launched in the window (NEW for 14 days),
 *       3% discontinued in the window. Assignment is shuffled (stream {@code product-lifecycle}).</li>
 *   <li>All other attributes come from the SKU's own stream ({@code product}/{@code SKU-…}), so one SKU's attributes do
 *       not depend on any other SKU.</li>
 * </ul>
 */
public final class ProductGenerator {

    /** Lifecycle mix (PLAN.md §7.2 step 1). */
    enum Lifecycle {
        PRE_WINDOW, LAUNCHED_IN_WINDOW, DISCONTINUED_IN_WINDOW
    }

    static final double[] LIFECYCLE_WEIGHTS = {0.92, 0.05, 0.03};
    /** In-window launches are NEW for this many days, then ACTIVE (PLAN.md §4.3). */
    public static final int NEW_PERIOD_DAYS = 14;
    static final double MAP_SHARE = 0.10;
    static final double TRACKED_SHARE = 0.80;
    static final double[] POSITION_WEIGHTS = {0.30, 0.50, 0.20}; // VALUE, MATCH, PREMIUM
    static final BigDecimal MIN_MRP = BigDecimal.TEN;
    static final double MIN_PROMO_MARGIN_FLOOR = 0.02;

    private final GenerationContext ctx;

    public ProductGenerator(GenerationContext ctx) {
        this.ctx = ctx;
    }

    /** Generates every product and its status events; returns the products sorted by {@code sku_id}. */
    public List<Product> generate(RowSink sink) {
        int n = ctx.profile().skus();
        List<Category> categories = categoryAssignment(n);
        List<Lifecycle> lifecycles = lifecycleAssignment(n);

        List<Product> products = new ArrayList<>(n);
        List<ProductStatusEvent> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String skuId = IdFactory.sku(i + 1L);
            Generated g = generateOne(skuId, categories.get(i), lifecycles.get(i));
            products.add(g.product());
            events.addAll(g.events());
        }

        for (Product p : products) {
            sink.accept(TableRegistry.PRODUCT, p);
        }
        // PEV IDs in (event_ts, sku_id) order, per year of the event (PLAN.md §6).
        events.sort(Comparator.comparing(ProductStatusEvent::eventTs).thenComparing(ProductStatusEvent::skuId));
        for (ProductStatusEvent e : events) {
            int year = e.eventTs().atZone(ZoneOffset.UTC).getYear();
            sink.accept(TableRegistry.PRODUCT_STATUS_EVENT, new ProductStatusEvent(
                    ctx.nextId(IdFactory.Sequence.PRODUCT_EVENT, year), e.skuId(), e.fromStatus(), e.toStatus(),
                    e.eventTs(), e.actorRole(), e.note()));
        }
        return List.copyOf(products);
    }

    private record Generated(Product product, List<ProductStatusEvent> events) {
    }

    private List<Category> categoryAssignment(int n) {
        Category[] all = Category.values();
        int[] counts = Distributions.apportion(n, CategoryCatalog.weights());
        List<Category> list = new ArrayList<>(n);
        for (int c = 0; c < all.length; c++) {
            for (int k = 0; k < counts[c]; k++) {
                list.add(all[c]);
            }
        }
        Distributions.shuffle(ctx.streams().of("product-category"), list);
        return list;
    }

    private List<Lifecycle> lifecycleAssignment(int n) {
        List<Lifecycle> list = new ArrayList<>(n);
        if (ctx.profile().days() < 2) {
            // A one-day window has no room for an in-window launch or discontinuation.
            for (int i = 0; i < n; i++) {
                list.add(Lifecycle.PRE_WINDOW);
            }
            return list;
        }
        Lifecycle[] all = Lifecycle.values();
        int[] counts = Distributions.apportion(n, LIFECYCLE_WEIGHTS);
        for (int c = 0; c < all.length; c++) {
            for (int k = 0; k < counts[c]; k++) {
                list.add(all[c]);
            }
        }
        Distributions.shuffle(ctx.streams().of("product-lifecycle"), list);
        return list;
    }

    private Generated generateOne(String skuId, Category category, Lifecycle lifecycle) {
        CategoryCatalog.Profile cp = CategoryCatalog.of(category);
        Random r = ctx.streams().of("product", skuId);
        LocalDate start = ctx.windowStart();
        int days = ctx.profile().days();

        // Fixed draw order: every SKU consumes the same draws regardless of lifecycle.
        String brand = Distributions.choice(r, cp.brands());
        CategoryCatalog.Subcategory sub = Distributions.choice(r, cp.subcategories());
        boolean pack = Distributions.bernoulli(r, cp.packShare());
        int packSize = Distributions.choice(r, CategoryCatalog.PACK_SIZES);
        String variant = Distributions.choice(r, sub.variants());
        double mrpRaw = Distributions.lognormalMedian(r, cp.mrpMedian(), cp.mrpSigma());
        double minMargin = Distributions.uniform(r, cp.marginLo(), cp.marginHi());
        double promoGap = Distributions.uniform(r, 0.03, 0.08);
        boolean hasMap = Distributions.bernoulli(r, MAP_SHARE);
        double mapRatio = Distributions.uniform(r, 0.80, 0.90);
        PricePosition position = Distributions.weightedChoice(r, List.of(PricePosition.values()), POSITION_WEIGHTS);
        boolean tracked = Distributions.bernoulli(r, TRACKED_SHARE);
        int preWindowAge = Distributions.uniformInt(r, 30, 1500);
        int inWindowOffset = Distributions.uniformInt(r, 1, days - 1 < 1 ? 1 : days - 1);
        int createdLeadDays = Distributions.uniformInt(r, 7, 60);
        int createdSecondOfDay = Distributions.uniformInt(r, 9 * 3600, 18 * 3600 - 1);

        Uom uom = pack ? Uom.PACK : Uom.EACH;
        int size = pack ? packSize : 1;
        String subcategory = sub.name();
        String name = brand + " " + subcategory + " " + variant + (pack ? " (Pack of " + packSize + ")" : "");

        BigDecimal mrp = PricePointRounder.nearestPoint(Amounts.money(mrpRaw));
        if (mrp.compareTo(MIN_MRP) < 0) {
            mrp = MIN_MRP;
        }
        mrp = mrp.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal mapPrice = hasMap ? Amounts.wholeRupees(mrp.doubleValue() * mapRatio) : null;
        BigDecimal minMarginPct = Amounts.fraction4(minMargin);
        BigDecimal minPromoMarginPct = Amounts.fraction4(StrictMath.max(MIN_PROMO_MARGIN_FLOOR, minMargin - promoGap));

        LocalDate launch;
        LocalDate discontinued = null;
        List<ProductStatusEvent> events = new ArrayList<>(3);
        switch (lifecycle) {
            case PRE_WINDOW -> {
                launch = start.minusDays(preWindowAge);
                events.add(event(skuId, null, ProductStatus.ACTIVE, launch, ActorRole.MERCH_ANALYST, "Launched"));
            }
            case LAUNCHED_IN_WINDOW -> {
                launch = start.plusDays(inWindowOffset);
                events.add(event(skuId, null, ProductStatus.NEW, launch, ActorRole.MERCH_ANALYST,
                        "Launched in window"));
                LocalDate activeOn = launch.plusDays(NEW_PERIOD_DAYS);
                if (!activeOn.isAfter(ctx.windowEnd())) {
                    events.add(event(skuId, ProductStatus.NEW, ProductStatus.ACTIVE, activeOn, ActorRole.SYSTEM,
                            "Launch period of " + NEW_PERIOD_DAYS + " days ended"));
                }
            }
            case DISCONTINUED_IN_WINDOW -> {
                launch = start.minusDays(preWindowAge);
                discontinued = start.plusDays(inWindowOffset);
                events.add(event(skuId, null, ProductStatus.ACTIVE, launch, ActorRole.MERCH_ANALYST, "Launched"));
                events.add(event(skuId, ProductStatus.ACTIVE, ProductStatus.DISCONTINUED, discontinued,
                        ActorRole.MERCH_ANALYST, "Discontinued"));
            }
            default -> throw new IllegalStateException("Unknown lifecycle " + lifecycle);
        }
        ProductStatus status = events.get(events.size() - 1).toStatus();
        Instant createdAt = launch.minusDays(createdLeadDays).atStartOfDay(ZoneOffset.UTC).toInstant()
                .plusSeconds(createdSecondOfDay);

        Product product = new Product(skuId, name, brand, category, subcategory, uom, size, status, launch,
                discontinued, mrp, mapPrice, minMarginPct, minPromoMarginPct, position, tracked, createdAt);
        return new Generated(product, events);
    }

    private static ProductStatusEvent event(String skuId, ProductStatus from, ProductStatus to, LocalDate day,
                                            ActorRole actor, String note) {
        // The event ID is assigned after all events are sorted.
        return new ProductStatusEvent(null, skuId, from, to, day.atStartOfDay(ZoneOffset.UTC).toInstant(), actor, note);
    }
}
