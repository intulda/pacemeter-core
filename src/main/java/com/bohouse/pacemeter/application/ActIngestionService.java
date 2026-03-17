package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.*;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FfxivJobMapper;
import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.BuffId;
import com.bohouse.pacemeter.core.model.DamageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
public final class ActIngestionService {
    private static final Logger logger = LoggerFactory.getLogger(ActIngestionService.class);
    private static final long BOSS_MIN_MAX_HP = 10_000_000L;
    private static final Set<Integer> UNKNOWN_STATUS_DOT_JOB_WHITELIST = Set.of(
            0x16, // Dragoon - Chaotic Spring
            0x18, // White Mage - Dia
            0x19, // Black Mage - Thunder
            0x1C, // Scholar - Biolysis
            0x1E, // Ninja - Dokumori
            0x21, // Astrologian - Combust
            0x22, // Samurai - Higanbana
            0x25, // Gunbreaker - Sonic Break
            0x28  // Sage - Eukrasian Dosis
    );

    private final CombatEventPort combatEventPort;
    private final CombatService combatService;
    private final FflogsZoneLookup fflogsZoneLookup;

    private volatile long currentPlayerId = 0;
    private volatile String currentPlayerName = "YOU";
    private volatile int currentPlayerJobId = 0;  // 본인 직업 ID
    private volatile String currentZoneName = "";
    private volatile int currentZoneId = 0;

    private final Map<Long, Long> ownerByCombatantId = new HashMap<>();
    private final Set<Long> partyMemberIds = new HashSet<>();  // 파티원 ID 추적
    private final Set<Long> deadPlayers = new HashSet<>();     // 사망한 파티원 ID
    private final Map<Long, Integer> jobIdByActorId = new HashMap<>();  // 캐릭터별 직업 ID
    private final Deque<DamageText> pendingDamageTexts = new ArrayDeque<>();
    private final Deque<PendingBuffEvent> pendingBuffEvents = new ArrayDeque<>();
    private BossCandidate pendingBoss;
    private Long announcedBossId;

    // ACT가 파티 정보를 명시적으로 전달했는지 여부
    // false = 아직 미수신 (레이트 스타트 대응: 모든 PC 허용)
    // true  = PartyList 또는 CombatData에서 파티원 목록 수신 완료
    private volatile boolean partyDataInitialized = false;

    private static final long COMBAT_TIMEOUT_MS = 30_000; // 30초 무활동 시 전투 종료

    private volatile boolean fightStarted = false;
    private volatile Instant fightStartInstant = null;
    private volatile Instant lastDamageAt = null;
    private volatile Instant lastEventInstant = null;  // 마지막 ACT 이벤트 타임스탬프 (wall clock 아님)

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

        // 마지막 데미지 이후 타임아웃이면 전투 자동 종료 (ACT 이벤트 타임스탬프 기준)
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

