package com.bohouse.pacemeter.adapter.inbound.tick;

import com.bohouse.pacemeter.application.ActIngestionService;
import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.core.event.CombatEvent;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@EnableScheduling
@Component
public class TickDriver {

    private final CombatEventPort combatEventPort;
    private final ActIngestionService actIngestionService;

    public TickDriver(CombatEventPort combatEventPort,
                      ActIngestionService actIngestionService) {
        this.combatEventPort = combatEventPort;
        this.actIngestionService = actIngestionService;
    }

    @Scheduled(fixedRate = 100)
    public void tick() {
        if (!actIngestionService.isFightStarted()) return;

        long elapsed = actIngestionService.nowElapsedMs();
        combatEventPort.onEvent(new CombatEvent.Tick(elapsed));
    }
}