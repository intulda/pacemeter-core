package com.bohouse.pacemeter.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTagCatalogTest {

    @Test
    void tagsKnownDragoonWeaponskillAsWeaponskill() {
        assertTrue(ActionTagCatalog.tagsFor(0x9058).contains(ActionTagCatalog.ActionTag.WEAPONSKILL));
    }

    @Test
    void leavesUnknownActionUntagged() {
        assertFalse(ActionTagCatalog.tagsFor(0xFFFF).contains(ActionTagCatalog.ActionTag.WEAPONSKILL));
    }
}
