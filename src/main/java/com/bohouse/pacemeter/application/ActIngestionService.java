package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.*;
import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.DamageType;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
public final class ActIngestionService {

    private final CombatEventPort combatEventPort;

    private volatile long currentPlayerId = 0;
    private volatile String currentPlayerName = "YOU";

    private final Map<Long, Long> ownerByCombatantId = new HashMap<>();

    private final Deque<NetworkAbilityRaw> abilities = new ArrayDeque<>();
    private final Duration window = Duration.ofSeconds(2);

    private volatile boolean fightStarted = false;
    private volatile Instant fightStartInstant = null;

    public ActIngestionService(CombatEventPort combatEventPort) {
        this.combatEventPort = combatEventPort;
    }

    public boolean isFightStarted() {
        return fightStarted;
    }

    /** TickDriver에서 쓸 수 있게 "지금 전투 기준 경과 ms" 제공 */
    public long nowElapsedMs() {
        if (!fightStarted || fightStartInstant == null) return 0;
        long ms = Duration.between(fightStartInstant, Instant.now()).toMillis();
        return Math.max(0, ms);
    }

    public void onParsed(ParsedLine line) {
        if (line == null) return;

        prune(line.ts());

        if (line instanceof PrimaryPlayerChanged p) {
            this.currentPlayerId = p.playerId();
            this.currentPlayerName = p.playerName();
            return;
        }

        if (line instanceof CombatantAdded c) {
            if (c.ownerId() != 0) ownerByCombatantId.put(c.id(), c.ownerId());
            return;
        }

        if (line instanceof NetworkAbilityRaw a) {
            abilities.addLast(a);
            return;
        }

        if (line instanceof DamageText d) {
            NetworkAbilityRaw match = matchAbility(d);
            if (match != null) emitDamage(match, d);
        }
    }

    private void emitDamage(NetworkAbilityRaw a, DamageText d) {
        // v1: YOU만 우선 처리(원하면 이 조건 제거해서 파티 전체로 확장)
        if (!isYouActor(a, d)) return;

        ensureFightStarted(d.ts());

        long tsMs = Duration.between(fightStartInstant, d.ts()).toMillis();
        if (tsMs < 0) tsMs = 0;

        DamageType damageType = mapDamageTypeV1(a);

        combatEventPort.onEvent(new CombatEvent.DamageEvent(
                tsMs,
                new ActorId(a.actorId()),
                a.actorName(),
                new ActorId(a.targetId()),
                a.skillId(),
                d.amount(),
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
        combatEventPort.onEvent(new CombatEvent.FightStart(0L, "act_fight"));
    }

    private boolean isYouActor(NetworkAbilityRaw a, DamageText d) {
        if (currentPlayerId != 0) {
            if (a.actorId() == currentPlayerId) return true;
            Long owner = ownerByCombatantId.get(a.actorId());
            if (owner != null && owner == currentPlayerId) return true;
        }
        if (a.actorName() != null && a.actorName().equals(currentPlayerName)) return true;
        if (d.sourceTextName() != null && currentPlayerName != null && d.sourceTextName().contains(currentPlayerName)) return true;
        return false;
    }

    private NetworkAbilityRaw matchAbility(DamageText d) {
        NetworkAbilityRaw best = null;
        int bestScore = -1;
        for (var a : abilities) {
            int s = score(a, d);
            if (s > bestScore) { bestScore = s; best = a; }
        }
        return bestScore >= 7 ? best : null;
    }

    private int score(NetworkAbilityRaw a, DamageText d) {
        int s = 0;
        long dtMs = Math.abs(Duration.between(a.ts(), d.ts()).toMillis());
        if (dtMs <= 200) s += 5;
        else if (dtMs <= 500) s += 3;
        else if (dtMs <= 1000) s += 1;

        String t1 = norm(d.targetTextName());
        String t2 = norm(a.targetName());
        if (t1 != null && t2 != null) {
            if (t1.equals(t2)) s += 4;
            else if (t1.contains(t2) || t2.contains(t1)) s += 2;
        }

        String src = norm(d.sourceTextName());
        String actor = norm(a.actorName());
        if (src != null && actor != null) {
            if (src.contains(actor) || actor.contains(src)) s += 3;
        }

        String you = norm(currentPlayerName);
        if (actor != null && you != null && actor.equals(you)) s += 2;

        return s;
    }

    private static String norm(String s) {
        if (s == null) return null;
        return s.replaceAll("\\s+", "").trim();
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(window);
        while (!abilities.isEmpty() && abilities.peekFirst().ts().isBefore(cutoff)) abilities.removeFirst();
    }
}