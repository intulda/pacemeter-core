package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.*;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FfxivJobMapper;
import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActionNameLibrary;
import com.bohouse.pacemeter.core.model.ActionTagCatalog;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.AutoHitCatalog;
import com.bohouse.pacemeter.core.model.BuffId;
import com.bohouse.pacemeter.core.model.DamageType;
import com.bohouse.pacemeter.core.model.DotAttributionRules;
import com.bohouse.pacemeter.core.model.DotStatusLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.prefs.Preferences;

@Component
public final class ActIngestionService {
    private static final Logger logger = LoggerFactory.getLogger(ActIngestionService.class);
    private static final long BOSS_MIN_MAX_HP = 10_000_000L;
    private static final long UNKNOWN_ACTOR_ID = 0xE0000000L;
    private static final DotAttributionRules DOT_ATTRIBUTION_RULES = DotAttributionRules.fromCatalog();
    private static final long UNKNOWN_STATUS_DOT_WINDOW_MS = 90_000L;
    private static final long CORROBORATED_KNOWN_SOURCE_WINDOW_MS = 15_000L;
    private static final long KNOWN_SOURCE_MULTI_TARGET_EXACT_WINDOW_MS = 15_000L;
    private static final Duration STATUS_SNAPSHOT_REDISTRIBUTION_WINDOW = Duration.ofMillis(10000);
    private static final Duration STATUS_SIGNAL_REDISTRIBUTION_WINDOW = Duration.ofMillis(3500);
    private static final Duration LIVE_DOT_ATTRIBUTION_DEBUG_RETENTION = Duration.ofSeconds(30);
    private static final double STATUS_SNAPSHOT_WEIGHT_GAMMA = 0.74;
    private static final double STATUS_SIGNAL_WEIGHT_BLEND_ALPHA = 0.80;
    private static final double STATUS0_SOURCE_HINT_WEIGHT = 1.0;
    private static final double ACTIVE_DOT_SUBSET_WEIGHT_COVERAGE_THRESHOLD = 0.50;
    private static final Set<Integer> INVALID_DOT_ACTION_IDS = Set.of(0x7, 0x17);
    private static final Duration LIVE_DOT_APPLICATION_CLONE_WINDOW = Duration.ofSeconds(1);
    private static final Map<Integer, Integer> LIVE_DOT_APPLICATION_CLONE_STATUS_TO_ACTION = Map.of(
            0x04CC, 0x1D41,
            0x0A9F, 0x64AC
    );
    private static final Set<Integer> LIVE_DOT_TICK_SUPPRESSED_ACTION_IDS =
            Set.copyOf(LIVE_DOT_APPLICATION_CLONE_STATUS_TO_ACTION.values());
    private static final Duration SELF_JOB_METADATA_GRACE = Duration.ofMillis(1500);
    private static final String SELF_JOB_PREF_NODE = "live-self-job";
    private static final String ACTOR_JOB_PREF_NODE = "live-actor-job";
    private final CombatEventPort combatEventPort;
    private final CombatService combatService;
    private final FflogsZoneLookup fflogsZoneLookup;
    private final Preferences preferences = Preferences.userNodeForPackage(ActIngestionService.class).node(SELF_JOB_PREF_NODE);
    private final Preferences actorJobPreferences = Preferences.userNodeForPackage(ActIngestionService.class).node(ACTOR_JOB_PREF_NODE);

    private volatile long currentPlayerId = 0;
    private volatile String currentPlayerName = "YOU";
    private volatile int currentPlayerJobId = 0;  // 현재 플레이어 직업 ID
    private volatile String currentZoneName = "";
    private volatile int currentZoneId = 0;

    private final Map<Long, Long> ownerByCombatantId = new HashMap<>();
    private final Set<Long> partyMemberIds = new HashSet<>();  // 파티원 ID 추적
    private final Set<Long> combatPartyMemberIds = new HashSet<>(); // 전투 중 확인된 파티원 ID
    private final Set<Long> deadPlayers = new HashSet<>();     // 사망한 파티원 ID
    private final Map<Long, Integer> jobIdByActorId = new HashMap<>();  // 캐릭터별 직업 ID
    private final Map<Long, String> actorNameById = new HashMap<>();
    private final Deque<DamageText> pendingDamageTexts = new ArrayDeque<>();
    private final Deque<NetworkAbilityRaw> pendingSelfJobAbilities = new ArrayDeque<>();
    private final Deque<PendingBuffEvent> pendingBuffEvents = new ArrayDeque<>();
    private final Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication> unknownStatusDotApplications = new HashMap<>();
    private final Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication> unknownStatusDotStatusApplications = new HashMap<>();
    private final Map<Long, UnknownStatusDotAttributionResolver.DotApplication> unknownStatusDotApplicationsBySource = new HashMap<>();
    private final Map<Long, UnknownStatusDotAttributionResolver.DotApplication> unknownStatusDotStatusApplicationsBySource = new HashMap<>();
    private final Map<Long, SourceDotEvidence> unknownStatusDotActionEvidenceBySource = new HashMap<>();
    private final Map<Long, SourceDotEvidence> unknownStatusDotStatusEvidenceBySource = new HashMap<>();
    private final UnknownStatusDotAttributionResolver unknownStatusDotAttributionResolver =
            new UnknownStatusDotAttributionResolver();
    private final Map<Long, Map<TrackedDotKey, TrackedDotState>> activeTargetDots = new HashMap<>();
    private final Map<Long, Set<Integer>> activeSelfBuffIdsByActor = new HashMap<>();
    private final Map<Long, Set<String>> activeSelfBuffNamesByActor = new HashMap<>();
    private final Map<Long, StatusSnapshotState> latestStatusSnapshotsByTarget = new HashMap<>();
    private final Map<Long, Deque<StatusSignalEvidence>> recentStatusSignalsByTarget = new HashMap<>();
    private final Map<String, Long> dotAttributionModeCounts = new HashMap<>();
    private final Map<String, Long> dotAttributionAssignedAmountByKey = new HashMap<>();
    private final Map<String, Long> dotAttributionAssignedHitCountByKey = new HashMap<>();
    private final Deque<DotAttributionAssignment> recentDotAttributionAssignments = new ArrayDeque<>();
    private final Map<LiveDotApplicationCloneKey, RecentDamageCloneCandidate> recentDotApplicationCloneCandidates = new HashMap<>();
    private final StatusZeroDotAllocationPlanner statusZeroDotAllocationPlanner = new StatusZeroDotAllocationPlanner();
    private BossCandidate pendingBoss;
    private Long announcedBossId;

    // ACT가 파티 정보를 명시적으로 전달했는지 여부
    // false = 아직 미수신 상태로 보고 모든 PC를 허용
    // true  = PartyList 또는 CombatData에서 파티 목록 수신 완료
    private volatile boolean partyDataInitialized = false;

    private static final long COMBAT_TIMEOUT_MS = 30_000; // 30초 무활동이면 전투 종료
    private volatile boolean fightStarted = false;
    private volatile Instant fightStartInstant = null;
    private volatile Instant lastDamageAt = null;
    private volatile Instant lastEventInstant = null;  // 마지막 ACT 이벤트 타임스탬프(wall clock)

    // 진단용 카운터
    private volatile long receivedAbilityCount = 0;
    private volatile long emittedDamageCount = 0;
    private volatile long filteredByYouCount = 0;
    private volatile long zeroDamageCount = 0;

    public ActIngestionService(CombatEventPort combatEventPort, CombatService combatService,
                               FflogsZoneLookup fflogsZoneLookup) {
        this.combatEventPort = combatEventPort;
        this.combatService = combatService;
        this.fflogsZoneLookup = fflogsZoneLookup;
    }

    public boolean isFightStarted() {
        if (!fightStarted) return false;

        // 마지막 대미지 이후 일정 시간이 지나면 전투를 자동 종료한다.
        if (lastDamageAt != null && lastEventInstant != null) {
            try {
                long idleMs = Duration.between(lastDamageAt, lastEventInstant).toMillis();
                if (idleMs > COMBAT_TIMEOUT_MS) {
                    logger.info("[Ingestion] combat timeout ({}ms idle), ending fight", idleMs);
                    endFight();
                    return false;
                }
            } catch (Exception e) {
                logger.error("[Ingestion] error checking combat timeout: {}", e.getMessage(), e);
            }
        }

        return true;
    }

    private void endFight() {
        if (!fightStarted) {
            logger.debug("[Ingestion] endFight called but fight not started, ignoring");
            return;
        }

        long elapsedMs = nowElapsedMs();
        logger.info("[Ingestion] fight ended, elapsed={}ms", elapsedMs);

        try {
            combatEventPort.onEvent(new CombatEvent.FightEnd(elapsedMs, false));
        } catch (Exception e) {
            logger.error("[Ingestion] error sending FightEnd event: {}", e.getMessage(), e);
        }
        // 전투 관련 상태 초기화
        fightStarted = false;
        fightStartInstant = null;
        lastDamageAt = null;
        lastEventInstant = null;
        deadPlayers.clear();
        combatPartyMemberIds.clear();
        pendingDamageTexts.clear();
        pendingSelfJobAbilities.clear();
        pendingBuffEvents.clear();
        unknownStatusDotApplications.clear();
        unknownStatusDotStatusApplications.clear();
        unknownStatusDotApplicationsBySource.clear();
        unknownStatusDotStatusApplicationsBySource.clear();
        unknownStatusDotActionEvidenceBySource.clear();
        unknownStatusDotStatusEvidenceBySource.clear();
        activeTargetDots.clear();
        activeSelfBuffIdsByActor.clear();
        activeSelfBuffNamesByActor.clear();
        latestStatusSnapshotsByTarget.clear();
        recentStatusSignalsByTarget.clear();
        dotAttributionModeCounts.clear();
        dotAttributionAssignedAmountByKey.clear();
        dotAttributionAssignedHitCountByKey.clear();
        recentDotAttributionAssignments.clear();
        recentDotApplicationCloneCandidates.clear();
        actorNameById.clear();
        pendingBoss = null;
        announcedBossId = null;
        // currentPlayerId, currentPlayerName은 유지한다. 다음 전투에서도 재사용한다.
    }

    /** TickDriver에서 현재 전투 기준 경과 ms를 제공한다.
     *  wall-clock 기준 마지막 ACT 이벤트 타임스탬프를 사용해 스레드 지연 영향을 줄인다. */
    public long nowElapsedMs() {
        if (!fightStarted || fightStartInstant == null) return 0;
        Instant ref = lastEventInstant != null ? lastEventInstant : fightStartInstant;
        long ms = Duration.between(fightStartInstant, ref).toMillis();
        return Math.max(0, ms);
    }

