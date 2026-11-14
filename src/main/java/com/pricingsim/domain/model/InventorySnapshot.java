package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Table {@code inventory_snapshot} (PLAN.md §5.5). Daily, network level.
 * closing = opening + receipts − sold + adjustment; lost_sales = demand − sold.
 */
public record InventorySnapshot(
        String inventorySnapshotId,
        String skuId,
        LocalDate snapshotDate,
        int openingOnHand,
        int receiptsUnits,
        int unitsSold,
        int adjustmentUnits,
        int closingOnHand,
        int onOrderUnits,
        int demandUnits,
        int lostSalesUnits,
        boolean stockoutFlag,
        BigDecimal daysOfCover) {
}
