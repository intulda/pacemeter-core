package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.CombatState;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class Heavy3ReplayIntegrationTest {

    @Test
    void heavy3MinimalReplay_identifiesBossAndBuildsSnapshot() throws Exception {
        CombatEngine engine = new CombatEngine();
        List<OverlaySnapshot> publishedSnapshots = new ArrayList<>();
        SnapshotPublisher snapshotPublisher = publishedSnapshots::add;

        CombatService combatService = new CombatService(
                engine,
                snapshotPublisher,
                (fightName, actTerritoryId) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(new ObjectMapper())
        );
        ActLineParser parser = new ActLineParser();

        var resource = new ClassPathResource("replay/raw/heavy3_pull1_minimal.log");
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                ParsedLine parsed = parser.parse(line);
                assertNotNull(parsed, "raw fixture line should be parseable: " + line);
                ingestion.onParsed(parsed);
            }
        }

        assertTrue(ingestion.isFightStarted(), "raw replay should start combat");

        long elapsedMs = ingestion.nowElapsedMs();
        combatService.onEvent(new CombatEvent.Tick(elapsedMs));

        CombatState state = engine.currentState();
        assertEquals(CombatState.Phase.ACTIVE, state.phase());
        assertTrue(state.bossInfo().isPresent(), "boss should be identified from raw replay");

        CombatState.BossInfo bossInfo = state.bossInfo().orElseThrow();
        assertEquals("더 타이런트", bossInfo.name());
        assertEquals(154_287_371L, bossInfo.maxHp());

        assertFalse(state.actors().isEmpty(), "party actors should be populated");
        assertTrue(state.totalPartyDamage() > 0, "damage should accumulate from replay");

        assertFalse(publishedSnapshots.isEmpty(), "tick should publish at least one snapshot");
        OverlaySnapshot snapshot = publishedSnapshots.get(publishedSnapshots.size() - 1);
        assertEquals("아르카디아 선수권: 헤비급(영웅) (3)", snapshot.fightName());
        assertTrue(snapshot.totalPartyDamage() > 0);
        assertNull(snapshot.clearability(), "enrage provider not wired in this test");
        assertTrue(snapshot.actors().stream().anyMatch(actor -> actor.name().equals("한정서너나좋아싫어")));
    }

    @Test
    void heavy3MinimalReplay_buildsClearabilityWhenEnrageProviderIsWired() throws Exception {
        CombatEngine engine = new CombatEngine();
        List<OverlaySnapshot> publishedSnapshots = new ArrayList<>();
        SnapshotPublisher snapshotPublisher = publishedSnapshots::add;
        EnrageTimeProvider.EnrageInfo enrageInfo = new EnrageTimeProvider.EnrageInfo(
                480.0,
                EnrageTimeProvider.ConfidenceLevel.HIGH,
                "test://heavy3"
        );

        CombatService combatService = new CombatService(
                engine,
                snapshotPublisher,
                (fightName, actTerritoryId) -> Optional.empty(),
                territoryId -> Optional.of(enrageInfo)
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(new ObjectMapper())
        );
        ActLineParser parser = new ActLineParser();

        var resource = new ClassPathResource("replay/raw/heavy3_pull1_minimal.log");
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                ParsedLine parsed = parser.parse(line);
                assertNotNull(parsed, "raw fixture line should be parseable: " + line);
                ingestion.onParsed(parsed);
            }
        }

        long elapsedMs = ingestion.nowElapsedMs();
        combatService.onEvent(new CombatEvent.Tick(elapsedMs));

        assertFalse(publishedSnapshots.isEmpty(), "tick should publish at least one snapshot");
        OverlaySnapshot snapshot = publishedSnapshots.get(publishedSnapshots.size() - 1);
        assertNotNull(snapshot.clearability(), "clearability should be present when enrage is available");
        EnrageTimeProvider.ConfidenceLevel expectedConfidence = elapsedMs < 60_000
                ? EnrageTimeProvider.ConfidenceLevel.LOW
                : elapsedMs < 180_000
                ? EnrageTimeProvider.ConfidenceLevel.MEDIUM
                : EnrageTimeProvider.ConfidenceLevel.HIGH;
        assertEquals(expectedConfidence, snapshot.clearability().confidence());
        assertEquals(480.0, snapshot.clearability().enrageTimeSeconds(), 0.001);

        double expectedKillTime = snapshot.partyDps() > 0
                ? 154_287_371L / snapshot.partyDps()
                : Double.POSITIVE_INFINITY;
        assertEquals(expectedKillTime, snapshot.clearability().estimatedKillTimeSeconds(), 0.001);
        assertEquals(
                snapshot.clearability().enrageTimeSeconds() - snapshot.clearability().estimatedKillTimeSeconds(),
                snapshot.clearability().marginSeconds(),
                0.001
        );
        assertEquals(154_287_371L / 480.0, snapshot.clearability().requiredDps(), 0.001);
    }

    @Test
    void heavy3MinimalReplay_withoutEnrageProvider_keepsClearabilityNullEvenWithBoss() throws Exception {
        CombatEngine engine = new CombatEngine();
        List<OverlaySnapshot> publishedSnapshots = new ArrayList<>();
        SnapshotPublisher snapshotPublisher = publishedSnapshots::add;

        CombatService combatService = new CombatService(
                engine,
                snapshotPublisher,
                (fightName, actTerritoryId) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(new ObjectMapper())
        );
        ActLineParser parser = new ActLineParser();

        var resource = new ClassPathResource("replay/raw/heavy3_pull1_minimal.log");
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                ParsedLine parsed = parser.parse(line);
                assertNotNull(parsed);
                ingestion.onParsed(parsed);
            }
        }

        combatService.onEvent(new CombatEvent.Tick(ingestion.nowElapsedMs()));

        OverlaySnapshot snapshot = publishedSnapshots.get(publishedSnapshots.size() - 1);
        assertNotNull(engine.currentState().bossInfo().orElse(null), "boss should still be identified");
        assertNull(snapshot.clearability(), "clearability must remain null when enrage info is absent");
    }

    @Test
    void fightEndSnapshot_preservesClearabilityWhenEnrageIsAvailable() throws Exception {
        CombatEngine engine = new CombatEngine();
        List<OverlaySnapshot> publishedSnapshots = new ArrayList<>();
        SnapshotPublisher snapshotPublisher = publishedSnapshots::add;
        EnrageTimeProvider.EnrageInfo enrageInfo = new EnrageTimeProvider.EnrageInfo(
                480.0,
                EnrageTimeProvider.ConfidenceLevel.HIGH,
                "test://heavy3"
        );

        CombatService combatService = new CombatService(
                engine,
                snapshotPublisher,
                (fightName, actTerritoryId) -> Optional.empty(),
                territoryId -> Optional.of(enrageInfo)
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(new ObjectMapper())
        );
        ActLineParser parser = new ActLineParser();

        var resource = new ClassPathResource("replay/raw/heavy3_pull1_minimal.log");
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                ParsedLine parsed = parser.parse(line);
                assertNotNull(parsed);
                ingestion.onParsed(parsed);
            }
        }

        long elapsedMs = ingestion.nowElapsedMs();
        combatService.onEvent(new CombatEvent.FightEnd(elapsedMs, false));

        OverlaySnapshot snapshot = publishedSnapshots.get(publishedSnapshots.size() - 1);
        assertTrue(snapshot.isFinal());
        assertNotNull(snapshot.clearability());
        assertEquals(CombatState.Phase.ENDED, snapshot.phase());
    }
}
