package com.bohouse.pacemeter.application;

import java.util.List;

public record LiveDotAttributionDebugSnapshot(
        long lookbackSeconds,
        int recentAssignmentCount,
        List<Entry> entries
) {
    public record Entry(
            String mode,
            long sourceId,
            String sourceName,
            int actionId,
            long totalAmount,
            long hitCount
    ) {
    }
}