    public void onParsed(ParsedLine line) {
        if (line == null) return;

        // Tick 경과 시간 계산을 위해 전투 중 마지막 ACT 이벤트 시각을 추적한다.
        if (line.ts() != null && !(line instanceof DotStatusSignalRaw)) {
            lastEventInstant = line.ts();
        }

        if (line instanceof ZoneChanged z) {
            // 전투 중 Zone 변경이 발생하면 현재 전투를 종료한다.
            if (fightStarted) {
                logger.info("[Ingestion] Zone changed during combat ({}??}), ending fight",
                        currentZoneName, z.zoneName());
                endFight();
            }

            this.currentZoneId = z.zoneId();
            this.currentZoneName = z.zoneName();
            // 존 변경 시 파티 정보도 초기화하고, 다음 CombatData/PartyList를 다시 기다린다.
            partyDataInitialized = false;
            partyMemberIds.clear();
            combatPartyMemberIds.clear();
            ownerByCombatantId.clear();
            jobIdByActorId.clear();
            actorNameById.clear();
            pendingDamageTexts.clear();
            pendingSelfJobAbilities.clear();
            pendingBuffEvents.clear();
            unknownStatusDotApplications.clear();
            unknownStatusDotStatusApplications.clear();
            unknownStatusDotApplicationsBySource.clear();
            unknownStatusDotStatusApplicationsBySource.clear();
            unknownStatusDotActionEvidenceBySource.clear();
            unknownStatusDotStatusEvidenceBySource.clear();
            activeTargetDots.clear();
            activeSelfBuffIdsByActor.clear();
            activeSelfBuffNamesByActor.clear();
            latestStatusSnapshotsByTarget.clear();
            recentStatusSignalsByTarget.clear();
            dotAttributionModeCounts.clear();
            dotAttributionAssignedAmountByKey.clear();
            dotAttributionAssignedHitCountByKey.clear();
            recentDotAttributionAssignments.clear();
            recentDotApplicationCloneCandidates.clear();
            combatService.clearCombatantContext();
            pendingBoss = null;
            announcedBossId = null;
            if (currentPlayerId != 0) {
                actorNameById.put(currentPlayerId, currentPlayerName);
                partyMemberIds.add(currentPlayerId);
                if (currentPlayerJobId > 0) {
                    jobIdByActorId.put(currentPlayerId, currentPlayerJobId);
                    combatService.setJobId(new ActorId(currentPlayerId), currentPlayerJobId);
                }
            }
            logger.info("[Ingestion] ZoneChanged: id={} name={}", z.zoneId(), z.zoneName());
            return;
        }

        if (line instanceof PrimaryPlayerChanged p) {
            boolean playerIdentityChanged = currentPlayerId != 0
                    && (currentPlayerId != p.playerId() || !Objects.equals(currentPlayerName, p.playerName()));
            if (fightStarted && playerIdentityChanged) {
                logger.info("[Ingestion] primary player changed during fight: old={}({}) new={}({}), ending fight",
                        currentPlayerName, Long.toHexString(currentPlayerId),
                        p.playerName(), Long.toHexString(p.playerId()));
                endFight();
            }
            this.currentPlayerId = p.playerId();
            this.currentPlayerName = p.playerName();
            actorNameById.put(p.playerId(), p.playerName());
            partyMemberIds.add(p.playerId());
            int restoredJobId = loadPersistedCurrentPlayerJob(p.playerId(), p.playerName());
            if (restoredJobId > 0 && currentPlayerJobId == 0) {
                currentPlayerJobId = restoredJobId;
                logger.info("[Ingestion] restored cached current player job: playerId={} jobId={} ({})",
                        Long.toHexString(p.playerId()),
                        Integer.toHexString(restoredJobId),
                        FfxivJobMapper.toKoreanName(restoredJobId));
            }
            if (currentPlayerJobId > 0) {
                jobIdByActorId.put(p.playerId(), currentPlayerJobId);
                combatService.setJobId(new ActorId(p.playerId()), currentPlayerJobId);
            }
            // 직업 ID는 CombatantAdded 또는 PlayerStats에서 채운다.
            // 현재 플레이어 ID를 전달해 개인 데이터 비교 기준을 맞춘다.
            combatEventPort.setCurrentPlayerId(new ActorId(p.playerId()));
            logger.info("[Ingestion] current player set: id={} name={}", Long.toHexString(p.playerId()), p.playerName());
            return;
        }

        if (line instanceof PlayerStatsUpdated stats) {
            if (stats.jobId() > 0) {
                if (fightStarted && currentPlayerJobId > 0 && currentPlayerJobId != stats.jobId()) {
                    logger.info("[Ingestion] current player job changed from PlayerStats during fight: {} -> {}, ending fight",
                            Integer.toHexString(currentPlayerJobId), Integer.toHexString(stats.jobId()));
                    endFight();
                }
                currentPlayerJobId = stats.jobId();
                persistCurrentPlayerJob(currentPlayerId, currentPlayerName, stats.jobId());
                if (currentPlayerId != 0) {
                    jobIdByActorId.put(currentPlayerId, stats.jobId());
                    combatService.setJobId(new ActorId(currentPlayerId), stats.jobId());
                }
                logger.info("[Ingestion] PlayerStats job updated: playerId={} jobId={} ({})",
                        Long.toHexString(currentPlayerId),
                        Integer.toHexString(stats.jobId()),
                        FfxivJobMapper.toKoreanName(stats.jobId()));
                flushPendingSelfJobAbilities();
            }
            return;
        }

        if (line instanceof PartyList party) {
            // ACT PartyList로 실제 파티 목록 업데이트
            partyMemberIds.clear();
            partyMemberIds.addAll(party.partyMemberIds());
            if (fightStarted) {
                // 전투 중 PartyList 변화는 지연 반영되므로 전투 파티 목록에는 누적만 반영한다.
                combatPartyMemberIds.addAll(party.partyMemberIds());
            }
            partyDataInitialized = true;  // 명시적 파티 정보 수신 완료
            logger.info("[Ingestion] PartyList received: {} members: {}",
                    partyMemberIds.size(),
                    partyMemberIds.stream()
                            .map(id -> Long.toHexString(id))
                            .toList());
            return;
        }

        if (line instanceof CombatantAdded c) {
            logger.info("[Ingestion] CombatantAdded: name={}(id={}) jobId={} ownerId={} rawLine={}",
                    c.name(), Long.toHexString(c.id()), Integer.toHexString(c.jobId()),
                    Long.toHexString(c.ownerId()), c.rawLine());
            actorNameById.put(c.id(), c.name());
            boolean wasKnownPartyMember = partyMemberIds.contains(c.id());

            // 소환수 owner 정보 추적
            if (c.ownerId() != 0) {
                ownerByCombatantId.put(c.id(), c.ownerId());
                // core에도 owner 정보를 전달
                combatService.setOwner(new ActorId(c.id()), new ActorId(c.ownerId()));
            }

            // 플레이어 캐릭터인 경우 직업 정보와 파티 여부를 추적한다.
            if (isPlayerCharacter(c.id())) {
                jobIdByActorId.put(c.id(), c.jobId());
                combatService.setJobId(new ActorId(c.id()), c.jobId());  // core에도 직업 정보를 전달
                persistKnownActorJob(c.id(), c.name(), c.jobId());
                partyMemberIds.add(c.id());  // CombatData 복원 시에도 파티원으로 등록

                // 본인이면 currentPlayerJobId를 갱신
                if (c.id() == currentPlayerId || c.name().equals(currentPlayerName)) {
                    if (fightStarted && currentPlayerJobId != 0 && c.jobId() != 0 && c.jobId() != currentPlayerJobId) {
                        logger.info("[Ingestion] current player job changed during fight: {} -> {}, ending fight",
                                Integer.toHexString(currentPlayerJobId), Integer.toHexString(c.jobId()));
                        endFight();
                    }
                    currentPlayerId = c.id();
                    currentPlayerJobId = c.jobId();
                    persistCurrentPlayerJob(c.id(), c.name(), c.jobId());
                    combatEventPort.setCurrentPlayerId(new ActorId(c.id()));
                    logger.info("[Ingestion] CURRENT PLAYER detected: {}(id={}) jobId={} ({})",
                            c.name(), Long.toHexString(c.id()),
                            Integer.toHexString(c.jobId()),
                            FfxivJobMapper.toKoreanName(c.jobId()));
                    flushPendingSelfJobAbilities();
                }

                if (!partyDataInitialized
                        && !(c.id() == currentPlayerId || c.name().equals(currentPlayerName))
                        && !wasKnownPartyMember) {
                    partyMemberIds.remove(c.id());
                }

            }

            onCombatantAdded(c);
            return;
        }

        if (line instanceof CombatantStatusSnapshotRaw snapshot) {
            onParsed(new CombatantAdded(
                    snapshot.ts(),
                    snapshot.actorId(),
                    snapshot.actorName(),
                    snapshot.jobId(),
                    0L,
                    snapshot.currentHp(),
                    snapshot.maxHp(),
                    snapshot.rawLine()
            ));
            noteStatusSnapshot(new StatusSnapshotRaw(
                    snapshot.ts(),
                    snapshot.actorId(),
                    snapshot.actorName(),
                    snapshot.statuses(),
                    snapshot.rawLine()
            ));
            return;
        }

        if (line instanceof NetworkAbilityRaw a) {
            actorNameById.put(a.actorId(), a.actorName());
            actorNameById.put(a.targetId(), a.targetName());
            restoreActorJobFromCache(a.actorId(), a.actorName());
            if (queueSelfAbilityUntilJobMetadata(a)) {
                return;
            }
            receivedAbilityCount++;
            boolean isParty = isPartyMember(a);
            if (a.damage() <= 0) zeroDamageCount++;
            if (a.damage() > 0 && !isParty) filteredByYouCount++;

            logger.info("[Ingestion] NetworkAbility #{}: actor={}(id={}) skill={}({}) damage={} isParty={} | stats: recv={} emit={} filtered={} zero={}",
                    receivedAbilityCount,
                    a.actorName(), Long.toHexString(a.actorId()),
                    a.skillName(), Integer.toHexString(a.skillId()),
                    a.damage(), isParty,
                    receivedAbilityCount, emittedDamageCount, filteredByYouCount, zeroDamageCount);
            noteUnknownStatusDotApplication(a);
            if (a.damage() > 0) {
                emitDamage(a);
            }
            return;
        }

        if (line instanceof DotTickRaw d) {
            actorNameById.put(d.sourceId(), d.sourceName());
            actorNameById.put(d.targetId(), d.targetName());
            if (d.damage() > 0 && d.isDot()) {
                emitDotDamage(d);
            }
            return;
        }

        if (line instanceof StatusSnapshotRaw snapshot) {
            noteStatusSnapshot(snapshot);
            return;
        }

        if (line instanceof DotStatusSignalRaw) {
            noteDotStatusSignal((DotStatusSignalRaw) line);
            return;
        }

        if (line instanceof DamageText d) {
            pendingDamageTexts.addLast(d);
            pruneOldDamageTexts(d.ts());
            return;
        }

        if (line instanceof BuffApplyRaw b) {
            actorNameById.put(b.sourceId(), b.sourceName());
            actorNameById.put(b.targetId(), b.targetName());
            noteUnknownStatusDotBuffApply(b);
            trackActiveDot(b);
            if (!fightStarted) {
                pendingBuffEvents.addLast(PendingBuffEvent.apply(b));
                return;
            }
            emitBuffApply(b);
            return;
        }

        if (line instanceof BuffRemoveRaw b) {
            untrackActiveDot(b);
            if (!fightStarted) {
                pendingBuffEvents.addLast(PendingBuffEvent.remove(b));
                return;
            }
            emitBuffRemove(b);
            return;
        }

        if (line instanceof NetworkDeath d) {
            if (!fightStarted) return;

            long tsMs = toElapsedMs(d.ts());

            // 파티원 사망을 추적하고, 전원 사망 시 wipe 여부를 확인한다.
            if (effectivePartyMemberIds().contains(d.targetId())) {
                deadPlayers.add(d.targetId());
                int partySizeForWipeCheck = effectivePartyMemberCountForCombat();
                logger.info("[Ingestion] Party member died: {}(id={}) | dead={}/{} party members",
                        d.targetName(), Long.toHexString(d.targetId()),
                        deadPlayers.size(), partySizeForWipeCheck);

                // core에도 사망 이벤트를 전달
                combatEventPort.onEvent(new CombatEvent.ActorDeath(
                        tsMs,
                        new ActorId(d.targetId()),
                        d.targetName()
                ));

                // 파티 전멸이면 wipe로 판단하고 전투를 종료한다.
                if (deadPlayers.size() == partySizeForWipeCheck && partySizeForWipeCheck > 0) {
                    logger.info("[Ingestion] PARTY WIPE! Ending fight.");
                    endFight();
                }
            }
        }
    }

