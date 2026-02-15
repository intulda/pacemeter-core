package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.*;
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

    private final CombatEventPort combatEventPort;

    private volatile long currentPlayerId = 0;
    private volatile String currentPlayerName = "YOU";

    private final Map<Long, Long> ownerByCombatantId = new HashMap<>();

    private static final long COMBAT_TIMEOUT_MS = 15_000; // 15초 무활동 시 전투 종료

    private volatile boolean fightStarted = false;
    private volatile Instant fightStartInstant = null;
    private volatile Instant lastDamageAt = null;
    private volatile Instant lastEventInstant = null;  // 마지막 ACT 이벤트 타임스탬프 (wall clock 아님)

    // 진단용 카운터
    private volatile long receivedAbilityCount = 0;
    private volatile long emittedDamageCount = 0;
    private volatile long filteredByYouCount = 0;
    private volatile long zeroDamageCount = 0;

    public ActIngestionService(CombatEventPort combatEventPort) {
        this.combatEventPort = combatEventPort;
    }

    public boolean isFightStarted() {
        if (!fightStarted) return false;

        // 마지막 데미지 이후 타임아웃이면 전투 자동 종료 (ACT 이벤트 타임스탬프 기준)
        if (lastDamageAt != null && lastEventInstant != null) {
            long idleMs = Duration.between(lastDamageAt, lastEventInstant).toMillis();
            if (idleMs > COMBAT_TIMEOUT_MS) {
                endFight();
                return false;
            }
        }

        return true;
    }

    private void endFight() {
        long elapsedMs = nowElapsedMs();
        logger.info("[Ingestion] fight auto-ended ({}ms idle timeout) elapsed={}ms",
                COMBAT_TIMEOUT_MS, elapsedMs);
        combatEventPort.onEvent(new CombatEvent.FightEnd(elapsedMs, false));
        fightStarted = false;
        fightStartInstant = null;
        lastDamageAt = null;
        lastEventInstant = null;
        currentPlayerId = 0;
        currentPlayerName = "YOU";
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

        if (line instanceof PrimaryPlayerChanged p) {
            this.currentPlayerId = p.playerId();
            this.currentPlayerName = p.playerName();
            return;
        }

        if (line instanceof CombatantAdded c) {
            logger.info("[Ingestion] CombatantAdded: name={}(id={}) ownerId={} rawLine={}",
                    c.name(), Long.toHexString(c.id()), Long.toHexString(c.ownerId()), c.rawLine());
            if (c.ownerId() != 0) ownerByCombatantId.put(c.id(), c.ownerId());
            return;
        }

        if (line instanceof NetworkAbilityRaw a) {
            receivedAbilityCount++;
            boolean isYou = isYouActor(a);
            if (a.damage() <= 0) zeroDamageCount++;
            if (a.damage() > 0 && !isYou) filteredByYouCount++;

            logger.info("[Ingestion] NetworkAbility #{}: actor={}(id={}) skill={}({}) damage={} isYou={} | stats: recv={} emit={} filtered={} zero={}",
                    receivedAbilityCount,
                    a.actorName(), Long.toHexString(a.actorId()),
                    a.skillName(), Integer.toHexString(a.skillId()),
                    a.damage(), isYou,
                    receivedAbilityCount, emittedDamageCount, filteredByYouCount, zeroDamageCount);
            if (a.damage() > 0) {
                emitDamage(a);
            }
            return;
        }

        if (line instanceof DamageText d) {
            // v1: typeCode 21에서 직접 데미지 추출하므로 DamageText 매칭은 미사용
            return;
        }

        if (line instanceof BuffApplyRaw b) {
            if (!fightStarted) return;
            long tsMs = toElapsedMs(b.ts());
            combatEventPort.onEvent(new CombatEvent.BuffApply(
                    tsMs,
                    new ActorId(b.sourceId()),
                    new ActorId(b.targetId()),
                    new BuffId(b.statusId()),
                    (long) (b.durationSec() * 1000)
            ));
            return;
        }

        if (line instanceof BuffRemoveRaw b) {
            if (!fightStarted) return;
            long tsMs = toElapsedMs(b.ts());
            combatEventPort.onEvent(new CombatEvent.BuffRemove(
                    tsMs,
                    new ActorId(b.sourceId()),
                    new ActorId(b.targetId()),
                    new BuffId(b.statusId())
            ));
        }
    }

    private void emitDamage(NetworkAbilityRaw a) {
        // v1: YOU만 우선 처리(원하면 이 조건 제거해서 파티 전체로 확장)
        if (!isYouActor(a)) return;

        ensureFightStarted(a.ts());
        lastDamageAt = a.ts();

        long tsMs = Duration.between(fightStartInstant, a.ts()).toMillis();
        if (tsMs < 0) tsMs = 0;

        DamageType damageType = mapDamageTypeV1(a);

        emittedDamageCount++;
        logger.info("[Ingestion] DamageEvent #{}: actor={} skill={} amount={} tsMs={}",
                emittedDamageCount, a.actorName(), a.skillName(), a.damage(), tsMs);
        combatEventPort.onEvent(new CombatEvent.DamageEvent(
                tsMs,
                new ActorId(a.actorId()),
                a.actorName(),
                new ActorId(a.targetId()),
                a.skillId(),
                a.damage(),
                damageType
        ));
    }

    private DamageType mapDamageTypeV1(NetworkAbilityRaw a) {
        // v1 규칙:
        // - 기본: DIRECT
        // - (ownerId==currentPlayerId) 이면 PET
        // - (DOT는 24 메시지 붙일 때 처리)
        Long owner = ownerByCombatantId.get(a.actorId());
        if (owner != null && owner != 0 && owner == currentPlayerId) return DamageType.PET;
        return DamageType.DIRECT;
    }

    private void ensureFightStarted(Instant firstEventTs) {
        if (fightStarted) return;
        fightStarted = true;
        fightStartInstant = firstEventTs;
        logger.info("[Ingestion] fight started at {}", firstEventTs);
        combatEventPort.onEvent(new CombatEvent.FightStart(0L, "act_fight"));
    }

    private boolean isYouActor(NetworkAbilityRaw a) {
        // PrimaryPlayerChanged를 아직 못 받았으면 첫 PC 액터를 자동 감지
        if (currentPlayerId == 0 && isPlayerCharacter(a.actorId())) {
            currentPlayerId = a.actorId();
            currentPlayerName = a.actorName();
            logger.info("[Ingestion] auto-detected player: {}(id={})",
                    currentPlayerName, Long.toHexString(currentPlayerId));
        }

        if (currentPlayerId != 0) {
            if (a.actorId() == currentPlayerId) return true;
            Long owner = ownerByCombatantId.get(a.actorId());
            if (owner != null && owner == currentPlayerId) return true;
        }
        if (a.actorName() != null && a.actorName().equals(currentPlayerName)) return true;
        return false;
    }

    /**
     * FFXIV에서 PC(플레이어 캐릭터)의 actorId는 0x10000000 ~ 0x1FFFFFFF 범위.
     * NPC/몬스터는 0x40000000+, 환경은 0xE0000000+ 등.
     */
    private static boolean isPlayerCharacter(long actorId) {
        return actorId >= 0x10000000L && actorId < 0x20000000L;
    }

    private long toElapsedMs(Instant eventTs) {
        if (fightStartInstant == null) return 0;
        long ms = Duration.between(fightStartInstant, eventTs).toMillis();
        return Math.max(0, ms);
    }

}