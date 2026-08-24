package io.github.campione01.mineclientbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.function.Function;
import net.neoforged.fml.loading.FMLPaths;

public final class BridgeConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_CONFIG_BYTES = 64 * 1024;
    private static final String PROPERTY_PREFIX = "mineclientBridge.";
    private static final String ENV_PREFIX = "MINECLIENT_BRIDGE_";

    private BridgeConfigStore() {
    }

    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("mineclient-bridge.json");
    }

    public static Path tokenPath() {
        return FMLPaths.CONFIGDIR.get().resolve("mineclient-bridge.token");
    }

    public static BridgeConfig load() {
        return load(configPath(), tokenPath(), System::getenv);
    }

    static BridgeConfig load(Path configPath, Path tokenPath, Function<String, String> environment) {
        String storedToken;
        try {
            storedToken = TokenStore.loadOrCreate(tokenPath);
        } catch (IOException e) {
            BridgeLog.LOGGER.warn("MineClient Bridge token store is unavailable; bridge disabled: {}",
                    e.getMessage());
            return new BridgeConfig(false, BridgeConfig.DEFAULT_HOST, BridgeConfig.DEFAULT_PORT, "");
        }

        BridgeConfig defaults = BridgeConfig.defaults(storedToken);
        BridgeConfig fileConfig = readConfig(configPath, defaults);
        BridgeConfig configured = applyOverrides(fileConfig, environment);
        if (!isLoopbackHost(configured.host())) {
            BridgeLog.LOGGER.warn(
                    "MineClient Bridge host is not loopback; using {}",
                    BridgeConfig.DEFAULT_HOST);
            configured = new BridgeConfig(
                    configured.enabled(),
                    BridgeConfig.DEFAULT_HOST,
                    configured.port(),
                    configured.token());
        }
        if (configured.port() < 1 || configured.port() > 65535) {
            BridgeLog.LOGGER.warn(
                    "MineClient Bridge port is invalid; using {}",
                    BridgeConfig.DEFAULT_PORT);
            configured = new BridgeConfig(
                    configured.enabled(),
                    configured.host(),
                    BridgeConfig.DEFAULT_PORT,
                    configured.token());
        }
        if (!TokenStore.isValidToken(configured.token())) {
            BridgeLog.LOGGER.warn("MineClient Bridge token override is invalid; using token store");
            configured = new BridgeConfig(
                    configured.enabled(),
                    configured.host(),
                    configured.port(),
                    storedToken);
        }

        saveFileConfig(configPath, configured);
        return configured;
    }

    private static BridgeConfig readConfig(Path path, BridgeConfig defaults) {
        if (!Files.exists(path)) {
            return defaults;
        }
        try {
            long size = Files.size(path);
            if (size < 0 || size > MAX_CONFIG_BYTES) {
                throw new IOException("Config file exceeds " + MAX_CONFIG_BYTES + " bytes");
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Config root must be a JSON object");
            }
            JsonObject object = parsed.getAsJsonObject();
            return new BridgeConfig(
                    booleanValue(object, "enabled", defaults.enabled()),
                    stringValue(object, "host", defaults.host()),
                    integerValue(object, "port", defaults.port()),
                    defaults.token());
        } catch (Exception e) {
            BridgeLog.LOGGER.warn("Failed to read MineClient Bridge config; using defaults: {}",
                    e.getMessage());
            return defaults;
        }
    }

    private static BridgeConfig applyOverrides(
            BridgeConfig config,
            Function<String, String> environment) {
        boolean enabled = parseBoolean(
                runtimeValue("enabled", environment),
                config.enabled());
        String host = fallback(runtimeValue("host", environment), config.host());
        int port = parseInteger(runtimeValue("port", environment), config.port());
        String token = fallback(runtimeValue("token", environment), config.token());
        return new BridgeConfig(enabled, host.trim(), port, token.trim());
    }

    private static String runtimeValue(String suffix, Function<String, String> environment) {
        String property = System.getProperty(PROPERTY_PREFIX + suffix);
        if (property != null && !property.isBlank()) {
            return property;
        }
        return environment.apply(ENV_PREFIX + camelToUpperSnake(suffix));
    }

    private static String camelToUpperSnake(String value) {
        StringBuilder result = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isUpperCase(character) && i > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(character));
        }
        return result.toString();
    }

    private static boolean isLoopbackHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void saveFileConfig(Path path, BridgeConfig config) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("enabled", config.enabled());
            object.addProperty("host", config.host());
            object.addProperty("port", config.port());

            Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
            Files.writeString(
                    temporary,
                    GSON.toJson(object) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            BridgeLog.LOGGER.warn("Failed to save MineClient Bridge config: {}", e.getMessage());
        }
    }

    private static String fallback(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static int parseInteger(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean()
                : fallback;
    }

    private static int integerValue(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
