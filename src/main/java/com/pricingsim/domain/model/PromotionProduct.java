package com.pricingsim.domain.model;

import java.time.Instant;

/** Bridge table {@code promotion_product}: Promotion ⇄ Product (N:M), PK (promotion_id, sku_id). */
public record PromotionProduct(
        String promotionId,
        String skuId,
        Instant addedAt) {
}
