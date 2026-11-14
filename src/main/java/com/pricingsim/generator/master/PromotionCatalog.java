package com.pricingsim.generator.master;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.pricingsim.domain.model.Promotion;
import com.pricingsim.pricing.PromotionOffer;

/**
 * Promotions as the simulator needs them: per-SKU markdown offers (resolved by price decisions) and coupon offers
 * (presented on orders), each list sorted by promotion ID.
 */
public record PromotionCatalog(
        List<Promotion> promotions,
        Map<String, List<PromotionOffer>> markdownsBySku,
        Map<String, List<PromotionOffer>> couponsBySku) {

    public PromotionCatalog {
        promotions = List.copyOf(promotions);
        markdownsBySku = Collections.unmodifiableMap(markdownsBySku);
        couponsBySku = Collections.unmodifiableMap(couponsBySku);
    }

    public static PromotionCatalog empty() {
        return new PromotionCatalog(List.of(), new java.util.TreeMap<>(), new java.util.TreeMap<>());
    }

    public List<PromotionOffer> markdownsFor(String skuId) {
        return markdownsBySku.getOrDefault(skuId, List.of());
    }

    public List<PromotionOffer> couponsFor(String skuId) {
        return couponsBySku.getOrDefault(skuId, List.of());
    }
}
