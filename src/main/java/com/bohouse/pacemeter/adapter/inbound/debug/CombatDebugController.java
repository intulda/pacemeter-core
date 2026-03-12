package com.bohouse.pacemeter.adapter.inbound.debug;

import com.bohouse.pacemeter.application.CombatDebugSnapshot;
import com.bohouse.pacemeter.application.CombatService;
import com.bohouse.pacemeter.application.RelaySessionManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/debug/combat")
public class CombatDebugController {

    private final CombatService combatService;
    private final RelaySessionManager relaySessionManager;

    public CombatDebugController(CombatService combatService, RelaySessionManager relaySessionManager) {
        this.combatService = combatService;
        this.relaySessionManager = relaySessionManager;
    }

    @GetMapping
    public CombatDebugSnapshot currentCombatDebug(@RequestParam(required = false) String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return relaySessionManager.debugSnapshot(sessionId);
        }
        return combatService.debugSnapshot();
    }
}
