package io.github.campione01.mineclientbridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class BridgeSecurity {
    private BridgeSecurity() {
    }

    static boolean bearerMatches(String expectedToken, String authorization) {
        if (expectedToken == null || expectedToken.isBlank() || authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }

        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }
}
