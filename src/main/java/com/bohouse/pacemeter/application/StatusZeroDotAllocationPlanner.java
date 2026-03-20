package com.bohouse.pacemeter.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class StatusZeroDotAllocationPlanner {

    List<Allocation> allocate(long totalDamage, List<Candidate> candidates) {
        if (totalDamage <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<Candidate> usable = candidates.stream()
                .filter(candidate -> candidate.weight() > 0.0)
                .sorted(Comparator.comparingDouble(Candidate::weight).reversed()
                        .thenComparingLong(Candidate::sourceId)
                        .thenComparingInt(Candidate::actionId))
                .toList();
        if (usable.isEmpty()) {
            return List.of();
        }

        double weightSum = usable.stream().mapToDouble(Candidate::weight).sum();
        if (weightSum <= 0.0) {
            return List.of();
        }

        List<Allocation> allocations = new ArrayList<>();
        long allocated = 0L;
        for (int i = 0; i < usable.size(); i++) {
            Candidate candidate = usable.get(i);
            long amount = i == usable.size() - 1
                    ? Math.max(0L, totalDamage - allocated)
                    : Math.max(0L, Math.round(totalDamage * (candidate.weight() / weightSum)));
            allocated += amount;
            if (amount <= 0) {
                continue;
            }
            allocations.add(new Allocation(candidate.sourceId(), candidate.actionId(), amount));
        }
        return allocations;
    }

    record Candidate(long sourceId, int actionId, double weight) {
    }

    record Allocation(long sourceId, int actionId, long amount) {
    }
}

