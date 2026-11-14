package com.pricingsim.domain.model;

import java.time.Instant;

import com.pricingsim.domain.enums.ActorRole;
import com.pricingsim.domain.enums.PromotionStatus;

/** Event table {@code promotion_status_event}: append-only promotion lifecycle history. fromStatus is null on the first event. */
public record PromotionStatusEvent(
        String eventId,
        String promotionId,
        PromotionStatus fromStatus,
        PromotionStatus toStatus,
        Instant eventTs,
        ActorRole actorRole,
        String note) {
}
