package io.github.campione01.mineclientbridge;

import java.util.Objects;

public record BridgeConfig(boolean enabled, String host, int port, String token) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 38121;

    public BridgeConfig {
        host = Objects.requireNonNull(host, "host");
        token = Objects.requireNonNull(token, "token");
    }

    public static BridgeConfig defaults(String token) {
        return new BridgeConfig(true, DEFAULT_HOST, DEFAULT_PORT, token);
    }
}
