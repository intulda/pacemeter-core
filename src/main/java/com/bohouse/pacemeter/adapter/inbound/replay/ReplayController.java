package com.bohouse.pacemeter.adapter.inbound.replay;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
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
            @RequestParam(defaultValue = "") String playerName,
            @RequestParam(defaultValue = "test_combat.log") String fileName) {
        executor.submit(() -> {
            try {
                log.info("[Replay] starting replay from {} (delay={}ms, playerName='{}')", fileName, delayMs, playerName);
                var resource = new ClassPathResource(fileName);
                try (var reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                    String line;
                    int count = 0;
                    boolean playerSet = false;

                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;

                        ParsedLine parsed = parser.parse(line);
                        if (parsed != null) {
                            ingestion.onParsed(parsed);
                            count++;

                            // 특정 플레이어 이름이 지정되었고, 아직 설정 안 했으면
                            if (!playerSet && !playerName.isEmpty()
                                    && parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.CombatantAdded c) {
                                if (c.name().equals(playerName)) {
                                    // 해당 플레이어를 YOU로 설정
                                    ingestion.onParsed(new com.bohouse.pacemeter.adapter.inbound.actws.PrimaryPlayerChanged(
                                            c.ts(), c.id(), c.name()));
                                    playerSet = true;
                                    log.info("[Replay] set primary player: {}(id={})", c.name(), Long.toHexString(c.id()));
                                }
                            }
                        }

                        if (delayMs > 0) {
                            Thread.sleep(delayMs);
                        }
                    }

                    log.info("[Replay] finished. {} lines processed", count);
                }
            } catch (Exception e) {
                log.error("[Replay] failed", e);
            }
        });

        return "Replay started (delay=" + delayMs + "ms, playerName='" + playerName + "'). Check logs for progress.";
    }
}