    boolean wouldEmitDamage(NetworkAbilityRaw ability) {
        if (!isPartyMember(ability)) {
            return false;
        }
        if (isFriendlyTarget(ability.targetId())) {
            return false;
        }
        return fightStarted || isValidCombatZone(ability);
    }

    boolean wouldEmitDotDamage(DotTickRaw dot) {
        if (isFriendlyTarget(dot.targetId())) {
            return false;
        }
        if (shouldAcceptDot(dot) && isPartyMember(dot.sourceId())) {
            return fightStarted || isValidCombatZone(dot.targetName());
        }
        if (resolveKnownStatusUnknownSourceAttribution(dot).isPresent()) {
            return fightStarted || isValidCombatZone(dot.targetName());
        }
        if (resolveUnknownSourceDotAttribution(dot).isPresent()) {
            return fightStarted || isValidCombatZone(dot.targetName());
        }
        if (resolveSnapshotRedistribution(dot).isEmpty()) {
            return false;
        }
        return fightStarted || isValidCombatZone(dot.targetName());
    }

    private boolean shouldAcceptDot(DotTickRaw dot) {
        if (dot.hasKnownStatus()) {
            return true;
        }
        Integer jobId = jobIdByActorId.get(dot.sourceId());
        if (jobId == null || !DOT_ATTRIBUTION_RULES.unknownStatusDotJobWhitelist().contains(jobId)) {
            return false;
        }
        Set<Integer> applicationActionIds = DOT_ATTRIBUTION_RULES.applicationActionsByJob().get(jobId);
        Set<Integer> statusIds = DOT_ATTRIBUTION_RULES.statusIdsByJob().get(jobId);
        boolean tracksApplicationAction = applicationActionIds != null && !applicationActionIds.isEmpty();
        boolean tracksStatusApply = statusIds != null && !statusIds.isEmpty();
        if (!tracksApplicationAction && !tracksStatusApply) {
            return false;
        }

        return resolveTrackedUnknownStatusDotStatusId(dot) != null
                || resolveTrackedUnknownStatusDotActionId(dot) != null;
    }

    private void noteUnknownStatusDotApplication(NetworkAbilityRaw ability) {
        Integer jobId = jobIdByActorId.get(ability.actorId());
        if (jobId == null) {
            return;
        }
        Set<Integer> actionIds = DOT_ATTRIBUTION_RULES.applicationActionsByJob().get(jobId);
        if (actionIds == null || !actionIds.contains(ability.skillId())) {
            return;
        }

        pruneExpiredUnknownStatusDotApplications(ability.ts());
        unknownStatusDotApplications.put(
                new UnknownStatusDotAttributionResolver.DotKey(ability.actorId(), ability.targetId()),
                new UnknownStatusDotAttributionResolver.DotApplication(ability.skillId(), ability.ts())
        );
        unknownStatusDotApplicationsBySource.put(
                ability.actorId(),
                new UnknownStatusDotAttributionResolver.DotApplication(ability.skillId(), ability.ts())
        );
        unknownStatusDotActionEvidenceBySource.put(
                ability.actorId(),
                new SourceDotEvidence(
                        ability.skillId(),
                        ability.ts(),
                        ability.targetId(),
                        ability.targetName()
                )
        );
    }

    private void noteUnknownStatusDotBuffApply(BuffApplyRaw buffApply) {
        Integer jobId = jobIdByActorId.get(buffApply.sourceId());
        if (jobId == null || !DOT_ATTRIBUTION_RULES.unknownStatusDotJobWhitelist().contains(jobId)) {
            return;
        }
        Set<Integer> expectedStatusIds = DOT_ATTRIBUTION_RULES.statusIdsByJob().get(jobId);
        if (expectedStatusIds == null || !expectedStatusIds.contains(buffApply.statusId())) {
            return;
        }
        if (!DotStatusLibrary.isLikelyDot(
                new BuffId(buffApply.statusId()),
                buffApply.statusName(),
                (long) (buffApply.durationSec() * 1000),
                new ActorId(buffApply.sourceId()),
                new ActorId(buffApply.targetId())
        )) {
            return;
        }

        pruneExpiredUnknownStatusDotApplications(buffApply.ts());
        unknownStatusDotStatusApplications.put(
                new UnknownStatusDotAttributionResolver.DotKey(buffApply.sourceId(), buffApply.targetId()),
                new UnknownStatusDotAttributionResolver.DotApplication(buffApply.statusId(), buffApply.ts())
        );
        unknownStatusDotStatusApplicationsBySource.put(
                buffApply.sourceId(),
                new UnknownStatusDotAttributionResolver.DotApplication(buffApply.statusId(), buffApply.ts())
        );
        unknownStatusDotStatusEvidenceBySource.put(
                buffApply.sourceId(),
                new SourceDotEvidence(
                        buffApply.statusId(),
                        buffApply.ts(),
                        buffApply.targetId(),
                        buffApply.targetName()
                )
        );
    }

    private void noteDotStatusSignal(DotStatusSignalRaw signal) {
        pruneExpiredUnknownStatusDotApplications(signal.ts());
        for (DotStatusSignalRaw.StatusSignal statusSignal : signal.signals()) {
            int statusId = statusSignal.statusId();
            if (toTrackedDotActionId(statusId) == 0) {
                continue;
            }
            long sourceId = statusSignal.sourceId();
            Integer jobId = jobIdByActorId.get(sourceId);
            if (jobId == null || !DOT_ATTRIBUTION_RULES.unknownStatusDotJobWhitelist().contains(jobId)) {
                continue;
            }
            Set<Integer> expectedStatusIds = DOT_ATTRIBUTION_RULES.statusIdsByJob().get(jobId);
            if (expectedStatusIds != null && !expectedStatusIds.isEmpty() && !expectedStatusIds.contains(statusId)) {
                continue;
            }

            UnknownStatusDotAttributionResolver.DotApplication application =
                    new UnknownStatusDotAttributionResolver.DotApplication(statusId, signal.ts());
            unknownStatusDotStatusApplications.put(
                    new UnknownStatusDotAttributionResolver.DotKey(sourceId, signal.targetId()),
                    application
            );
            unknownStatusDotStatusApplicationsBySource.put(sourceId, application);
            unknownStatusDotStatusEvidenceBySource.put(
                    sourceId,
                    new SourceDotEvidence(
                            statusId,
                            signal.ts(),
                            signal.targetId(),
                            actorNameById.getOrDefault(signal.targetId(), "")
                    )
            );
            recentStatusSignalsByTarget
                    .computeIfAbsent(signal.targetId(), ignored -> new ArrayDeque<>())
                    .addLast(new StatusSignalEvidence(signal.ts(), sourceId, toTrackedDotActionId(statusId)));
        }
        pruneExpiredStatusSignals(signal.ts());
    }

    private void trackActiveDot(BuffApplyRaw buffApply) {
        long durationMs = (long) (buffApply.durationSec() * 1000);
        int trackedActionId = toTrackedDotActionId(buffApply.statusId());
        if (trackedActionId == 0) {
            return;
        }
        if (!isPartyMember(buffApply.sourceId())
                || isFriendlyTarget(buffApply.targetId())
                || !DotStatusLibrary.isLikelyDot(
                        new BuffId(buffApply.statusId()),
                        buffApply.statusName(),
                        durationMs,
                        new ActorId(buffApply.sourceId()),
                        new ActorId(buffApply.targetId()))) {
            return;
        }

        pruneExpiredTrackedDots(buffApply.ts());
        activeTargetDots
                .computeIfAbsent(buffApply.targetId(), ignored -> new HashMap<>())
                .put(
                        new TrackedDotKey(buffApply.sourceId(), trackedActionId),
                        new TrackedDotState(
                                buffApply.sourceId(),
                                buffApply.sourceName(),
                                trackedActionId,
                                buffApply.ts().plusMillis(durationMs)
                        )
                );
    }

    private void untrackActiveDot(BuffRemoveRaw buffRemove) {
        int trackedActionId = toTrackedDotActionId(buffRemove.statusId());
        if (trackedActionId == 0) {
            return;
        }
        Map<TrackedDotKey, TrackedDotState> targetDots = activeTargetDots.get(buffRemove.targetId());
        if (targetDots == null) {
            return;
        }
        targetDots.remove(new TrackedDotKey(buffRemove.sourceId(), trackedActionId));
        if (targetDots.isEmpty()) {
            activeTargetDots.remove(buffRemove.targetId());
        }
    }

    private void pruneExpiredTrackedDots(Instant now) {
        Iterator<Map.Entry<Long, Map<TrackedDotKey, TrackedDotState>>> targetIterator = activeTargetDots.entrySet().iterator();
        while (targetIterator.hasNext()) {
            Map<TrackedDotKey, TrackedDotState> targetDots = targetIterator.next().getValue();
            targetDots.values().removeIf(dot -> !dot.expiresAt().isAfter(now));
            if (targetDots.isEmpty()) {
                targetIterator.remove();
            }
        }
    }

    private void noteStatusSnapshot(StatusSnapshotRaw snapshot) {
        Map<TrackedDotKey, Double> weights = new HashMap<>();
        for (StatusSnapshotRaw.StatusEntry entry : snapshot.statuses()) {
            if (!DOT_ATTRIBUTION_RULES.snapshotStatusIds().contains(entry.statusId())) {
                continue;
            }
            int trackedActionId = toTrackedDotActionId(entry.statusId());
            if (trackedActionId == 0) {
                continue;
            }
            double value = decodeSnapshotFloat(entry.rawValueHex());
            if (value <= 0.0) {
                continue;
            }
            value = Math.pow(value, STATUS_SNAPSHOT_WEIGHT_GAMMA);
            weights.put(new TrackedDotKey(entry.sourceId(), trackedActionId), value);
        }
        if (weights.isEmpty()) {
            return;
        }
        latestStatusSnapshotsByTarget.put(snapshot.actorId(), new StatusSnapshotState(snapshot.ts(), Map.copyOf(weights)));
    }

    private List<TrackedDotState> resolveTrackedTargetDots(DotTickRaw dot) {
        if (dot.statusId() != 0 || isFriendlyTarget(dot.targetId())) {
            return List.of();
        }
        pruneExpiredTrackedDots(dot.ts());
        Map<TrackedDotKey, TrackedDotState> targetDots = activeTargetDots.get(dot.targetId());
        if (targetDots == null || targetDots.isEmpty()) {
            return List.of();
        }
        return targetDots.values().stream()
                .filter(dotState -> isPartyMember(dotState.sourceId()))
                .sorted(Comparator.comparingLong(TrackedDotState::sourceId).thenComparingInt(TrackedDotState::actionId))
                .toList();
    }

    private List<TrackedDotState> resolveTrackedSourceDots(DotTickRaw dot) {
        if (dot.statusId() != 0 || isFriendlyTarget(dot.targetId())) {
            return List.of();
        }
        pruneExpiredTrackedDots(dot.ts());
        Map<TrackedDotKey, TrackedDotState> targetDots = activeTargetDots.get(dot.targetId());
        if (targetDots == null || targetDots.isEmpty()) {
            return List.of();
        }
        return targetDots.values().stream()
                .filter(dotState -> dotState.sourceId() == dot.sourceId())
                .filter(dotState -> isPartyMember(dotState.sourceId()))
                .sorted(Comparator.comparingLong(TrackedDotState::sourceId).thenComparingInt(TrackedDotState::actionId))
                .toList();
    }

