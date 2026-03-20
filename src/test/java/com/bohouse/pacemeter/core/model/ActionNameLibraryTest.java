package com.bohouse.pacemeter.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionNameLibraryTest {

    @Test
    void resolveKnown_usesGeneratedCatalogForRoleActions() {
        String reprisal = ActionNameLibrary.resolveKnown(0x1D6F);
        assertEquals("reprisal", reprisal);
    }

    @Test
    void resolveKnown_keepsLegacyMappings() {
        String spinningEdge = ActionNameLibrary.resolveKnown(0x08C0);
        assertEquals("spinning edge", spinningEdge);
    }

    @Test
    void resolveKnown_resolvesAdditionalKnownAction() {
        String name = ActionNameLibrary.resolveKnown(0x1D75);
        assertEquals("second wind", name);
    }
}
