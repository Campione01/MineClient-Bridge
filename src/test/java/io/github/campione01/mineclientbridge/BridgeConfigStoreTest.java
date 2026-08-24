package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeConfigStoreTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearProperties() {
        System.clearProperty("mineclientBridge.port");
    }

    @Test
    void configExcludesTokenAndAppliesPropertyBeforeEnvironment() throws Exception {
        Path configPath = temporaryDirectory.resolve("mineclient-bridge.json");
        Path tokenPath = temporaryDirectory.resolve("mineclient-bridge.token");
        System.setProperty("mineclientBridge.port", "39001");

        BridgeConfig config = BridgeConfigStore.load(
                configPath,
                tokenPath,
                Map.of(
                        "MINECLIENT_BRIDGE_ENABLED", "false",
                        "MINECLIENT_BRIDGE_PORT", "39002")::get);

        assertFalse(config.enabled());
        assertEquals(39001, config.port());
        assertEquals(BridgeConfig.DEFAULT_HOST, config.host());
        assertTrue(TokenStore.isValidToken(config.token()));

        String persisted = Files.readString(configPath);
        assertTrue(persisted.contains("\"enabled\""));
        assertFalse(persisted.contains(config.token()));
        assertFalse(persisted.contains("\"token\""));
    }

    @Test
    void nonLoopbackHostFallsBackToDefault() {
        Path configPath = temporaryDirectory.resolve("mineclient-bridge.json");
        Path tokenPath = temporaryDirectory.resolve("mineclient-bridge.token");

        BridgeConfig config = BridgeConfigStore.load(
                configPath,
                tokenPath,
                Map.of("MINECLIENT_BRIDGE_HOST", "192.0.2.1")::get);

        assertEquals(BridgeConfig.DEFAULT_HOST, config.host());
    }
}
