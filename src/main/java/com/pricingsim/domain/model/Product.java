package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.pricingsim.domain.enums.Category;
import com.pricingsim.domain.enums.PricePosition;
import com.pricingsim.domain.enums.ProductStatus;
import com.pricingsim.domain.enums.Uom;

/** Table {@code product} (PLAN.md §5.1). Master record; MRP is the hard price ceiling. */
public record Product(
        String skuId,
        String productName,
        String brand,
        Category category,
        String subcategory,
        Uom uom,
        int packSize,
        ProductStatus status,
        LocalDate launchDate,
        LocalDate discontinuedDate,
        BigDecimal mrp,
        BigDecimal mapPrice,
        BigDecimal minMarginPct,
        BigDecimal minPromoMarginPct,
        PricePosition pricePosition,
        boolean competitorTracked,
        Instant createdAt) {
}