        // 상태 초기화
        fightStarted = false;
        fightStartInstant = null;
        lastDamageAt = null;
        lastEventInstant = null;
        deadPlayers.clear();
        ownerByCombatantId.clear();
        jobIdByActorId.clear();
        pendingDamageTexts.clear();
        pendingBuffEvents.clear();
        combatService.clearCombatantContext();
        pendingBoss = null;
        announcedBossId = null;
        // currentPlayerId, currentPlayerName은 유지 (다음 전투에서도 사용)
    }

    /** TickDriver에서 쓸 수 있게 "지금 전투 기준 경과 ms" 제공.
     *  wall-clock 대신 마지막 ACT 이벤트 타임스탬프 사용 → 딜레이 영향 제거. */
    public long nowElapsedMs() {
        if (!fightStarted || fightStartInstant == null) return 0;
        Instant ref = lastEventInstant != null ? lastEventInstant : fightStartInstant;
        long ms = Duration.between(fightStartInstant, ref).toMillis();
        return Math.max(0, ms);
    }

    public void onParsed(ParsedLine line) {
        if (line == null) return;

        // 모든 이벤트의 ACT 타임스탬프를 추적 (Tick 경과시간 계산용)
        if (line.ts() != null) {
            lastEventInstant = line.ts();
        }

        if (line instanceof ZoneChanged z) {
            // Zone 변경 시 전투 중이면 자동 종료
            if (fightStarted) {
                logger.info("[Ingestion] Zone changed during combat ({}→{}), ending fight",
                        currentZoneName, z.zoneName());
                endFight();
            }

            this.currentZoneId = z.zoneId();
            this.currentZoneName = z.zoneName();
            // 존 변경 시 파티 정보 초기화 → 다음 CombatData/PartyList에서 재수신
            partyDataInitialized = false;
            partyMemberIds.clear();
            ownerByCombatantId.clear();
            jobIdByActorId.clear();
            pendingDamageTexts.clear();
            pendingBuffEvents.clear();
            combatService.clearCombatantContext();
            pendingBoss = null;
            announcedBossId = null;
            logger.info("[Ingestion] ZoneChanged: id={} name={}", z.zoneId(), z.zoneName());
            return;
        }

        if (line instanceof PrimaryPlayerChanged p) {
            this.currentPlayerId = p.playerId();
            this.currentPlayerName = p.playerName();
            // jobId는 CombatantAdded에서 설정됨

            // 엔진에 현재 플레이어 ID 전달 (개인 페이스 비교용)
            combatEventPort.setCurrentPlayerId(new ActorId(p.playerId()));
            logger.info("[Ingestion] current player set: id={} name={}", Long.toHexString(p.playerId()), p.playerName());
            return;
        }

        if (line instanceof PartyList party) {
            // ACT PartyList로부터 실제 파티원 목록 업데이트
            partyMemberIds.clear();
            partyMemberIds.addAll(party.partyMemberIds());
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

            // 펫/소환수 소유자 추적
            if (c.ownerId() != 0) {
                ownerByCombatantId.put(c.id(), c.ownerId());
                // 엔진에도 owner 정보 전달
                combatService.setOwner(new ActorId(c.id()), new ActorId(c.ownerId()));
            }

            // 파티원 정보 저장 (PC만, 실제 파티원 여부는 데미지 발생 시 확인)
            if (isPlayerCharacter(c.id())) {
                jobIdByActorId.put(c.id(), c.jobId());  // 직업 저장
                combatService.setJobId(new ActorId(c.id()), c.jobId());  // 엔진에 직업 정보 전달
                partyMemberIds.add(c.id());  // CombatData 복원 시에도 파티원으로 등록

                // 본인이면 currentPlayerJobId 저장
                if (c.id() == currentPlayerId || c.name().equals(currentPlayerName)) {
                    currentPlayerId = c.id();
                    currentPlayerJobId = c.jobId();
                    combatEventPort.setCurrentPlayerId(new ActorId(c.id()));
                    logger.info("[Ingestion] CURRENT PLAYER detected: {}(id={}) jobId={} ({})",
                            c.name(), Long.toHexString(c.id()),
                            Integer.toHexString(c.jobId()),
                            FfxivJobMapper.toKoreanName(c.jobId()));
                }

            }

            onCombatantAdded(c);
            return;
        }

        if (line instanceof NetworkAbilityRaw a) {
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
            if (a.damage() > 0) {
                emitDamage(a);
            }
            return;
        }

        if (line instanceof DotTickRaw d) {
            if (d.damage() > 0 && d.isDot()) {
                emitDotDamage(d);
            }
            return;
        }

        if (line instanceof DamageText d) {
            pendingDamageTexts.addLast(d);
            pruneOldDamageTexts(d.ts());
            return;
        }

        if (line instanceof BuffApplyRaw b) {
            if (!fightStarted) {
                pendingBuffEvents.addLast(PendingBuffEvent.apply(b));
                return;
            }
            emitBuffApply(b);
            return;
        }

        if (line instanceof BuffRemoveRaw b) {
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

            // 파티원 사망 추적 (단, 이미 사망 처리된 경우는 무시 — 부활 후 재사망은 정상 처리)
            if (partyMemberIds.contains(d.targetId())) {
                deadPlayers.add(d.targetId());
                logger.info("[Ingestion] Party member died: {}(id={}) | dead={}/{} party members",
                        d.targetName(), Long.toHexString(d.targetId()),
                        deadPlayers.size(), partyMemberIds.size());

                // 엔진에 사망 이벤트 전달
                combatEventPort.onEvent(new CombatEvent.ActorDeath(
                        tsMs,
                        new ActorId(d.targetId()),
                        d.targetName()
                ));

                // 전멸 체크
                if (deadPlayers.size() == partyMemberIds.size() && partyMemberIds.size() > 0) {
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
        if (!shouldAcceptDot(dot)) {
            return false;
        }
        if (!isPartyMember(dot.sourceId())) {
            return false;
        }
        if (isFriendlyTarget(dot.targetId())) {
            return false;
        }
        return fightStarted || isValidCombatZone(dot.targetName());
    }

    private boolean shouldAcceptDot(DotTickRaw dot) {
        if (dot.hasKnownStatus()) {
            return true;
        }
        Integer jobId = jobIdByActorId.get(dot.sourceId());
        return jobId != null && UNKNOWN_STATUS_DOT_JOB_WHITELIST.contains(jobId);
    }

    private void emitDamage(NetworkAbilityRaw a) {
        if (!wouldEmitDamage(a)) {
            return;
        }

        ensureFightStarted(a.ts());
        lastDamageAt = a.ts();

        // 사망했다가 데미지를 주면 부활로 간주하여 deadPlayers에서 제거
        if (deadPlayers.remove(a.actorId())) {
            logger.info("[Ingestion] Actor {} revived (detected via damage)", a.actorName());
        }

        long tsMs = Duration.between(fightStartInstant, a.ts()).toMillis();
        if (tsMs < 0) tsMs = 0;

        // 처음 보는 파티원이면 ActorJoined 이벤트 먼저 전송
        boolean isNewPartyMember = partyMemberIds.add(a.actorId());
        if (isNewPartyMember) {
            combatEventPort.onEvent(new CombatEvent.ActorJoined(
                    tsMs, new ActorId(a.actorId()), a.actorName()));
            logger.info("[Ingestion] Party member joined: {}(id={}) | total party size={}",
                    a.actorName(), Long.toHexString(a.actorId()), partyMemberIds.size());
        }

        DamageType damageType = mapDamageTypeV1(a);
        DamageFlags damageFlags = matchDamageFlags(a);

        emittedDamageCount++;
        logger.info("[Ingestion] DamageEvent #{}: actor={} skill={} amount={} tsMs={} crit={} direct={}",
                emittedDamageCount, a.actorName(), a.skillName(), a.damage(), tsMs,
                damageFlags.criticalHit(), damageFlags.directHit());
        combatEventPort.onEvent(new CombatEvent.DamageEvent(
                tsMs,
                new ActorId(a.actorId()),
                a.actorName(),
                new ActorId(a.targetId()),
                a.skillId(),
                a.damage(),
                damageType,
                damageFlags.criticalHit(),
                damageFlags.directHit()
        ));
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
        // - 기본: DIRECT
        // - (ownerId==currentPlayerId) 이면 PET
        Long owner = ownerByCombatantId.get(a.actorId());
        if (owner != null && owner != 0 && owner == currentPlayerId) return DamageType.PET;
        return DamageType.DIRECT;
    }

    private void emitDotDamage(DotTickRaw dot) {
        if (!wouldEmitDotDamage(dot)) {
            return;
        }

        ensureFightStarted(dot.ts());
        lastDamageAt = dot.ts();

        if (deadPlayers.remove(dot.sourceId())) {
            logger.info("[Ingestion] Actor {} revived (detected via DoT)", dot.sourceName());
        }

        long tsMs = Duration.between(fightStartInstant, dot.ts()).toMillis();
        if (tsMs < 0) tsMs = 0;

        boolean isNewPartyMember = partyMemberIds.add(dot.sourceId());
        if (isNewPartyMember) {
            combatEventPort.onEvent(new CombatEvent.ActorJoined(
                    tsMs, new ActorId(dot.sourceId()), dot.sourceName()));
            logger.info("[Ingestion] Party member joined via DoT: {}(id={}) | total party size={}",
                    dot.sourceName(), Long.toHexString(dot.sourceId()), partyMemberIds.size());
        }

        combatEventPort.onEvent(new CombatEvent.DamageEvent(
                tsMs,
                new ActorId(dot.sourceId()),
                dot.sourceName(),
                new ActorId(dot.targetId()),
                dot.statusId(),
                dot.damage(),
                DamageType.DOT,
                false,
                false
        ));
    }

    private void ensureFightStarted(Instant firstEventTs) {
        if (fightStarted) return;
        fightStarted = true;
        fightStartInstant = firstEventTs;
        deadPlayers.clear();  // 새 전투 시작 시 사망자 목록 초기화
        announcedBossId = null;
        logger.info("[Ingestion] fight started at {} zone={} zoneId={} playerJobId={} partySize={}",
                firstEventTs, currentZoneName, currentZoneId,
                Integer.toHexString(currentPlayerJobId), partyMemberIds.size());
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
        long tsMs = toElapsedMs(b.ts());
        combatEventPort.onEvent(new CombatEvent.BuffApply(
                tsMs,
                new ActorId(b.sourceId()),
                new ActorId(b.targetId()),
                new BuffId(b.statusId()),
                b.statusName(),
                (long) (b.durationSec() * 1000)
        ));
    }

    private void emitBuffRemove(BuffRemoveRaw b) {
        long tsMs = toElapsedMs(b.ts());
        combatEventPort.onEvent(new CombatEvent.BuffRemove(
                tsMs,
                new ActorId(b.sourceId()),
                new ActorId(b.targetId()),
                new BuffId(b.statusId()),
                b.statusName()
        ));
    }

    /**
     * 유효한 전투 zone인지 확인.
     * 던전/레이드 zone이거나, 타겟이 나무인형(훈련용 더미)이면 true.
     */
    private boolean isValidCombatZone(NetworkAbilityRaw a) {
        return isValidCombatZone(a.targetName());
    }

    private boolean isValidCombatZone(String targetName) {
        // 1. 던전/레이드 zone인지 확인 (FflogsZoneLookup에 등록된 zone)
        if (fflogsZoneLookup.resolve(currentZoneId).isPresent()) {
            return true;
        }

        // 2. 타겟이 나무인형이면 허용 (훈련용 더미)
        if (targetName != null && targetName.contains("나무인형")) {
            return true;
        }

        return false;
    }

    /**
     * ACT CombatData 처리 완료 알림.
     * ActWsClient가 CombatData의 모든 Combatant 처리 후 호출한다.
     * memberCount > 0이면 파티 정보 수신 완료로 간주한다.
     */
    public void onCombatDataReady(int memberCount) {
        if (memberCount > 0) {
            partyDataInitialized = true;
            logger.info("[Ingestion] party data ready from CombatData ({} members)", memberCount);
        } else {
            // isActive=false였거나 파티원이 없음 → 아직 미초기화 상태 유지 (PC 전체 허용 계속)
            logger.info("[Ingestion] CombatData received but no members (not in active combat)");
        }
    }

    /**
     * 액터가 파티원(또는 파티원의 펫)인지 확인.
     * PartyList 로그로부터 받은 실제 파티원 목록 기반.
     * partyDataInitialized=false이면 PC 전체 허용 (레이트 스타트 / 파티 정보 미수신 대응).
     */
    private boolean isPartyMember(NetworkAbilityRaw a) {
        return isPartyMember(a.actorId());
    }

    private boolean isPartyMember(long actorId) {
        // 파티원 직접 확인
        if (partyMemberIds.contains(actorId)) {
            return true;
        }

        // 펫/소환수의 소유자가 파티원인지 확인
        Long owner = ownerByCombatantId.get(actorId);
        if (owner != null && partyMemberIds.contains(owner)) {
            return true;
        }

        // 파티 정보 미수신 시: PC면 파티원으로 간주 (레이트 스타트 대응)
        if (!partyDataInitialized && isPlayerCharacter(actorId)) {
            return true;
        }

        return false;
    }

    private boolean isFriendlyTarget(long targetId) {
        if (partyMemberIds.contains(targetId)) {
            return true;
        }
        Long owner = ownerByCombatantId.get(targetId);
        return owner != null && partyMemberIds.contains(owner);
    }

    /**
     * FFXIV에서 PC(플레이어 캐릭터)의 actorId는 0x10000000 ~ 0x1FFFFFFF 범위.
     * NPC/몬스터는 0x40000000+, 환경은 0xE0000000+ 등.
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
