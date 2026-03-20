package com.bohouse.pacemeter.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusZeroDotAllocationPlannerTest {

    private final StatusZeroDotAllocationPlanner planner = new StatusZeroDotAllocationPlanner();

    @Test
    void allocate_splitsByWeight_andConservesTotal() {
        List<StatusZeroDotAllocationPlanner.Allocation> allocations = planner.allocate(
                10_000L,
                List.of(
                        new StatusZeroDotAllocationPlanner.Candidate(0xA, 0x1111, 3.0),
                        new StatusZeroDotAllocationPlanner.Candidate(0xB, 0x2222, 1.0)
                )
        );

        assertEquals(2, allocations.size());
        long total = allocations.stream().mapToLong(StatusZeroDotAllocationPlanner.Allocation::amount).sum();
        assertEquals(10_000L, total);
        long major = allocations.stream()
                .filter(allocation -> allocation.sourceId() == 0xAL)
                .mapToLong(StatusZeroDotAllocationPlanner.Allocation::amount)
                .sum();
        long minor = allocations.stream()
                .filter(allocation -> allocation.sourceId() == 0xBL)
                .mapToLong(StatusZeroDotAllocationPlanner.Allocation::amount)
                .sum();
        assertTrue(major > minor);
    }

    @Test
    void allocate_ignoresZeroOrNegativeWeights() {
        List<StatusZeroDotAllocationPlanner.Allocation> allocations = planner.allocate(
                7_000L,
                List.of(
                        new StatusZeroDotAllocationPlanner.Candidate(0xA, 0x1111, 0.0),
                        new StatusZeroDotAllocationPlanner.Candidate(0xB, 0x2222, -1.0),
                        new StatusZeroDotAllocationPlanner.Candidate(0xC, 0x3333, 2.0)
                )
        );

        assertEquals(1, allocations.size());
        assertEquals(0xCL, allocations.get(0).sourceId());
        assertEquals(7_000L, allocations.get(0).amount());
    }
}

