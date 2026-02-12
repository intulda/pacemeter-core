package com.bohouse.pacemeter.config;

import com.bohouse.pacemeter.application.CombatService;
import com.bohouse.pacemeter.application.port.outbound.PaceProfileProvider;
import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
public class AppWiringConfig {

    @Bean
    public CombatEngine combatEngine() {
        return new CombatEngine(); // PaceProfile.NONE 기본
    }

    @Bean
    public PaceProfileProvider paceProfileProvider() {
        // MVP: 일단 항상 NONE 반환(프로필 로딩은 다음 스텝)
        return fightName -> Optional.of(PaceProfile.NONE);
    }

    @Bean
    public CombatService combatService(
            CombatEngine engine,
            SnapshotPublisher snapshotPublisher,
            PaceProfileProvider paceProfileProvider
    ) {
        return new CombatService(engine, snapshotPublisher, paceProfileProvider);
    }

}
