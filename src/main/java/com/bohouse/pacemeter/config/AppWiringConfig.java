package com.bohouse.pacemeter.config;

import com.bohouse.pacemeter.application.CombatService;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.application.port.outbound.PaceProfileProvider;
import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppWiringConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public CombatEngine combatEngine() {
        return new CombatEngine();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // PaceProfileProvider는 FflogsPaceProfileProvider(@Component)가 자동 등록됨

    @Bean
    public CombatService combatService(
            CombatEngine engine,
            SnapshotPublisher snapshotPublisher,
            PaceProfileProvider paceProfileProvider,
            EnrageTimeProvider enrageTimeProvider
    ) {
        return new CombatService(engine, snapshotPublisher, paceProfileProvider, enrageTimeProvider);
    }

}
