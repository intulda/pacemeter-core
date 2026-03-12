package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.application.port.outbound.PaceProfileProvider;
import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.engine.EngineResult;
import com.bohouse.pacemeter.core.estimator.Confidence;
import com.bohouse.pacemeter.core.estimator.OnlineEstimator;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.estimator.RdpsEstimate;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActiveBuff;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.ActorStats;
import com.bohouse.pacemeter.core.model.CombatState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class CombatService implements CombatEventPort {

    private final CombatEngine engine;
    private final SnapshotPublisher snapshotPublisher;
    private final PaceProfileProvider paceProfileProvider;
    private final EnrageTimeProvider enrageTimeProvider;
    private final OnlineEstimator onlineEstimator;
    private final ExecutorService profileLoader;
    private final AtomicLong profileLoadGeneration;
    private final Object lock;

    private int currentTerritoryId;

    public CombatService(
            CombatEngine engine,
            SnapshotPublisher snapshotPublisher,
            PaceProfileProvider paceProfileProvider,
            EnrageTimeProvider enrageTimeProvider
    ) {
        this.engine = engine;
        this.snapshotPublisher = snapshotPublisher;
        this.paceProfileProvider = paceProfileProvider;
        this.enrageTimeProvider = enrageTimeProvider;
        this.onlineEstimator = new OnlineEstimator();
        this.profileLoader = Executors.newSingleThreadExecutor(new ProfileLoaderThreadFactory());
        this.profileLoadGeneration = new AtomicLong();
        this.lock = new Object();
        this.currentTerritoryId = 0;
    }

    @Override
    public EngineResult onEvent(CombatEvent event) {
        EngineResult result;

        if (event instanceof CombatEvent.FightStart fightStart) {
            synchronized (lock) {
                currentTerritoryId = fightStart.zoneId();
                engine.setProfiles(PaceProfile.NONE, PaceProfile.NONE);
                result = engine.process(event);
            }
            requestProfileLoad(fightStart.fightName(), fightStart.zoneId(), fightStart.playerJobId());
        } else {
            synchronized (lock) {
                if (event instanceof CombatEvent.Tick || event instanceof CombatEvent.FightEnd) {
                    engine.setEnrageInfo(enrageTimeProvider.getEnrageTime(currentTerritoryId));
                }
                result = engine.process(event);
            }
        }

        result.snapshot().ifPresent(snapshotPublisher::publish);
        return result;
    }

    public void setCurrentPlayerId(ActorId playerId) {
        synchronized (lock) {
            engine.setCurrentPlayerId(playerId);
            if (playerId == null) {
                return;
            }
        }

        Integer knownJobId = engine.jobIdMap().get(playerId);
        if (knownJobId != null && knownJobId > 0) {
            refreshProfilesIfPossible(playerId, knownJobId);
        }
    }

    public void setJobId(ActorId actorId, int jobId) {
        synchronized (lock) {
            engine.setJobId(actorId, jobId);
        }
        refreshProfilesIfPossible(actorId, jobId);
    }

    public void setOwner(ActorId petId, ActorId ownerId) {
        synchronized (lock) {
            engine.setOwner(petId, ownerId);
        }
    }

    public void clearCombatantContext() {
        synchronized (lock) {
            engine.clearCombatantContext();
            engine.setProfiles(PaceProfile.NONE, PaceProfile.NONE);
            currentTerritoryId = 0;
            profileLoadGeneration.incrementAndGet();
        }
    }

    public CombatDebugSnapshot debugSnapshot() {
        CombatState state;
        Map<ActorId, Integer> jobIds;
        ActorId currentPlayerId;
        int territoryId;

        synchronized (lock) {
            state = engine.currentState();
            jobIds = engine.jobIdMap();
            currentPlayerId = engine.currentPlayerId();
            territoryId = currentTerritoryId;
        }

        Map<ActorId, RdpsEstimate> estimates = onlineEstimator.estimate(state);
        List<CombatDebugSnapshot.ActorDebugEntry> actors = new ArrayList<>();
        for (Map.Entry<ActorId, ActorStats> entry : state.actors().entrySet()) {
            ActorId actorId = entry.getKey();
            ActorStats stats = entry.getValue();
            if (stats.name() == null || stats.name().isBlank()) {
                continue;
            }
            actors.add(toDebugEntry(actorId, stats, jobIds, estimates, currentPlayerId));
        }
        actors.sort(Comparator.comparingDouble(CombatDebugSnapshot.ActorDebugEntry::onlineRdps).reversed());

        CombatDebugSnapshot.ActorDebugEntry currentPlayer = actors.stream()
                .filter(CombatDebugSnapshot.ActorDebugEntry::currentPlayer)
                .findFirst()
                .orElse(null);
        Optional<CombatState.BossInfo> bossInfo = state.bossInfo();
        Optional<EnrageTimeProvider.EnrageInfo> enrageInfo = territoryId > 0
                ? this.enrageTimeProvider.getEnrageTime(territoryId)
                : Optional.empty();

        return new CombatDebugSnapshot(
                state.fightName(),
                state.phase(),
                state.elapsedMs(),
                territoryId,
                currentPlayerId,
                currentPlayer,
                List.copyOf(actors),
                bossInfo.map(this::toBossDebugInfo).orElse(null),
                enrageInfo.map(this::toEnrageDebugInfo).orElse(null)
        );
    }

    private CombatDebugSnapshot.ActorDebugEntry toDebugEntry(
            ActorId actorId,
            ActorStats stats,
            Map<ActorId, Integer> jobIds,
            Map<ActorId, RdpsEstimate> estimates,
            ActorId currentPlayerId
    ) {
        List<CombatDebugSnapshot.ActiveBuffEntry> activeBuffs = stats.activeBuffs().stream()
                .map(this::toActiveBuffEntry)
                .toList();
        RdpsEstimate estimate = estimates.getOrDefault(actorId, new RdpsEstimate(0.0, Confidence.none()));
        return new CombatDebugSnapshot.ActorDebugEntry(
                actorId,
                stats.name(),
                jobIds.getOrDefault(actorId, 0),
                actorId.equals(currentPlayerId),
                stats.totalDamage(),
                stats.recentDamage(),
                stats.totalReceivedBuffContribution(),
                stats.totalGrantedBuffContribution(),
                estimate.actorOnlineRdps(),
                stats.hitCount(),
                stats.observedHitSampleCount(),
                stats.observedCritHitCount(),
                stats.observedDirectHitCount(),
                activeBuffs
        );
    }

    private CombatDebugSnapshot.ActiveBuffEntry toActiveBuffEntry(ActiveBuff activeBuff) {
        return new CombatDebugSnapshot.ActiveBuffEntry(
                activeBuff.buffId().value(),
                activeBuff.buffName(),
                activeBuff.sourceId(),
                activeBuff.appliedAtMs(),
                activeBuff.durationMs()
        );
    }

    private CombatDebugSnapshot.BossDebugInfo toBossDebugInfo(CombatState.BossInfo bossInfo) {
        return new CombatDebugSnapshot.BossDebugInfo(
                bossInfo.actorId(),
                bossInfo.name(),
                bossInfo.maxHp()
        );
    }

    private CombatDebugSnapshot.EnrageDebugInfo toEnrageDebugInfo(EnrageTimeProvider.EnrageInfo enrageInfo) {
        return new CombatDebugSnapshot.EnrageDebugInfo(
                enrageInfo.seconds(),
                enrageInfo.confidence().name(),
                enrageInfo.source()
        );
    }

    private void refreshProfilesIfPossible(ActorId actorId, int jobId) {
        if (jobId <= 0) {
            return;
        }

        String fightName;
        int territoryId;
        synchronized (lock) {
            ActorId currentPlayerId = engine.currentPlayerId();
            if (currentPlayerId == null || !currentPlayerId.equals(actorId)) {
                return;
            }
            CombatState state = engine.currentState();
            if (state.phase() != CombatState.Phase.ACTIVE || currentTerritoryId <= 0) {
                return;
            }
            fightName = state.fightName();
            territoryId = currentTerritoryId;
        }

        requestProfileLoad(fightName, territoryId, jobId);
    }

    private void requestProfileLoad(String fightName, int territoryId, int playerJobId) {
        long generation = profileLoadGeneration.incrementAndGet();
        profileLoader.execute(() -> loadProfilesAsync(generation, fightName, territoryId, playerJobId));
    }

    private void loadProfilesAsync(long generation, String fightName, int territoryId, int playerJobId) {
        PaceProfile partyProfile = paceProfileProvider.findProfile(fightName, territoryId, 0)
                .orElse(PaceProfile.NONE);
        PaceProfile individualProfile = paceProfileProvider.findIndividualProfile(fightName, territoryId, playerJobId)
                .orElse(PaceProfile.NONE);

        synchronized (lock) {
            if (generation != profileLoadGeneration.get()) {
                return;
            }
            CombatState state = engine.currentState();
            if (state.phase() != CombatState.Phase.ACTIVE || currentTerritoryId != territoryId) {
                return;
            }
            engine.setProfiles(partyProfile, individualProfile);
        }
    }

    private static final class ProfileLoaderThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "fflogs-profile-loader");
            thread.setDaemon(true);
            return thread;
        }
    }
}
