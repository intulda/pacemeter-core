package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.DotTickRaw;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

final class UnknownStatusDotAttributionResolver {

    Integer resolveTrackedStatusActionId(
            DotTickRaw dot,
            Map<DotKey, DotApplication> statusApplications,
            long windowMs,
            IntUnaryOperator toTrackedDotActionId
    ) {
        DotApplication application = resolveEvidence(statusApplications, dot, windowMs);
        if (application == null) return null;
        int trackedActionId = toTrackedDotActionId.applyAsInt(application.actionId());
        return trackedActionId != 0 ? trackedActionId : application.actionId();
    }

    Integer resolveTrackedApplicationActionId(
            DotTickRaw dot,
            Map<DotKey, DotApplication> actionApplications,
            long windowMs
    ) {
        DotApplication application = resolveEvidence(actionApplications, dot, windowMs);
        if (application == null) return null;
        return application.actionId();
    }

    Optional<UnknownSourceAttribution> resolveUnknownSourceAttribution(
            DotTickRaw dot,
            Map<DotKey, DotApplication> actionApplications,
            Map<DotKey, DotApplication> statusApplications,
            long unknownActorId,
            long windowMs,
            LongPredicate isPartyMember,
            LongFunction<String> sourceNameResolver,
            IntUnaryOperator toTrackedDotActionId
    ) {
        if (dot.statusId() != 0) {
            return Optional.empty();
        }
        if (dot.sourceId() != 0 && dot.sourceId() != unknownActorId) {
            return Optional.empty();
        }

        Instant cutoff = dot.ts().minusMillis(windowMs);
        Map<Long, UnknownSourceCandidate> candidatesBySource = new HashMap<>();

        addActionCandidates(dot, cutoff, actionApplications, isPartyMember, sourceNameResolver, candidatesBySource);
        addStatusCandidates(dot, cutoff, statusApplications, isPartyMember, sourceNameResolver, toTrackedDotActionId, candidatesBySource);

        if (candidatesBySource.isEmpty()) {
            return Optional.empty();
        }
        UnknownSourceCandidate selected = candidatesBySource.values().stream()
                .max(Comparator.comparing(UnknownSourceCandidate::appliedAt)
                        .thenComparingLong(UnknownSourceCandidate::sourceId))
                .orElse(null);
        if (selected == null) {
            return Optional.empty();
        }
        return Optional.of(new UnknownSourceAttribution(
                selected.sourceId(),
                selected.actionId(),
                selected.sourceName()
        ));
    }

    private DotApplication resolveEvidence(
            Map<DotKey, DotApplication> evidenceMap,
            DotTickRaw dot,
            long windowMs
    ) {
        DotApplication application = evidenceMap.get(new DotKey(dot.sourceId(), dot.targetId()));
        if (application == null) {
            return null;
        }
        if (application.appliedAt().isBefore(dot.ts().minusMillis(windowMs))) {
            return null;
        }
        return application;
    }

    private void addActionCandidates(
            DotTickRaw dot,
            Instant cutoff,
            Map<DotKey, DotApplication> actionApplications,
            LongPredicate isPartyMember,
            LongFunction<String> sourceNameResolver,
            Map<Long, UnknownSourceCandidate> candidatesBySource
    ) {
        for (Map.Entry<DotKey, DotApplication> entry : actionApplications.entrySet()) {
            DotKey key = entry.getKey();
            DotApplication application = entry.getValue();
            if (key.targetId() != dot.targetId() || application.appliedAt().isBefore(cutoff)) {
                continue;
            }
            if (!isPartyMember.test(key.sourceId())) {
                continue;
            }
            candidatesBySource.compute(key.sourceId(), (ignored, existing) -> pickNewer(
                    existing,
                    new UnknownSourceCandidate(
                            key.sourceId(),
                            application.actionId(),
                            sourceNameResolver.apply(key.sourceId()),
                            application.appliedAt()
                    )
            ));
        }
    }

    private void addStatusCandidates(
            DotTickRaw dot,
            Instant cutoff,
            Map<DotKey, DotApplication> statusApplications,
            LongPredicate isPartyMember,
            LongFunction<String> sourceNameResolver,
            IntUnaryOperator toTrackedDotActionId,
            Map<Long, UnknownSourceCandidate> candidatesBySource
    ) {
        for (Map.Entry<DotKey, DotApplication> entry : statusApplications.entrySet()) {
            DotKey key = entry.getKey();
            DotApplication application = entry.getValue();
            if (key.targetId() != dot.targetId() || application.appliedAt().isBefore(cutoff)) {
                continue;
            }
            if (!isPartyMember.test(key.sourceId())) {
                continue;
            }
            int mappedAction = toTrackedDotActionId.applyAsInt(application.actionId());
            if (mappedAction == 0) {
                continue;
            }
            candidatesBySource.compute(key.sourceId(), (ignored, existing) -> pickNewer(
                    existing,
                    new UnknownSourceCandidate(
                            key.sourceId(),
                            mappedAction,
                            sourceNameResolver.apply(key.sourceId()),
                            application.appliedAt()
                    )
            ));
        }
    }

    private UnknownSourceCandidate pickNewer(
            UnknownSourceCandidate existing,
            UnknownSourceCandidate candidate
    ) {
        if (existing == null) {
            return candidate;
        }
        return candidate.appliedAt().isAfter(existing.appliedAt()) ? candidate : existing;
    }

    record DotKey(long sourceId, long targetId) {}

    record DotApplication(int actionId, Instant appliedAt) {}

    record UnknownSourceAttribution(long sourceId, int actionId, String sourceName) {}

    private record UnknownSourceCandidate(long sourceId, int actionId, String sourceName, Instant appliedAt) {}
}
