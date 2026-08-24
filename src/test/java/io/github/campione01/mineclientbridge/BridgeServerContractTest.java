package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BridgeServerContractTest {
    @Test
    void sourceKeepsTheBoundedAuthenticatedControlSurface() throws Exception {
        Path project = Path.of(System.getProperty("mineclientBridge.projectDir"));
        String source = Files.readString(project.resolve(
                "src/main/java/io/github/campione01/mineclientbridge/BridgeServer.java"));

        assertEquals(12, occurrences(source, "createContext(\"/control/"));
        assertTrue(source.contains("MAX_BODY_BYTES = 64 * 1024"));
        assertTrue(source.contains("MAX_JSON_BYTES = 256 * 1024"));
        assertTrue(source.contains("MAX_FRAME_BYTES = 32 * 1024 * 1024"));
        assertTrue(source.contains("remoteAddress.isLoopbackAddress()"));
        assertTrue(source.contains("BridgeSecurity.bearerMatches"));
        assertFalse(source.contains("createContext(\"/" + "chat\""));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int position = 0;
        while ((position = source.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }
}
