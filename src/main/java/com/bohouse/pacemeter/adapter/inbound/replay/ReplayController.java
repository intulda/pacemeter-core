package com.bohouse.pacemeter.adapter.inbound.replay;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.CombatantAdded;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.inbound.actws.PrimaryPlayerChanged;
import com.bohouse.pacemeter.application.ActIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final ActLineParser parser;
    private final ActIngestionService ingestion;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public ReplayController(ActLineParser parser, ActIngestionService ingestion) {
        this.parser = parser;
        this.ingestion = ingestion;
    }

    @PostMapping("/start")
    public String startReplay(
            @RequestParam(defaultValue = "10") int delayMs,
            @RequestParam(defaultValue = "한정서너나좋아싫어") String playerName,
            @RequestParam(defaultValue = "heavy3_pull1_full.log") String fileName) {
        executor.submit(() -> {
            try {
                log.info("[Replay] starting replay from {} (delay={}ms, ='{}')", fileName, delayMs, playerName);
                var resource = new ClassPathResource(fileName);
                try (var reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                    String line;
                    int count = 0;
                    boolean playerSet = false;
                    boolean primaryPlayerSeenInLog = false;

                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;

                        ParsedLine parsed = parser.parse(line);
                        if (parsed != null) {
                            ingestion.onParsed(parsed);
                            count++;

                            if (parsed instanceof PrimaryPlayerChanged p) {
                                primaryPlayerSeenInLog = true;
                                playerSet = true;
                                log.info("[Replay] primary player from log: {}(id={})",
                                        p.playerName(), Long.toHexString(p.playerId()));
                                continue;
                            }

                            Optional<PrimaryPlayerChanged> fallbackPrimary = fallbackPrimaryPlayerChange(
                                    parsed, playerName, playerSet
                            );
                            if (fallbackPrimary.isPresent()) {
                                PrimaryPlayerChanged fallback = fallbackPrimary.orElseThrow();
                                ingestion.onParsed(fallback);
                                playerSet = true;
                                log.info("[Replay] primary player fallback by name: {}(id={})",
                                        fallback.playerName(), Long.toHexString(fallback.playerId()));
                            }
                        }

                        if (delayMs > 0) {
                            Thread.sleep(delayMs);
                        }
                    }

                    if (!playerSet) {
                        log.warn("[Replay] no primary player resolved from replay. playerName='{}' primaryLineSeen={}",
                                playerName, primaryPlayerSeenInLog);
                    }
                    log.info("[Replay] finished. {} lines processed", count);
                }
            } catch (Exception e) {
                log.error("[Replay] failed", e);
            }
        });

        return "Replay started (delay=" + delayMs + "ms, playerName='" + playerName + "'). Check logs for progress.";
    }

    static Optional<PrimaryPlayerChanged> fallbackPrimaryPlayerChange(
            ParsedLine parsed,
            String playerName,
            boolean playerAlreadySet
    ) {
        if (playerAlreadySet || playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        if (!(parsed instanceof CombatantAdded c)) {
            return Optional.empty();
        }
        if (!c.name().equals(playerName)) {
            return Optional.empty();
        }
        return Optional.of(new PrimaryPlayerChanged(c.ts(), c.id(), c.name()));
    }
}
