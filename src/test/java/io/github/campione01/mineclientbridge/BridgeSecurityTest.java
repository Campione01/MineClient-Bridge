package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BridgeSecurityTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void bearerAuthenticationRequiresAnExactToken() {
        assertTrue(BridgeSecurity.bearerMatches(TOKEN, "Bearer " + TOKEN));
        assertTrue(BridgeSecurity.bearerMatches(TOKEN, "bearer " + TOKEN));
        assertFalse(BridgeSecurity.bearerMatches(TOKEN, "Bearer " + TOKEN + "x"));
        assertFalse(BridgeSecurity.bearerMatches(TOKEN, TOKEN));
        assertFalse(BridgeSecurity.bearerMatches("", "Bearer "));
        assertFalse(BridgeSecurity.bearerMatches(TOKEN, null));
    }
}