    private List<SnapshotRedistributedDot> resolveSnapshotRedistribution(DotTickRaw dot) {
        if (dot.statusId() != 0 || isFriendlyTarget(dot.targetId())) {
            return List.of();
        }
        if (dot.sourceId() != 0
                && dot.sourceId() != UNKNOWN_ACTOR_ID
                && isPartyMember(dot.sourceId())
                && shouldAcceptDot(dot)) {
            return List.of();
        }
        StatusSnapshotState snapshot = latestStatusSnapshotsByTarget.get(dot.targetId());
        if (snapshot == null) {
            return List.of();
        }
        if (Duration.between(snapshot.ts(), dot.ts()).abs().compareTo(STATUS_SNAPSHOT_REDISTRIBUTION_WINDOW) > 0) {
            return List.of();
        }

        Map<TrackedDotKey, Double> redistributionWeights = selectSnapshotRedistributionWeights(dot, snapshot);
        redistributionWeights = applySourceHintWeighting(dot, redistributionWeights);
        redistributionWeights = applyStatusSignalWeighting(dot, redistributionWeights);
        double denominator = redistributionWeights.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        if (denominator <= 0.0) {
            return List.of();
        }

        List<StatusZeroDotAllocationPlanner.Candidate> candidates = redistributionWeights.entrySet().stream()
                .map(entry -> new StatusZeroDotAllocationPlanner.Candidate(
                        entry.getKey().sourceId(),
                        entry.getKey().actionId(),
                        entry.getValue() / denominator
                ))
                .toList();
        return statusZeroDotAllocationPlanner.allocate(dot.damage(), candidates).stream()
                .map(allocation -> new SnapshotRedistributedDot(
                        allocation.sourceId(),
                        allocation.actionId(),
                        allocation.amount()
                ))
                .toList();
    }

    private Map<TrackedDotKey, Double> selectSnapshotRedistributionWeights(DotTickRaw dot, StatusSnapshotState snapshot) {
        Map<TrackedDotKey, Double> fallbackWeights = new HashMap<>();
        for (Map.Entry<TrackedDotKey, Double> entry : snapshot.weights().entrySet()) {
            TrackedDotKey key = entry.getKey();
            if (isPartyMember(key.sourceId())) {
                fallbackWeights.put(key, entry.getValue());
            }
        }
        if (fallbackWeights.isEmpty()) {
            return Map.of();
        }

        pruneExpiredTrackedDots(dot.ts());
        Map<TrackedDotKey, TrackedDotState> activeDots = activeTargetDots.get(dot.targetId());
        if (activeDots == null || activeDots.isEmpty()) {
            return fallbackWeights;
        }
        boolean suppressUnknownMultiTargetFallback = shouldSuppressUnknownMultiTargetFallback(dot);

        Map<TrackedDotKey, Double> sameSourceFallbackWeights = selectKnownSourceWeights(dot, fallbackWeights);
        if (!sameSourceFallbackWeights.isEmpty()) {
            return sameSourceFallbackWeights;
        }

        Map<TrackedDotKey, Double> activeWeights = new HashMap<>();
        for (Map.Entry<TrackedDotKey, Double> entry : fallbackWeights.entrySet()) {
            if (activeDots.containsKey(entry.getKey())) {
                activeWeights.put(entry.getKey(), entry.getValue());
            }
        }
        if (suppressUnknownMultiTargetFallback && !activeWeights.isEmpty()) {
            return Map.of();
        }
        if (!activeWeights.isEmpty() && shouldPreferActiveTrackedDotSubset(activeWeights, fallbackWeights)) {
            return activeWeights;
        }
        if (suppressUnknownMultiTargetFallback) {
            return Map.of();
        }
        return fallbackWeights;
    }

    private boolean shouldSuppressUnknownMultiTargetFallback(DotTickRaw dot) {
        if (dot.sourceId() != UNKNOWN_ACTOR_ID) {
            return false;
        }
        return countTrackedTargetsWithActiveDots() > 1;
    }

    private boolean shouldSuppressKnownSourceGuidMissingMultiTargetFallback(DotTickRaw dot) {
        if (dot.sourceId() == 0 || dot.sourceId() == UNKNOWN_ACTOR_ID || !isPartyMember(dot.sourceId())) {
            return false;
        }
        if (countTrackedTargetsWithActiveDots() <= 1) {
            return false;
        }
        if (!resolveTrackedSourceDots(dot).isEmpty()) {
            return false;
        }
        StatusSnapshotState snapshot = latestStatusSnapshotsByTarget.get(dot.targetId());
        if (snapshot == null) {
            return false;
        }
        return snapshot.weights().keySet().stream()
                .filter(key -> isPartyMember(key.sourceId()))
                .noneMatch(key -> key.sourceId() == dot.sourceId());
    }

    private boolean shouldPreferCorroboratedKnownSourceAttribution(DotTickRaw dot) {
        if (!shouldSuppressKnownSourceGuidMissingMultiTargetFallback(dot)) {
            return false;
        }
        return resolveCorroboratedTrackedUnknownStatusDotActionId(dot, CORROBORATED_KNOWN_SOURCE_WINDOW_MS) != null;
    }

    private boolean shouldSuppressKnownSourceMismatchedTrackedTargetSplit(
            DotTickRaw dot,
            List<TrackedDotState> trackedDots
    ) {
        if (!shouldSuppressKnownSourceGuidMissingMultiTargetFallback(dot) || trackedDots.isEmpty()) {
            return false;
        }
        Integer recentSourceActionId = resolveRecentSourceUnknownStatusActionId(dot);
        if (recentSourceActionId == null) {
            return false;
        }
        return trackedDots.stream().noneMatch(trackedDot -> trackedDot.actionId() == recentSourceActionId);
    }

    private boolean shouldRequireRecentExactEvidenceForKnownSourceAcceptedBySource(DotTickRaw dot) {
        if (dot.statusId() != 0 || isFriendlyTarget(dot.targetId())) {
            return false;
        }
        if (dot.sourceId() == 0 || dot.sourceId() == UNKNOWN_ACTOR_ID || !isPartyMember(dot.sourceId())) {
            return false;
        }
        return countTrackedTargetsWithActiveDots() > 1;
    }

    private long countTrackedTargetsWithActiveDots() {
        return activeTargetDots.values().stream()
                .filter(states -> states != null && !states.isEmpty())
                .count();
    }

    private Map<TrackedDotKey, Double> selectKnownSourceWeights(
            DotTickRaw dot,
            Map<TrackedDotKey, Double> candidateWeights
    ) {
        if (candidateWeights.isEmpty()) {
            return Map.of();
        }
        long sourceId = dot.sourceId();
        if (sourceId == 0 || sourceId == UNKNOWN_ACTOR_ID || !isPartyMember(sourceId)) {
            return Map.of();
        }
        Map<TrackedDotKey, Double> sameSourceWeights = new HashMap<>();
        for (Map.Entry<TrackedDotKey, Double> entry : candidateWeights.entrySet()) {
            if (entry.getKey().sourceId() == sourceId) {
                sameSourceWeights.put(entry.getKey(), entry.getValue());
            }
        }
        return sameSourceWeights;
    }

    private static boolean shouldPreferActiveTrackedDotSubset(
            Map<TrackedDotKey, Double> activeWeights,
            Map<TrackedDotKey, Double> fallbackWeights
    ) {
        if (activeWeights.isEmpty() || fallbackWeights.isEmpty()) {
            return false;
        }
        if (activeWeights.size() == fallbackWeights.size()) {
            return true;
        }
        if (activeWeights.size() < 2) {
            return false;
        }

        double fallbackWeightSum = fallbackWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (fallbackWeightSum <= 0.0) {
            return false;
        }
        double activeWeightSum = activeWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        return (activeWeightSum / fallbackWeightSum) >= ACTIVE_DOT_SUBSET_WEIGHT_COVERAGE_THRESHOLD;
    }

    private Map<TrackedDotKey, Double> applyStatusSignalWeighting(DotTickRaw dot, Map<TrackedDotKey, Double> baseWeights) {
        if (baseWeights.isEmpty()) {
            return baseWeights;
        }
        pruneExpiredStatusSignals(dot.ts());
        Deque<StatusSignalEvidence> targetSignals = recentStatusSignalsByTarget.get(dot.targetId());
        if (targetSignals == null || targetSignals.isEmpty()) {
            return baseWeights;
        }

        Instant cutoff = dot.ts().minus(STATUS_SIGNAL_REDISTRIBUTION_WINDOW);
        Map<TrackedDotKey, Integer> signalCounts = new HashMap<>();
        for (StatusSignalEvidence signal : targetSignals) {
            if (signal.ts().isBefore(cutoff) || signal.ts().isAfter(dot.ts())) {
                continue;
            }
            TrackedDotKey key = new TrackedDotKey(signal.sourceId(), signal.actionId());
            if (!baseWeights.containsKey(key)) {
                continue;
            }
            signalCounts.merge(key, 1, Integer::sum);
        }
        if (signalCounts.isEmpty()) {
            return baseWeights;
        }

        double baseSum = baseWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (baseSum <= 0.0) {
            return baseWeights;
        }
        int totalSignalCount = signalCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalSignalCount <= 0) {
            return baseWeights;
        }

