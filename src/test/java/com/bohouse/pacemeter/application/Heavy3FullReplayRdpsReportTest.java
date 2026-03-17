package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Heavy3FullReplayRdpsReportTest {

    @Test
    void heavy3FullReplay_printsRdpsBreakdownReport() throws Exception {
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

        var resource = new ClassPathResource("replay/raw/heavy3_pull1_full.log");
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                ParsedLine parsed = parser.parse(line);
                if (parsed != null) {
                    ingestion.onParsed(parsed);
                }
            }
        }

        assertTrue(ingestion.isFightStarted(), "full replay should start combat");

        long elapsedMs = ingestion.nowElapsedMs();
        combatService.onEvent(new CombatEvent.Tick(elapsedMs));

        CombatDebugSnapshot snapshot = combatService.debugSnapshot();
        assertFalse(snapshot.actors().isEmpty(), "debug snapshot should contain actors");

        printReport(snapshot);
    }

    private static void printReport(CombatDebugSnapshot snapshot) {
        List<CombatDebugSnapshot.ActorDebugEntry> actors = snapshot.actors().stream()
                .filter(actor -> actor.totalDamage() > 0 || actor.grantedBuffContribution() > 0.0)
                .sorted(Comparator.comparingDouble(CombatDebugSnapshot.ActorDebugEntry::onlineRdps).reversed())
                .toList();

        System.out.println();
        System.out.println("=== Heavy3 Full Replay rDPS Breakdown ===");
        System.out.printf("Fight: %s | Phase: %s | Elapsed: %.3fs%n",
                snapshot.fightName(),
                snapshot.phase(),
                snapshot.elapsedMs() / 1000.0);
        System.out.println("Note: current formula is paceMeter online rDPS estimate, not FFLogs parity proof.");
        System.out.printf("%-3s %-18s %8s %12s %12s %12s %12s%n",
                "#", "Actor", "Job", "Raw", "Received", "Granted", "rDPS");

        int rank = 1;
        for (CombatDebugSnapshot.ActorDebugEntry actor : actors) {
            System.out.printf("%-3d %-18s %8s %12d %12.1f %12.1f %12.1f%n",
                    rank++,
                    truncate(actor.name(), 18),
                    formatJobId(actor.jobId()),
                    actor.totalDamage(),
                    actor.receivedBuffContribution(),
                    actor.grantedBuffContribution(),
                    actor.onlineRdps());
        }
        System.out.println();
    }

    private static String truncate(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxWidth) {
            return value;
        }
        return value.substring(0, maxWidth - 1) + ".";
    }

    private static String formatJobId(int jobId) {
        return switch (jobId) {
            case 19 -> "PLD";
            case 30 -> "NIN";
            case 34 -> "SAM";
            case 35 -> "RDM";
            case 39 -> "DNC";
            case 40 -> "RPR";
            case 41 -> "SGE";
            case 42 -> "VPR";
            default -> Integer.toString(jobId);
        };
    }
}
