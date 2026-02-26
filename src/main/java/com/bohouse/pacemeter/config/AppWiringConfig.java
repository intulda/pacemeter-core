package com.bohouse.pacemeter.config;

import com.bohouse.pacemeter.application.CombatService;
import com.bohouse.pacemeter.application.port.outbound.PaceProfileProvider;
import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppWiringConfig {

    @Bean
    public CombatEngine combatEngine() {
        return new CombatEngine();
    }

    // PaceProfileProvider는 FflogsPaceProfileProvider(@Component)가 자동 등록됨

    @Bean
    public CombatService combatService(
            CombatEngine engine,
            SnapshotPublisher snapshotPublisher,
            PaceProfileProvider paceProfileProvider
    ) {
        return new CombatService(engine, snapshotPublisher, paceProfileProvider);
    }

}