        double confidenceScale = Math.min(1.0, signalCounts.size() / 2.0);
        double alpha = STATUS_SIGNAL_WEIGHT_BLEND_ALPHA * confidenceScale;
        Map<TrackedDotKey, Double> adjusted = new HashMap<>();
        for (Map.Entry<TrackedDotKey, Double> entry : baseWeights.entrySet()) {
            TrackedDotKey key = entry.getKey();
            double snapshotWeight = entry.getValue();
            double signalRatio = signalCounts.getOrDefault(key, 0) / (double) totalSignalCount;
            double blended = snapshotWeight * (1.0 - alpha)
                    + (baseSum * signalRatio * alpha);
            adjusted.put(key, Math.max(0.0, blended));
        }
        return adjusted;
    }

    private Map<TrackedDotKey, Double> applySourceHintWeighting(DotTickRaw dot, Map<TrackedDotKey, Double> baseWeights) {
        if (baseWeights.isEmpty() || dot.statusId() != 0) {
            return baseWeights;
        }
        long sourceId = dot.sourceId();
        if (sourceId == 0 || sourceId == UNKNOWN_ACTOR_ID || !isPartyMember(sourceId)) {
            return baseWeights;
        }
        boolean hasSourceCandidate = baseWeights.keySet().stream().anyMatch(key -> key.sourceId() == sourceId);
        if (!hasSourceCandidate) {
            return baseWeights;
        }
        Map<TrackedDotKey, Double> adjusted = new HashMap<>();
        for (Map.Entry<TrackedDotKey, Double> entry : baseWeights.entrySet()) {
            double weight = entry.getValue();
            if (entry.getKey().sourceId() == sourceId) {
                weight *= STATUS0_SOURCE_HINT_WEIGHT;
            }
            adjusted.put(entry.getKey(), weight);
        }
        return adjusted;
    }

    private static double decodeSnapshotFloat(String rawValueHex) {
        if (rawValueHex == null || rawValueHex.isBlank()) {
            return 0.0;
        }
        try {
            int bits = (int) Long.parseUnsignedLong(rawValueHex, 16);
            return Float.intBitsToFloat(bits);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void pruneExpiredUnknownStatusDotApplications(Instant now) {
        Instant cutoff = now.minusMillis(UNKNOWN_STATUS_DOT_WINDOW_MS);
        unknownStatusDotApplications.entrySet().removeIf(entry -> entry.getValue().appliedAt().isBefore(cutoff));
        unknownStatusDotStatusApplications.entrySet().removeIf(entry -> entry.getValue().appliedAt().isBefore(cutoff));
        unknownStatusDotApplicationsBySource.entrySet().removeIf(entry -> entry.getValue().appliedAt().isBefore(cutoff));
        unknownStatusDotStatusApplicationsBySource.entrySet().removeIf(entry -> entry.getValue().appliedAt().isBefore(cutoff));
        unknownStatusDotActionEvidenceBySource.entrySet().removeIf(entry -> entry.getValue().appliedAt().isBefore(cutoff));
        unknownStatusDotStatusEvidenceBySource.entrySet().removeIf(entry -> entry.getValue().appliedAt().isBefore(cutoff));
    }

    private void pruneExpiredStatusSignals(Instant now) {
        Instant cutoff = now.minusMillis(UNKNOWN_STATUS_DOT_WINDOW_MS);
        Iterator<Map.Entry<Long, Deque<StatusSignalEvidence>>> iterator = recentStatusSignalsByTarget.entrySet().iterator();
        while (iterator.hasNext()) {
            Deque<StatusSignalEvidence> signals = iterator.next().getValue();
            while (!signals.isEmpty() && signals.peekFirst().ts().isBefore(cutoff)) {
                signals.removeFirst();
            }
            if (signals.isEmpty()) {
                iterator.remove();
            }
        }
    }

    int resolveDotActionId(DotTickRaw dot) {
        if (dot.hasKnownStatus()) {
            int trackedActionId = toTrackedDotActionId(dot.statusId());
            return trackedActionId != 0 ? trackedActionId : dot.statusId();
        }
        Integer actionId = resolveCorroboratedTrackedUnknownStatusDotActionId(dot);
        if (actionId == null) {
            actionId = resolveTrackedUnknownStatusDotStatusId(dot);
        }
        if (actionId == null) {
            actionId = resolveTrackedUnknownStatusDotActionId(dot);
        }
        return actionId != null ? actionId : dot.statusId();
    }

    private Integer resolveCorroboratedTrackedUnknownStatusDotActionId(DotTickRaw dot) {
        return resolveCorroboratedTrackedUnknownStatusDotActionId(dot, UNKNOWN_STATUS_DOT_WINDOW_MS);
    }

    private Integer resolveCorroboratedTrackedUnknownStatusDotActionId(DotTickRaw dot, long windowMs) {
        pruneExpiredUnknownStatusDotApplications(dot.ts());
        return unknownStatusDotAttributionResolver.resolveCorroboratedActionId(
                dot,
                unknownStatusDotApplications,
                unknownStatusDotStatusApplications,
                windowMs,
                this::toTrackedDotActionId
        );
    }

    private Integer resolveTrackedUnknownStatusDotStatusId(DotTickRaw dot) {
        pruneExpiredUnknownStatusDotApplications(dot.ts());
        Integer resolved = unknownStatusDotAttributionResolver.resolveTrackedStatusActionId(
                dot,
                unknownStatusDotStatusApplications,
                UNKNOWN_STATUS_DOT_WINDOW_MS,
                this::toTrackedDotActionId
        );
        if (resolved != null) {
            return resolved;
        }
        return resolveSourceEvidenceFallbackActionId(
                dot,
                unknownStatusDotStatusEvidenceBySource,
                this::toTrackedDotActionId
        );
    }

    Integer debugJobId(long actorId) {
        return jobIdByActorId.get(actorId);
    }

    Map<String, Long> debugDotAttributionModeCounts() {
        return Map.copyOf(dotAttributionModeCounts);
    }

    Map<String, Long> debugDotAttributionAssignedAmounts() {
        return Map.copyOf(dotAttributionAssignedAmountByKey);
    }

    Map<String, Long> debugDotAttributionAssignedHitCounts() {
        return Map.copyOf(dotAttributionAssignedHitCountByKey);
    }

    public LiveDotAttributionDebugSnapshot debugLiveDotAttributionSnapshot(long lookbackSeconds) {
        long sanitizedLookbackSeconds = Math.max(1L, Math.min(60L, lookbackSeconds));
        Instant now = lastEventInstant != null ? lastEventInstant : Instant.now();
        pruneRecentDotAttributionAssignments(now);
        Instant cutoff = now.minusSeconds(sanitizedLookbackSeconds);
        Map<DotAttributionRollupKey, DotAttributionRollup> rollups = new HashMap<>();
        int recentAssignmentCount = 0;
        for (DotAttributionAssignment assignment : recentDotAttributionAssignments) {
            if (assignment.ts().isBefore(cutoff)) {
                continue;
            }
            recentAssignmentCount++;
            rollups.computeIfAbsent(
                            new DotAttributionRollupKey(
                                    assignment.mode(),
                                    assignment.sourceId(),
                                    assignment.sourceName(),
                                    assignment.actionId()
                            ),
                            ignored -> new DotAttributionRollup()
                    )
                    .add(assignment.amount());
        }
        List<LiveDotAttributionDebugSnapshot.Entry> entries = rollups.entrySet().stream()
                .sorted((left, right) -> {
                    int amountCompare = Long.compare(right.getValue().totalAmount, left.getValue().totalAmount);
                    if (amountCompare != 0) {
                        return amountCompare;
                    }
                    int hitCompare = Long.compare(right.getValue().hitCount, left.getValue().hitCount);
                    if (hitCompare != 0) {
                        return hitCompare;
                    }
                    int modeCompare = left.getKey().mode.compareTo(right.getKey().mode);
                    if (modeCompare != 0) {
                        return modeCompare;
                    }
                    int sourceCompare = Long.compare(left.getKey().sourceId, right.getKey().sourceId);
                    if (sourceCompare != 0) {
                        return sourceCompare;
                    }
                    return Integer.compare(left.getKey().actionId, right.getKey().actionId);
                })
                .map(entry -> new LiveDotAttributionDebugSnapshot.Entry(
                        entry.getKey().mode,
                        entry.getKey().sourceId,
                        entry.getKey().sourceName,
                        entry.getKey().actionId,
                        entry.getValue().totalAmount,
                        entry.getValue().hitCount
                ))
                .toList();
        return new LiveDotAttributionDebugSnapshot(
                sanitizedLookbackSeconds,
                recentAssignmentCount,
                entries
        );
    }

    boolean debugHasUnknownStatusDotAction(long sourceId, long targetId) {
        return unknownStatusDotApplications.containsKey(new UnknownStatusDotAttributionResolver.DotKey(sourceId, targetId));
    }

    boolean debugHasUnknownStatusDotStatus(long sourceId, long targetId) {
        return unknownStatusDotStatusApplications.containsKey(new UnknownStatusDotAttributionResolver.DotKey(sourceId, targetId));
    }

    private Integer resolveTrackedUnknownStatusDotActionId(DotTickRaw dot) {
        pruneExpiredUnknownStatusDotApplications(dot.ts());
        Integer resolved = unknownStatusDotAttributionResolver.resolveTrackedApplicationActionId(
                dot,
                unknownStatusDotApplications,
                UNKNOWN_STATUS_DOT_WINDOW_MS
        );
        if (resolved != null) {
            return resolved;
        }
        return resolveSourceEvidenceFallbackActionId(
                dot,
                unknownStatusDotActionEvidenceBySource,
                actionId -> actionId
        );
    }

    private Integer resolveRecentSourceUnknownStatusActionId(DotTickRaw dot) {
        pruneExpiredUnknownStatusDotApplications(dot.ts());
        UnknownStatusDotAttributionResolver.DotApplication actionApplication =
                unknownStatusDotApplicationsBySource.get(dot.sourceId());
        UnknownStatusDotAttributionResolver.DotApplication statusApplication =
                unknownStatusDotStatusApplicationsBySource.get(dot.sourceId());
        Instant cutoff = dot.ts().minusMillis(UNKNOWN_STATUS_DOT_WINDOW_MS);

        Integer actionId = null;
        Instant actionAppliedAt = null;
        if (actionApplication != null && !actionApplication.appliedAt().isBefore(cutoff)) {
            actionId = actionApplication.actionId();
            actionAppliedAt = actionApplication.appliedAt();
        }

        Integer statusMappedActionId = null;
        Instant statusAppliedAt = null;
        if (statusApplication != null && !statusApplication.appliedAt().isBefore(cutoff)) {
            int mappedActionId = toTrackedDotActionId(statusApplication.actionId());
            if (mappedActionId != 0) {
                statusMappedActionId = mappedActionId;
                statusAppliedAt = statusApplication.appliedAt();
            }
        }

        if (actionId != null && statusMappedActionId != null && actionId.equals(statusMappedActionId)) {
            return actionId;
        }
        if (actionAppliedAt == null) {
            return statusMappedActionId;
        }
        if (statusAppliedAt == null) {
            return actionId;
        }
        return statusAppliedAt.isAfter(actionAppliedAt) ? statusMappedActionId : actionId;
    }

    private Integer resolveRecentExactUnknownStatusActionId(DotTickRaw dot, long windowMs) {
        pruneExpiredUnknownStatusDotApplications(dot.ts());
        Integer corroboratedActionId = unknownStatusDotAttributionResolver.resolveCorroboratedActionId(
                dot,
                unknownStatusDotApplications,
                unknownStatusDotStatusApplications,
                windowMs,
                this::toTrackedDotActionId
        );
        if (corroboratedActionId != null) {
            return corroboratedActionId;
        }
        Integer trackedApplicationActionId = unknownStatusDotAttributionResolver.resolveTrackedApplicationActionId(
                dot,
                unknownStatusDotApplications,
                windowMs
        );
        if (trackedApplicationActionId != null) {
            return trackedApplicationActionId;
        }
        return unknownStatusDotAttributionResolver.resolveTrackedStatusActionId(
                dot,
                unknownStatusDotStatusApplications,
                windowMs,
                this::toTrackedDotActionId
        );
    }

    private Integer resolveSourceEvidenceFallbackActionId(
            DotTickRaw dot,
            Map<Long, SourceDotEvidence> evidenceBySource,
            java.util.function.IntUnaryOperator actionMapper
    ) {
        SourceDotEvidence evidence = evidenceBySource.get(dot.sourceId());
        if (evidence == null || evidence.appliedAt().isBefore(dot.ts().minusMillis(UNKNOWN_STATUS_DOT_WINDOW_MS))) {
            return null;
        }
        if (evidence.targetId() != dot.targetId()) {
            return null;
        }
        int mapped = actionMapper.applyAsInt(evidence.actionOrStatusId());
        return mapped != 0 ? mapped : evidence.actionOrStatusId();
    }

    private Optional<UnknownSourceDotAttribution> resolveUnknownSourceDotAttribution(DotTickRaw dot) {
        pruneExpiredUnknownStatusDotApplications(dot.ts());
        return unknownStatusDotAttributionResolver.resolveUnknownSourceAttribution(
                dot,
                unknownStatusDotApplications,
                unknownStatusDotStatusApplications,
                UNKNOWN_ACTOR_ID,
                UNKNOWN_STATUS_DOT_WINDOW_MS,
                this::isPartyMember,
                sourceId -> actorNameById.getOrDefault(sourceId, ""),
                this::toTrackedDotActionId
        ).map(value -> new UnknownSourceDotAttribution(
                value.sourceId(),
                value.actionId(),
                value.sourceName()
        ));
    }

    private Optional<UnknownSourceDotAttribution> resolveKnownStatusUnknownSourceAttribution(DotTickRaw dot) {
        pruneExpiredUnknownStatusDotApplications(dot.ts());
        Optional<UnknownSourceDotAttribution> resolved = unknownStatusDotAttributionResolver.resolveKnownStatusUnknownSourceAttribution(
                dot,
                unknownStatusDotApplications,
                unknownStatusDotStatusApplications,
                UNKNOWN_ACTOR_ID,
                UNKNOWN_STATUS_DOT_WINDOW_MS,
                this::isPartyMember,
                sourceId -> actorNameById.getOrDefault(sourceId, ""),
                this::toTrackedDotActionId
        ).map(value -> new UnknownSourceDotAttribution(
                value.sourceId(),
                value.actionId(),
                value.sourceName()
        ));
        if (resolved.isPresent()) {
            return resolved;
        }
        return resolveKnownStatusUnknownSourceByUniqueJob(dot);
    }

    private Optional<UnknownSourceDotAttribution> resolveKnownStatusUnknownSourceByUniqueJob(DotTickRaw dot) {
        if (!dot.hasKnownStatus()) {
            return Optional.empty();
        }
        if (dot.sourceId() != 0 && dot.sourceId() != UNKNOWN_ACTOR_ID) {
            return Optional.empty();
        }

        List<Long> candidates = partyMemberIds.stream()
                .filter(this::isPartyMember)
                .filter(actorId -> {
                    Integer jobId = jobIdByActorId.get(actorId);
                    if (jobId == null) {
                        return false;
                    }
                    Set<Integer> statusIds = DOT_ATTRIBUTION_RULES.statusIdsByJob().get(jobId);
                    return statusIds != null && statusIds.contains(dot.statusId());
                })
                .sorted()
                .toList();

        if (candidates.size() != 1) {
            return Optional.empty();
        }

        long sourceId = candidates.get(0);
        int actionId = toTrackedDotActionId(dot.statusId());
        if (actionId == 0) {
            actionId = dot.statusId();
        }

        return Optional.of(new UnknownSourceDotAttribution(
                sourceId,
                actionId,
                actorNameById.getOrDefault(sourceId, "")
        ));
    }

    private void emitDamage(NetworkAbilityRaw a) {
        if (!wouldEmitDamage(a)) {
            return;
        }

        ensureFightStarted(a.ts());
        lastDamageAt = a.ts();

        // 죽은 상태였다가 대미지를 주면 부활로 간주하고 deadPlayers에서 제거한다.
        if (deadPlayers.remove(a.actorId())) {
            logger.info("[Ingestion] Actor {} revived (detected via damage)", a.actorName());
        }

        long tsMs = Duration.between(fightStartInstant, a.ts()).toMillis();
        if (tsMs < 0) tsMs = 0;

        // 전투 중 처음 확인된 파티원이면 ActorJoined 이벤트를 먼저 전송한다.
        boolean isNewPartyMember = combatPartyMemberIds.add(a.actorId());
        if (isNewPartyMember && partyMemberIds.contains(a.actorId())) {
            combatEventPort.onEvent(new CombatEvent.ActorJoined(
                    tsMs, new ActorId(a.actorId()), a.actorName()));
            logger.info("[Ingestion] Party member joined: {}(id={}) | total party size={}",
                    a.actorName(), Long.toHexString(a.actorId()), effectivePartyMemberCountForCombat());
        }

        DamageType damageType = mapDamageTypeV1(a);
        DamageFlags damageFlags = matchDamageFlags(a);
        CombatEvent.HitOutcomeContext hitOutcomeContext = classifyHitOutcome(a, damageFlags);
        noteLiveDotApplicationCloneCandidate(a, damageFlags, hitOutcomeContext);

        emittedDamageCount++;
        logger.info("[Ingestion] DamageEvent #{}: actor={} skill={} amount={} tsMs={} crit={} direct={} autoCrit={} autoDirect={}",
                emittedDamageCount, a.actorName(), a.skillName(), a.damage(), tsMs,
                damageFlags.criticalHit(), damageFlags.directHit(),
                hitOutcomeContext.autoCrit(), hitOutcomeContext.autoDirectHit());
        combatEventPort.onEvent(new CombatEvent.DamageEvent(
                tsMs,
                new ActorId(a.actorId()),
                a.actorName(),
                new ActorId(a.targetId()),
                a.skillId(),
                a.skillName(),
                a.damage(),
                damageType,
                damageFlags.criticalHit(),
                damageFlags.directHit(),
                hitOutcomeContext
        ));
    }

    private void noteLiveDotApplicationCloneCandidate(
            NetworkAbilityRaw ability,
            DamageFlags damageFlags,
            CombatEvent.HitOutcomeContext hitOutcomeContext
    ) {
        if (!LIVE_DOT_TICK_SUPPRESSED_ACTION_IDS.contains(ability.skillId())) {
            return;
        }
        recentDotApplicationCloneCandidates.put(
                new LiveDotApplicationCloneKey(ability.actorId(), ability.targetId(), ability.skillId()),
                new RecentDamageCloneCandidate(
                        ability.ts(),
                        ability.skillName(),
                        ability.damage(),
                        damageFlags.criticalHit(),
                        damageFlags.directHit(),
                        hitOutcomeContext
                )
        );
        pruneRecentDotApplicationCloneCandidates(ability.ts());
    }

    private void pruneRecentDotApplicationCloneCandidates(Instant now) {
        Instant cutoff = now.minus(LIVE_DOT_APPLICATION_CLONE_WINDOW);
        recentDotApplicationCloneCandidates.entrySet().removeIf(entry -> entry.getValue().ts().isBefore(cutoff));
    }

    private CombatEvent.HitOutcomeContext classifyHitOutcome(NetworkAbilityRaw ability, DamageFlags damageFlags) {
        Integer jobId = jobIdByActorId.get(ability.actorId());
        if (jobId == null || jobId <= 0) {
            return CombatEvent.HitOutcomeContext.UNKNOWN;
        }

        Optional<CombatEvent.HitOutcomeContext> resolved = AutoHitCatalog.resolve(
                jobId,
                ability.skillId(),
                ability.skillName(),
                ActionTagCatalog.tagsFor(ability.skillId()),
                activeSelfBuffIdsByActor.getOrDefault(ability.actorId(), Set.of()),
                activeSelfBuffNamesByActor.getOrDefault(ability.actorId(), Set.of())
        );
        if (resolved.isEmpty()) {
            return CombatEvent.HitOutcomeContext.UNKNOWN;
        }

        CombatEvent.HitOutcomeContext context = resolved.orElseThrow();
        return new CombatEvent.HitOutcomeContext(
                damageFlags.criticalHit() ? context.autoCrit() : CombatEvent.AutoHitFlag.NO,
                damageFlags.directHit() ? context.autoDirectHit() : CombatEvent.AutoHitFlag.NO
        );
    }

    private boolean queueSelfAbilityUntilJobMetadata(NetworkAbilityRaw ability) {
        if (fightStarted || ability.actorId() != currentPlayerId || currentPlayerJobId > 0 || ability.damage() <= 0) {
            return false;
        }
        if (pendingSelfJobAbilities.isEmpty()) {
            pendingSelfJobAbilities.addLast(ability);
            logger.info("[Ingestion] delaying self ability until job metadata arrives: skill={}({})",
                    ability.skillName(), Integer.toHexString(ability.skillId()));
            return true;
        }

        NetworkAbilityRaw firstPending = pendingSelfJobAbilities.peekFirst();
        if (firstPending != null) {
            long waitMs = Math.abs(Duration.between(firstPending.ts(), ability.ts()).toMillis());
            if (waitMs <= SELF_JOB_METADATA_GRACE.toMillis()) {
                pendingSelfJobAbilities.addLast(ability);
                logger.info("[Ingestion] buffering self ability while waiting for job metadata: skill={}({}) buffered={}",
                        ability.skillName(), Integer.toHexString(ability.skillId()), pendingSelfJobAbilities.size());
                return true;
            }
        }

        logger.info("[Ingestion] job metadata grace expired, flushing {} buffered self abilities without job metadata",
                pendingSelfJobAbilities.size());
        flushPendingSelfJobAbilities();
        return false;
    }

    private void flushPendingSelfJobAbilities() {
        while (!pendingSelfJobAbilities.isEmpty()) {
            emitDamage(pendingSelfJobAbilities.removeFirst());
        }
    }

    private int loadPersistedCurrentPlayerJob(long playerId, String playerName) {
        int jobId = preferences.getInt("player-id:" + Long.toHexString(playerId), 0);
        if (jobId > 0) {
            return jobId;
        }
        if (playerName == null || playerName.isBlank()) {
            return 0;
        }
        return preferences.getInt("player-name:" + playerName, 0);
    }

    private void persistCurrentPlayerJob(long playerId, String playerName, int jobId) {
        if (jobId <= 0) {
            return;
        }
        if (playerId != 0) {
            preferences.putInt("player-id:" + Long.toHexString(playerId), jobId);
        }
        if (playerName != null && !playerName.isBlank()) {
            preferences.putInt("player-name:" + playerName, jobId);
        }
    }

    private void persistKnownActorJob(long actorId, String actorName, int jobId) {
        if (jobId <= 0) {
            return;
        }
        actorJobPreferences.putInt("actor-id:" + Long.toHexString(actorId), jobId);
        if (actorName != null && !actorName.isBlank()) {
            actorJobPreferences.putInt("actor-name:" + actorName, jobId);
        }
    }

    private void restoreActorJobFromCache(long actorId, String actorName) {
        if (jobIdByActorId.containsKey(actorId)) {
            return;
        }
        int cachedJobId = actorJobPreferences.getInt("actor-id:" + Long.toHexString(actorId), 0);
        if (cachedJobId <= 0 && actorName != null && !actorName.isBlank()) {
            cachedJobId = actorJobPreferences.getInt("actor-name:" + actorName, 0);
        }
        if (cachedJobId <= 0) {
            return;
        }
        jobIdByActorId.put(actorId, cachedJobId);
        combatService.setJobId(new ActorId(actorId), cachedJobId);
        if (actorId == currentPlayerId && currentPlayerJobId <= 0) {
            currentPlayerJobId = cachedJobId;
            persistCurrentPlayerJob(actorId, actorName, cachedJobId);
        }
        logger.info("[Ingestion] restored cached actor job: actor={}({}) jobId={} ({})",
                actorName,
                Long.toHexString(actorId),
                Integer.toHexString(cachedJobId),
                FfxivJobMapper.toKoreanName(cachedJobId));
    }

    private DamageFlags matchDamageFlags(NetworkAbilityRaw ability) {
        if (ability.criticalHit() || ability.directHit()) {
            return new DamageFlags(ability.criticalHit(), ability.directHit());
        }

        pruneOldDamageTexts(ability.ts());

        DamageText bestMatch = null;
        int bestScore = Integer.MAX_VALUE;
        long bestDeltaMs = Long.MAX_VALUE;

        for (DamageText text : pendingDamageTexts) {
            long deltaMs = Math.abs(Duration.between(text.ts(), ability.ts()).toMillis());
            if (deltaMs > 2_000) {
                continue;
            }
            if (text.amount() != ability.damage()) {
                continue;
            }
            if (text.targetTextName() != null && !text.targetTextName().isBlank()
                    && !text.targetTextName().equals(ability.targetName())) {
                continue;
            }

            int score = sourceMatchScore(text.sourceTextName(), ability.actorName());
            if (score < bestScore || (score == bestScore && deltaMs < bestDeltaMs)) {
                bestMatch = text;
                bestScore = score;
                bestDeltaMs = deltaMs;
            }
        }

        if (bestMatch != null) {
            pendingDamageTexts.remove(bestMatch);
            return new DamageFlags(bestMatch.criticalLike(), bestMatch.directHitLike());
        }

        return DamageFlags.NONE;
    }

    private int sourceMatchScore(String textSourceName, String actorName) {
        if (textSourceName == null || textSourceName.isBlank()) {
            return 1;
        }
        if (textSourceName.equals(actorName)) {
            return 0;
        }
        return 2;
    }

    private void pruneOldDamageTexts(Instant now) {
        while (!pendingDamageTexts.isEmpty()) {
            DamageText first = pendingDamageTexts.peekFirst();
            if (first == null) {
                return;
            }
            long ageMs = Math.abs(Duration.between(first.ts(), now).toMillis());
            if (ageMs <= 3_000) {
                return;
            }
            pendingDamageTexts.removeFirst();
        }
    }

    private DamageType mapDamageTypeV1(NetworkAbilityRaw a) {
        // v1 규칙:
        // - 기본은 DIRECT
        // - ownerId == currentPlayerId 이면 PET
        Long owner = ownerByCombatantId.get(a.actorId());
        if (owner != null && owner != 0 && owner == currentPlayerId) return DamageType.PET;
        return DamageType.DIRECT;
    }

    private void emitDotDamage(DotTickRaw dot) {
        if (!wouldEmitDotDamage(dot)) {
            return;
        }

        Optional<UnknownSourceDotAttribution> unknownSourceAttribution = resolveUnknownSourceDotAttribution(dot);
        Optional<UnknownSourceDotAttribution> knownStatusUnknownSourceAttribution =
                resolveKnownStatusUnknownSourceAttribution(dot);
        boolean unknownStatusDot = dot.statusId() == 0;
        boolean acceptedBySource = (shouldAcceptDot(dot) && isPartyMember(dot.sourceId()))
                || unknownSourceAttribution.isPresent()
                || knownStatusUnknownSourceAttribution.isPresent();

        Optional<UnknownSourceDotAttribution> resolvedSourceAttribution =
                knownStatusUnknownSourceAttribution.or(() -> unknownSourceAttribution);

        ensureFightStarted(dot.ts());
        lastDamageAt = dot.ts();

        long resolvedSourceId = resolvedSourceAttribution
                .map(UnknownSourceDotAttribution::sourceId)
                .orElse(dot.sourceId());
        String resolvedSourceName = resolvedSourceAttribution
                .map(UnknownSourceDotAttribution::sourceName)
                .orElse(dot.sourceName());

        if (deadPlayers.remove(resolvedSourceId)) {
            logger.info("[Ingestion] Actor {} revived (detected via DoT)", dot.sourceName());
        }

        long tsMs = Duration.between(fightStartInstant, dot.ts()).toMillis();
        if (tsMs < 0) tsMs = 0;

        boolean isNewPartyMember = combatPartyMemberIds.add(resolvedSourceId);
        if (isNewPartyMember && partyMemberIds.contains(resolvedSourceId)) {
            combatEventPort.onEvent(new CombatEvent.ActorJoined(
                    tsMs, new ActorId(resolvedSourceId), resolvedSourceName));
            logger.info("[Ingestion] Party member joined via DoT: {}(id={}) | total party size={}",
                    resolvedSourceName, Long.toHexString(resolvedSourceId), effectivePartyMemberCountForCombat());
        }

        if (unknownStatusDot) {
            Integer freshCorroboratedKnownSourceActionId =
                    resolveCorroboratedTrackedUnknownStatusDotActionId(dot, CORROBORATED_KNOWN_SOURCE_WINDOW_MS);
            boolean suppressKnownSourceGuidMissingFallback =
                    shouldSuppressKnownSourceGuidMissingMultiTargetFallback(dot);
            if (suppressKnownSourceGuidMissingFallback && freshCorroboratedKnownSourceActionId != null) {
                emitDotDamageWithAttribution(
                        "status0_corroborated_known_source",
                        dot.ts(),
                        tsMs,
                        new ActorId(resolvedSourceId),
                        resolvedSourceName,
                        new ActorId(dot.targetId()),
                        freshCorroboratedKnownSourceActionId,
                        dot.damage()
                );
                return;
            }
            List<SnapshotRedistributedDot> redistributedDots = resolveSnapshotRedistribution(dot);
            if (!redistributedDots.isEmpty() && !suppressKnownSourceGuidMissingFallback) {
                long remaining = dot.damage();
                for (int i = 0; i < redistributedDots.size(); i++) {
                    SnapshotRedistributedDot redistributedDot = redistributedDots.get(i);
                    long allocated = i == redistributedDots.size() - 1
                            ? remaining
                            : Math.min(remaining, redistributedDot.amount());
                    remaining -= allocated;
                    emitDotDamageWithAttribution(
                            "status0_snapshot_redistribution",
                            dot.ts(),
                            tsMs,
                            new ActorId(redistributedDot.sourceId()),
                            actorNameById.getOrDefault(redistributedDot.sourceId(), dot.sourceName()),
                            new ActorId(dot.targetId()),
                            redistributedDot.actionId(),
                            allocated
                    );
                }
                return;
            }

            if (!acceptedBySource) {
                List<TrackedDotState> sourceTrackedDots = resolveTrackedSourceDots(dot);
                if (!sourceTrackedDots.isEmpty()) {
                    long remaining = dot.damage();
                    for (int i = 0; i < sourceTrackedDots.size(); i++) {
                        TrackedDotState trackedDot = sourceTrackedDots.get(i);
                        long allocated = remaining / (sourceTrackedDots.size() - i);
                        remaining -= allocated;
                        emitDotDamageWithAttribution(
                                "status0_tracked_source_target_split",
                                dot.ts(),
                                tsMs,
                                new ActorId(trackedDot.sourceId()),
                                trackedDot.sourceName(),
                                new ActorId(dot.targetId()),
                                trackedDot.actionId(),
                                allocated
                        );
                    }
                    return;
                }
            }

            List<TrackedDotState> trackedDots = resolveTrackedTargetDots(dot);
            if (!trackedDots.isEmpty()
                    && !suppressKnownSourceGuidMissingFallback
                    && !shouldSuppressKnownSourceMismatchedTrackedTargetSplit(dot, trackedDots)
                    && !(dot.sourceId() == UNKNOWN_ACTOR_ID && countTrackedTargetsWithActiveDots() > 1)) {
                long remaining = dot.damage();
                for (int i = 0; i < trackedDots.size(); i++) {
                    TrackedDotState trackedDot = trackedDots.get(i);
                    long allocated = remaining / (trackedDots.size() - i);
                    remaining -= allocated;
                    emitDotDamageWithAttribution(
                            "status0_tracked_target_split",
                            dot.ts(),
                            tsMs,
                            new ActorId(trackedDot.sourceId()),
                            trackedDot.sourceName(),
                            new ActorId(dot.targetId()),
                            trackedDot.actionId(),
                            allocated
                    );
                }
                return;
            }
        }

        if (acceptedBySource) {
            if (unknownStatusDot
                    && shouldSuppressKnownSourceGuidMissingMultiTargetFallback(dot)
                    && resolveCorroboratedTrackedUnknownStatusDotActionId(dot, CORROBORATED_KNOWN_SOURCE_WINDOW_MS) == null) {
                return;
            }
            if (unknownStatusDot
                    && shouldRequireRecentExactEvidenceForKnownSourceAcceptedBySource(dot)
                    && resolveRecentExactUnknownStatusActionId(dot, KNOWN_SOURCE_MULTI_TARGET_EXACT_WINDOW_MS) == null) {
                return;
            }
            String attributionMode = unknownStatusDot
                    ? "status0_accepted_by_source"
                    : "known_status_accepted_by_source";
            int actionId = resolvedSourceAttribution
                    .map(UnknownSourceDotAttribution::actionId)
                    .orElseGet(() -> resolveDotActionId(dot));
            emitDotDamageWithAttribution(
                    attributionMode,
                    dot.ts(),
                    tsMs,
                    new ActorId(resolvedSourceId),
                    resolvedSourceName,
                    new ActorId(dot.targetId()),
                    actionId,
                    dot.damage()
            );
            return;
        }

        List<TrackedDotState> trackedDots = resolveTrackedTargetDots(dot);
        if (!trackedDots.isEmpty()
                && !(unknownStatusDot && shouldSuppressKnownSourceMismatchedTrackedTargetSplit(dot, trackedDots))) {
            long remaining = dot.damage();
            for (int i = 0; i < trackedDots.size(); i++) {
                TrackedDotState trackedDot = trackedDots.get(i);
                long allocated = remaining / (trackedDots.size() - i);
                remaining -= allocated;
                emitDotDamageWithAttribution(
                        "status0_fallback_tracked_target_split",
                        dot.ts(),
                        tsMs,
                        new ActorId(trackedDot.sourceId()),
                        trackedDot.sourceName(),
                        new ActorId(dot.targetId()),
                        trackedDot.actionId(),
                        allocated
                );
            }
        }
    }

    private void recordDotAttributionMode(String mode) {
        dotAttributionModeCounts.merge(mode, 1L, Long::sum);
    }

    private void emitDotDamageWithAttribution(
            String attributionMode,
            Instant attributionTs,
            long tsMs,
            ActorId sourceId,
            String sourceName,
            ActorId targetId,
            int actionId,
            long amount
    ) {
        recordDotAttributionMode(attributionMode);
        if (amount <= 0) {
            return;
        }
        String key = buildDotAttributionAssignmentKey(attributionMode, sourceId.value(), targetId.value(), actionId);
        dotAttributionAssignedAmountByKey.merge(key, amount, Long::sum);
        dotAttributionAssignedHitCountByKey.merge(key, 1L, Long::sum);
        recentDotAttributionAssignments.addLast(new DotAttributionAssignment(
                attributionTs,
                attributionMode,
                sourceId.value(),
                sourceName,
                actionId,
                amount
        ));
        pruneRecentDotAttributionAssignments(attributionTs);
        emitValidatedDotDamageEvent(tsMs, sourceId, sourceName, targetId, actionId, amount);
    }

    private void pruneRecentDotAttributionAssignments(Instant now) {
        Instant cutoff = now.minus(LIVE_DOT_ATTRIBUTION_DEBUG_RETENTION);
        while (!recentDotAttributionAssignments.isEmpty()
                && recentDotAttributionAssignments.peekFirst().ts().isBefore(cutoff)) {
            recentDotAttributionAssignments.removeFirst();
        }
    }

    private String buildDotAttributionAssignmentKey(String mode, long sourceId, long targetId, int actionId) {
        return mode
                + "|source=" + Long.toHexString(sourceId).toUpperCase()
                + "|target=" + Long.toHexString(targetId).toUpperCase()
                + "|action=" + Integer.toHexString(actionId).toUpperCase();
    }

    private void emitValidatedDotDamageEvent(
            long tsMs,
            ActorId sourceId,
            String sourceName,
            ActorId targetId,
            int actionId,
            long amount
    ) {
        if (amount <= 0) {
            return;
        }
        if (LIVE_DOT_TICK_SUPPRESSED_ACTION_IDS.contains(actionId)) {
            return;
        }
        if (INVALID_DOT_ACTION_IDS.contains(actionId)) {
            logger.warn(
                    "[Ingestion] dropped invalid DoT action id={} source={}({}) target={} amount={}",
                    Integer.toHexString(actionId).toUpperCase(),
                    sourceName,
                    Long.toHexString(sourceId.value()).toUpperCase(),
                    Long.toHexString(targetId.value()).toUpperCase(),
                    amount
            );
            return;
        }
        combatEventPort.onEvent(new CombatEvent.DamageEvent(
                tsMs,
                sourceId,
                sourceName,
                targetId,
                actionId,
                ActionNameLibrary.resolveDisplay(actionId),
                amount,
                DamageType.DOT,
                false,
                false
        ));
    }

    private int toTrackedDotActionId(int statusId) {
        Integer actionId = DOT_ATTRIBUTION_RULES.statusToAction().get(statusId);
        return actionId != null ? actionId : 0;
    }

    private void ensureFightStarted(Instant firstEventTs) {
        if (fightStarted) return;
        fightStarted = true;
        fightStartInstant = firstEventTs;
        combatPartyMemberIds.clear();
        combatPartyMemberIds.addAll(partyMemberIds);
        deadPlayers.clear();  // 전투 시작 시 사망자 목록 초기화
        announcedBossId = null;
        logger.info("[Ingestion] fight started at {} zone={} zoneId={} playerJobId={} partySize={}",
                firstEventTs, currentZoneName, currentZoneId,
                Integer.toHexString(currentPlayerJobId), effectivePartyMemberCountForCombat());
        combatEventPort.onEvent(new CombatEvent.FightStart(0L, currentZoneName, currentZoneId, currentPlayerJobId));
        emitPendingBuffs();
        emitPendingBossIfPresent();
    }

    private void emitPendingBuffs() {
        while (!pendingBuffEvents.isEmpty()) {
            PendingBuffEvent pending = pendingBuffEvents.removeFirst();
            if (pending.apply() != null) {
                emitBuffApply(pending.apply());
                continue;
            }
            if (pending.remove() != null) {
                emitBuffRemove(pending.remove());
            }
        }
    }

    private void emitBuffApply(BuffApplyRaw b) {
        trackActiveSelfBuff(b);
        long tsMs = toElapsedMs(b.ts());
        emitLiveDotApplicationCloneIfPresent(b, tsMs);
        combatEventPort.onEvent(new CombatEvent.BuffApply(
                tsMs,
                new ActorId(b.sourceId()),
                new ActorId(b.targetId()),
                new BuffId(b.statusId()),
                b.statusName(),
                (long) (b.durationSec() * 1000)
        ));
    }

    private void emitLiveDotApplicationCloneIfPresent(BuffApplyRaw buffApply, long tsMs) {
        Integer actionId = LIVE_DOT_APPLICATION_CLONE_STATUS_TO_ACTION.get(buffApply.statusId());
        if (actionId == null) {
            return;
        }
        LiveDotApplicationCloneKey key = new LiveDotApplicationCloneKey(buffApply.sourceId(), buffApply.targetId(), actionId);
        RecentDamageCloneCandidate candidate = recentDotApplicationCloneCandidates.get(key);
        if (candidate == null) {
            return;
        }
        if (candidate.ts().isBefore(buffApply.ts().minus(LIVE_DOT_APPLICATION_CLONE_WINDOW))) {
            recentDotApplicationCloneCandidates.remove(key);
            return;
        }
        recentDotApplicationCloneCandidates.remove(key);
        emittedDamageCount++;
        combatEventPort.onEvent(new CombatEvent.DamageEvent(
                tsMs,
                new ActorId(buffApply.sourceId()),
                buffApply.sourceName(),
                new ActorId(buffApply.targetId()),
                actionId,
                candidate.actionName(),
                candidate.amount(),
                DamageType.DIRECT,
                candidate.criticalHit(),
                candidate.directHit(),
                candidate.hitOutcomeContext()
        ));
    }

    private void emitBuffRemove(BuffRemoveRaw b) {
        untrackActiveSelfBuff(b);
        long tsMs = toElapsedMs(b.ts());
        combatEventPort.onEvent(new CombatEvent.BuffRemove(
                tsMs,
                new ActorId(b.sourceId()),
                new ActorId(b.targetId()),
                new BuffId(b.statusId()),
                b.statusName()
        ));
    }

    private void trackActiveSelfBuff(BuffApplyRaw buffApply) {
        if (buffApply.sourceId() != buffApply.targetId()) {
            return;
        }
        activeSelfBuffIdsByActor
                .computeIfAbsent(buffApply.targetId(), ignored -> new HashSet<>())
                .add(buffApply.statusId());
        if (buffApply.statusName() != null && !buffApply.statusName().isBlank()) {
            activeSelfBuffNamesByActor
                    .computeIfAbsent(buffApply.targetId(), ignored -> new HashSet<>())
                    .add(normalizeCombatText(buffApply.statusName()));
        }
    }

    private void untrackActiveSelfBuff(BuffRemoveRaw buffRemove) {
        if (buffRemove.sourceId() != buffRemove.targetId()) {
            return;
        }
        Set<Integer> activeIds = activeSelfBuffIdsByActor.get(buffRemove.targetId());
        if (activeIds != null) {
            activeIds.remove(buffRemove.statusId());
            if (activeIds.isEmpty()) {
                activeSelfBuffIdsByActor.remove(buffRemove.targetId());
            }
        }
        if (buffRemove.statusName() != null && !buffRemove.statusName().isBlank()) {
            Set<String> activeNames = activeSelfBuffNamesByActor.get(buffRemove.targetId());
            if (activeNames != null) {
                activeNames.remove(normalizeCombatText(buffRemove.statusName()));
                if (activeNames.isEmpty()) {
                    activeSelfBuffNamesByActor.remove(buffRemove.targetId());
                }
            }
        }
    }

    private String normalizeCombatText(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 유효한 전투 zone인지 확인한다.
     * 레이드/인던 zone이거나 대상이 허수아비형이면 true를 반환한다.
     */
    private boolean isValidCombatZone(NetworkAbilityRaw a) {
        return isValidCombatZone(a.targetName());
    }

    private boolean isValidCombatZone(String targetName) {
        // 1. FFLogs에서 관리하는 유효 전투 zone인지 확인
        if (fflogsZoneLookup.resolve(currentZoneId).isPresent()) {
            return true;
        }
        // 2. 대상이 허수아비형이면 전투 zone이 아니어도 허용 (훈련용 더미)
        if (targetName != null && (targetName.contains("\uD5C8\uC218\uC544\uBE44\uD615") || targetName.contains("Training Dummy"))) {
            return true;
        }

        return false;
    }

    /**
     * ACT CombatData 처리 완료 알림.
     * ActWsClient가 CombatData의 모든 Combatant 처리를 마친 뒤 호출한다.
     * memberCount > 0 이면 파티 정보 수신 완료로 간주한다.
     */
    public void onCombatDataReady(int memberCount) {
        if (memberCount > 0) {
            partyDataInitialized = true;
            logger.info("[Ingestion] party data ready from CombatData ({} members)", memberCount);
        } else {
            // 활성 전투가 아니거나 파티원이 없으면 아직 미초기화 상태로 유지한다.
            logger.info("[Ingestion] CombatData received but no members (not in active combat)");
        }
    }

    /**
     * caller가 파티 정보를 신뢰하는지 여부를 함께 받아 처리한다.
     * PartyList 기반 실제 파티 목록이 있으면 그 정보를 우선한다.
     * 파티 정보가 아직 없으면 현재 플레이어만 임시 파티원으로 간주한다.
     */
    public void onCombatDataReady(int memberCount, boolean trustPartyMembership) {
        if (trustPartyMembership) {
            onCombatDataReady(memberCount);
            return;
        }
        if (memberCount > 0) {
            logger.info("[Ingestion] CombatData metadata received ({} members, party trust disabled)", memberCount);
        } else {
            logger.info("[Ingestion] CombatData received but no members (not in active combat)");
        }
    }

    private boolean isPartyMember(NetworkAbilityRaw a) {
        return isPartyMember(a.actorId());
    }

    private boolean isPartyMember(long actorId) {
        Set<Long> effectivePartyMembers = effectivePartyMemberIds();

        // 직속 파티원인지 확인
        if (effectivePartyMembers.contains(actorId)) {
            return true;
        }

        // 소환수면 owner가 파티원인지 확인
        Long owner = ownerByCombatantId.get(actorId);
        if (owner != null && effectivePartyMembers.contains(owner)) {
            return true;
        }

        // 파티 정보 미수신 상태에서는 현재 플레이어만 파티원으로 본다.
        if (!partyDataInitialized && actorId == currentPlayerId) {
            return true;
        }

        if (!partyDataInitialized && owner != null && owner == currentPlayerId) {
            return true;
        }

        return false;
    }

    private boolean isFriendlyTarget(long targetId) {
        Set<Long> effectivePartyMembers = effectivePartyMemberIds();
        if (effectivePartyMembers.contains(targetId)) {
            return true;
        }
        Long owner = ownerByCombatantId.get(targetId);
        return owner != null && effectivePartyMembers.contains(owner);
    }

    private Set<Long> effectivePartyMemberIds() {
        if (fightStarted && !combatPartyMemberIds.isEmpty()) {
            return combatPartyMemberIds;
        }
        return partyMemberIds;
    }

    private int effectivePartyMemberCountForCombat() {
        if (fightStarted && !combatPartyMemberIds.isEmpty()) {
            return combatPartyMemberIds.size();
        }
        return partyMemberIds.size();
    }

    /**
     * FFXIV에서 PC(플레이어 캐릭터)의 actorId는 0x10000000 ~ 0x1FFFFFFF 범위다.
     * NPC/보스는 0x40000000+, 환경 오브젝트는 0xE0000000+ 대역을 사용한다.
     */
    private static boolean isPlayerCharacter(long actorId) {
        return actorId >= 0x10000000L && actorId < 0x20000000L;
    }

    private static boolean isNpc(long actorId) {
        return actorId >= 0x40000000L && actorId < 0x50000000L;
    }

    private void onCombatantAdded(CombatantAdded combatant) {
        if (!isBoss(combatant)) {
            return;
        }

        pendingBoss = new BossCandidate(combatant.id(), combatant.name(), combatant.maxHp());
        logger.info("[Ingestion] boss candidate detected: {}(id={}) maxHp={}",
                combatant.name(), Long.toHexString(combatant.id()), combatant.maxHp());

        if (fightStarted) {
            emitPendingBossIfPresent();
        }
    }

    private boolean isBoss(CombatantAdded combatant) {
        return isNpc(combatant.id())
                && combatant.ownerId() == 0
                && combatant.maxHp() >= BOSS_MIN_MAX_HP;
    }

    private void emitPendingBossIfPresent() {
        if (!fightStarted || pendingBoss == null) {
            return;
        }
        if (Objects.equals(announcedBossId, pendingBoss.actorId())) {
            return;
        }

        combatEventPort.onEvent(new CombatEvent.BossIdentified(
                nowElapsedMs(),
                new ActorId(pendingBoss.actorId()),
                pendingBoss.name(),
                pendingBoss.maxHp()
        ));
        announcedBossId = pendingBoss.actorId();
    }


    private long toElapsedMs(Instant eventTs) {
        if (fightStartInstant == null) return 0;
        long ms = Duration.between(fightStartInstant, eventTs).toMillis();
        return Math.max(0, ms);
    }

    private record BossCandidate(long actorId, String name, long maxHp) {}
    private record UnknownSourceDotAttribution(long sourceId, int actionId, String sourceName) {}
    private record SourceDotEvidence(int actionOrStatusId, Instant appliedAt, long targetId, String targetName) {}
    private record LiveDotApplicationCloneKey(long sourceId, long targetId, int actionId) {}
    private record TrackedDotKey(long sourceId, int actionId) {}
    private record TrackedDotState(long sourceId, String sourceName, int actionId, Instant expiresAt) {}
    private record StatusSignalEvidence(Instant ts, long sourceId, int actionId) {}
    private record StatusSnapshotState(Instant ts, Map<TrackedDotKey, Double> weights) {}
    private record SnapshotRedistributedDot(long sourceId, int actionId, long amount) {}
    private record DotAttributionAssignment(
            Instant ts,
            String mode,
            long sourceId,
            String sourceName,
            int actionId,
            long amount
    ) {}
    private record DotAttributionRollupKey(String mode, long sourceId, String sourceName, int actionId) {}
    private record RecentDamageCloneCandidate(
            Instant ts,
            String actionName,
            long amount,
            boolean criticalHit,
            boolean directHit,
            CombatEvent.HitOutcomeContext hitOutcomeContext
    ) {}
    private static final class DotAttributionRollup {
        private long totalAmount;
        private long hitCount;

        private void add(long amount) {
            totalAmount += amount;
            hitCount++;
        }
    }
    private record PendingBuffEvent(BuffApplyRaw apply, BuffRemoveRaw remove) {
        private static PendingBuffEvent apply(BuffApplyRaw apply) {
            return new PendingBuffEvent(apply, null);
        }

        private static PendingBuffEvent remove(BuffRemoveRaw remove) {
            return new PendingBuffEvent(null, remove);
        }
    }
    private record DamageFlags(boolean criticalHit, boolean directHit) {
        private static final DamageFlags NONE = new DamageFlags(false, false);
    }

}
