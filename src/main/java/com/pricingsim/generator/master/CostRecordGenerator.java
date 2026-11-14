package com.pricingsim.generator.master;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.pricingsim.domain.enums.CostChangeReason;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.Amounts;
import com.pricingsim.generator.GenerationContext;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;

/**
 * Generates {@code cost_record}: one row per SKU per month in which the SKU is active (PLAN.md §7.2 step 2).
 *
 * <ul>
 *   <li>Initial landed cost = MRP / U(category markup band); freight is 2–6% of unit cost; landed = unit + freight.</li>
 *   <li>Each later month: 70% NO_CHANGE, 22% a small supplier change of ±1–5%, 4% FREIGHT_CHANGE and 4% a supplier
 *       shock of +10–20% (the source of natural guardrail conflicts).</li>
 *   <li>{@code effective_from} = first of month (or launch date if later); {@code effective_to} (exclusive) = next
 *       record's {@code effective_from}, null on the latest record, so periods never overlap or leave gaps.</li>
 *   <li>{@code change_pct} = landed / previous landed − 1 (4 dp, HALF_UP), null on the first record.</li>
 * </ul>
 * Draws come from the SKU's own stream ({@code cost}/{@code SKU-…}).
 */
public final class CostRecordGenerator {

    static final double P_NO_CHANGE = 0.70;
    static final double P_SMALL_CHANGE = 0.22;
    static final double P_FREIGHT_CHANGE = 0.04;
    // The remaining 4% is a supplier shock.
    static final double SMALL_INCREASE_SHARE = 0.55;
    static final double FREIGHT_MIN = 0.02;
    static final double FREIGHT_MAX = 0.06;
    static final double MIN_FREIGHT_STEP = 0.01;

    private final GenerationContext ctx;

    public CostRecordGenerator(GenerationContext ctx) {
        this.ctx = ctx;
    }

    /** Generates every cost record; returns them per SKU (sorted by SKU, then month). */
    public Map<String, List<CostRecord>> generate(List<Product> products, RowSink sink) {
        Map<String, List<CostRecord>> bySku = new TreeMap<>();
        for (Product product : products) {
            if (ctx.activeDays(product) == 0) {
                continue;
            }
            List<CostRecord> records = generateFor(product);
            records.forEach(rec -> sink.accept(TableRegistry.COST_RECORD, rec));
            bySku.put(product.skuId(), Collections.unmodifiableList(records));
        }
        return Collections.unmodifiableMap(bySku);
    }

    List<CostRecord> generateFor(Product product) {
        CategoryCatalog.Profile cp = CategoryCatalog.of(product.category());
        Random r = ctx.streams().of("cost", product.skuId());

        YearMonth first = YearMonth.from(ctx.activeFrom(product));
        YearMonth last = YearMonth.from(ctx.activeTo(product));

        double markup = Distributions.uniform(r, cp.markupLo(), cp.markupHi());
        double freightRatio = Distributions.uniform(r, FREIGHT_MIN, FREIGHT_MAX);
        double initialLanded = product.mrp().doubleValue() / markup;
        BigDecimal unit = Amounts.money(initialLanded / (1.0 + freightRatio));
        BigDecimal freight = Amounts.money(unit.doubleValue() * freightRatio);

        List<Draft> drafts = new ArrayList<>();
        drafts.add(new Draft(first, unit, freight, CostChangeReason.INITIAL));
        for (YearMonth month = first.plusMonths(1); !month.isAfter(last); month = month.plusMonths(1)) {
            double u = r.nextDouble();
            CostChangeReason reason;
            if (u < P_NO_CHANGE) {
                reason = CostChangeReason.NO_CHANGE;
            } else if (u < P_NO_CHANGE + P_SMALL_CHANGE) {
                double magnitude = Distributions.uniform(r, 0.01, 0.05);
                boolean increase = Distributions.bernoulli(r, SMALL_INCREASE_SHARE);
                unit = Amounts.money(unit.doubleValue() * (1.0 + (increase ? magnitude : -magnitude)));
                freight = Amounts.money(unit.doubleValue() * freightRatio);
                reason = increase ? CostChangeReason.SUPPLIER_INCREASE : CostChangeReason.SUPPLIER_DECREASE;
            } else if (u < P_NO_CHANGE + P_SMALL_CHANGE + P_FREIGHT_CHANGE) {
                double next = Distributions.uniform(r, FREIGHT_MIN, FREIGHT_MAX);
                if (StrictMath.abs(next - freightRatio) < MIN_FREIGHT_STEP) {
                    // Keep the change visible: move at least one percentage point, staying inside the band.
                    next = freightRatio + MIN_FREIGHT_STEP <= FREIGHT_MAX ? freightRatio + MIN_FREIGHT_STEP
                            : freightRatio - MIN_FREIGHT_STEP;
                }
                freightRatio = next;
                freight = Amounts.money(unit.doubleValue() * freightRatio);
                reason = CostChangeReason.FREIGHT_CHANGE;
            } else {
                double shock = Distributions.uniform(r, 0.10, 0.20);
                unit = Amounts.money(unit.doubleValue() * (1.0 + shock));
                freight = Amounts.money(unit.doubleValue() * freightRatio);
                reason = CostChangeReason.SUPPLIER_INCREASE;
            }
            drafts.add(new Draft(month, unit, freight, reason));
        }

        List<CostRecord> records = new ArrayList<>(drafts.size());
        BigDecimal previousLanded = null;
        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            LocalDate from = effectiveFrom(product, d.month());
            LocalDate to = i + 1 < drafts.size() ? effectiveFrom(product, drafts.get(i + 1).month()) : null;
            BigDecimal landed = d.unit().add(d.freight());
            BigDecimal changePct = previousLanded == null ? null
                    : landed.divide(previousLanded, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                            .setScale(4, RoundingMode.HALF_UP);
            CostChangeReason reason = reconcileReason(d.reason(), changePct);
            records.add(new CostRecord(IdFactory.costRecord(product.skuId(), d.month()), product.skuId(),
                    IdFactory.costMonth(d.month()), from, to, d.unit(), d.freight(), landed, changePct, reason));
            previousLanded = landed;
        }
        return records;
    }

    /** First of the month, or the launch date when the product launched later in that month. */
    static LocalDate effectiveFrom(Product product, YearMonth month) {
        LocalDate firstOfMonth = month.atDay(1);
        return product.launchDate().isAfter(firstOfMonth) ? product.launchDate() : firstOfMonth;
    }

    /**
     * Keeps {@code change_reason} consistent with the sign of {@code change_pct} (data dictionary rule). Paise rounding
     * can, in rare cases, flatten a small supplier change to 0; it is then recorded as NO_CHANGE.
     */
    private static CostChangeReason reconcileReason(CostChangeReason drafted, BigDecimal changePct) {
        if (changePct == null || drafted == CostChangeReason.NO_CHANGE) {
            return drafted;
        }
        int sign = changePct.signum();
        if (sign == 0) {
            return CostChangeReason.NO_CHANGE;
        }
        if (drafted == CostChangeReason.FREIGHT_CHANGE) {
            return drafted;
        }
        return sign > 0 ? CostChangeReason.SUPPLIER_INCREASE : CostChangeReason.SUPPLIER_DECREASE;
    }

    private record Draft(YearMonth month, BigDecimal unit, BigDecimal freight, CostChangeReason reason) {
    }
}
