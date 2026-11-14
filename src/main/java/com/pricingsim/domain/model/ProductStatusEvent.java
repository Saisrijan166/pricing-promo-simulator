package com.pricingsim.domain.model;

import java.time.Instant;

import com.pricingsim.domain.enums.ActorRole;
import com.pricingsim.domain.enums.ProductStatus;

/** Event table {@code product_status_event}: append-only product lifecycle history. fromStatus is null on the first event. */
public record ProductStatusEvent(
        String eventId,
        String skuId,
        ProductStatus fromStatus,
        ProductStatus toStatus,
        Instant eventTs,
        ActorRole actorRole,
        String note) {
}
