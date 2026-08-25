package io.github.campione01.mineclientbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class BridgeServer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String PROTOCOL_NAME = "mineclient-bridge";
    private static final int PROTOCOL_SCHEMA_VERSION = 2;
    private static final int HTTP_BACKLOG = 16;
    private static final int HTTP_WORKERS = 4;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final int MAX_COMMAND_LENGTH = 256;
    private static final int MAX_GLFW_KEY_CODE = 348;
    private static final int MAX_KEYMAPS = 256;
    private static final int MAX_STATUS_EFFECTS = 64;
    private static final int MAX_NEARBY_ENTITIES = 64;
    private static final int MAX_SCREEN_CHILDREN = 128;
    private static final int MAX_CONTAINER_SLOTS = 256;
    private static final double DEFAULT_ENTITY_RADIUS = 16.0;
    private static final double MAX_ENTITY_RADIUS = 32.0;
    private static final double MAX_GUI_COORDINATE = 1_000_000.0;
    private static final long MINECRAFT_TIMEOUT_SECONDS = 5;
    private static final long FRAME_TIMEOUT_SECONDS = 15;
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();
    private static final LinkedHashSet<InputConstants.Key> HELD_RAW_KEYS = new LinkedHashSet<>();
    private static volatile HttpServer server;
    private static volatile ExecutorService serverExecutor;
    private static volatile BridgeConfig config;

    private BridgeServer() {
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }

        config = BridgeConfigStore.load();
        if (!config.enabled()) {
            BridgeLog.LOGGER.info("MineClient Bridge disabled by config");
            return;
        }

        HttpServer createdServer = null;
        ExecutorService createdExecutor = null;
        try {
            InetAddress bindAddress = InetAddress.getByName(config.host());
            if (!bindAddress.isLoopbackAddress()) {
                throw new IOException("MineClient Bridge refuses non-loopback host: " + config.host());
            }

            createdServer = HttpServer.create(new InetSocketAddress(bindAddress, config.port()), HTTP_BACKLOG);
            createdServer.createContext("/control/status", BridgeServer::handleControlStatus);
            createdServer.createContext("/control/capabilities", BridgeServer::handleControlCapabilities);
            createdServer.createContext("/control/frame", BridgeServer::handleControlFrame);
            createdServer.createContext("/control/keymaps", BridgeServer::handleControlKeymaps);
            createdServer.createContext("/control/state", BridgeServer::handleControlState);
            createdServer.createContext("/control/screen", BridgeServer::handleControlScreen);
            createdServer.createContext("/control/key", BridgeServer::handleControlKey);
            createdServer.createContext("/control/raw-key", BridgeServer::handleControlRawKey);
            createdServer.createContext("/control/look", BridgeServer::handleControlLook);
            createdServer.createContext("/control/mouse", BridgeServer::handleControlMouse);
            createdServer.createContext("/control/text", BridgeServer::handleControlText);
            createdServer.createContext("/control/command", BridgeServer::handleControlCommand);
            createdServer.createContext("/control/release-all", BridgeServer::handleControlReleaseAll);
            createdServer.createContext("/control/close", BridgeServer::handleControlClose);
            createdServer.createContext("/", BridgeServer::handleRoot);

            createdExecutor = Executors.newFixedThreadPool(HTTP_WORKERS, workerThreadFactory());
            createdServer.setExecutor(createdExecutor);
            server = createdServer;
            serverExecutor = createdExecutor;
            createdServer.start();
            BridgeLog.LOGGER.info("MineClient Bridge listening on http://{}:{} auth_enabled={}",
                    config.host(), config.port(), !config.token().isBlank());
        } catch (IOException | RuntimeException e) {
            server = null;
            serverExecutor = null;
            if (createdServer != null) {
                createdServer.stop(0);
            }
            if (createdExecutor != null) {
                createdExecutor.shutdownNow();
            }
            BridgeLog.LOGGER.warn("Failed to start MineClient Bridge", e);
        }
    }

    public static synchronized void restart() {
        stop();
        start();
    }

    public static synchronized void stop() {
        releaseAllOnMinecraftThread();

        HttpServer currentServer = server;
        server = null;
        if (currentServer != null) {
            currentServer.stop(0);
        }

        ExecutorService currentExecutor = serverExecutor;
        serverExecutor = null;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
    }

    public static boolean isRunning() {
        return server != null;
    }

    public static BridgeConfig config() {
        if (config == null) {
            config = BridgeConfigStore.load();
        }
        return config;
    }

    private static ThreadFactory workerThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "MineClient-Bridge-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        respondJson(exchange, 404, error("unknown_endpoint"));
    }

    private static void handleControlStatus(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/status", "GET")) return;

        try {
            JsonObject status = callOnMinecraftThread(
                    BridgeServer::createControlStatus,
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, 200, status);
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "status_failed", e);
        }
    }

    private static void handleControlCapabilities(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/capabilities", "GET")) return;
        respondJson(exchange, 200, createCapabilities());
    }

    private static void handleControlFrame(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/frame", "GET")) return;

        try {
            byte[] png = callOnMinecraftThread(
                    BridgeServer::captureFrame,
                    FRAME_TIMEOUT_SECONDS);
            respondBytes(exchange, 200, "image/png", png);
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "frame_capture_failed", e);
        }
    }

    private static void handleControlKeymaps(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/keymaps", "GET")) return;

        try {
            JsonObject keymaps = callOnMinecraftThread(
                    BridgeServer::createKeymapsSnapshot,
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, 200, keymaps);
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "keymaps_failed", e);
        }
    }

    private static void handleControlState(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/state", "GET")) return;

        final double radius;
        try {
            radius = queryDouble(exchange, "radius", DEFAULT_ENTITY_RADIUS, 0.0, MAX_ENTITY_RADIUS);
        } catch (RequestException e) {
            respondRequestFailure(exchange, e);
            return;
        }

        try {
            EndpointResult result = callOnMinecraftThread(
                    () -> createStateSnapshot(radius),
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, result.status(), result.body());
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "state_failed", e);
        }
    }

    private static void handleControlScreen(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/screen", "GET")) return;

        try {
            JsonObject screen = callOnMinecraftThread(
                    BridgeServer::createScreenSnapshot,
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, 200, screen);
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "screen_failed", e);
        }
    }

    private static void handleControlKey(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/key", "POST")) return;

        JsonObject body = readJsonObjectOrRespond(exchange, false);
        if (body == null) return;

        final String mapping;
        final String action;
        try {
            mapping = requiredString(body, "mapping");
            action = requiredString(body, "action").trim().toLowerCase(Locale.ROOT);
            if (mapping.isBlank()) {
                throw new RequestException(400, "invalid_mapping", "mapping must not be blank");
            }
            if (!action.equals("down") && !action.equals("up") && !action.equals("click")) {
                throw new RequestException(400, "invalid_action", "action must be down, up, or click");
            }
        } catch (RequestException e) {
            respondRequestFailure(exchange, e);
            return;
        }

        try {
            EndpointResult result = callOnMinecraftThread(
                    () -> applyKeyAction(mapping, action),
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, result.status(), result.body());
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "key_action_failed", e);
        }
    }

    private static void handleControlRawKey(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/raw-key", "POST")) return;

        JsonObject body = readJsonObjectOrRespond(exchange, false);
        if (body == null) return;

        final String key;
        final String action;
        try {
            key = requiredString(body, "key");
            action = requiredString(body, "action").trim().toLowerCase(Locale.ROOT);
            if (key.isBlank()) {
                throw new RequestException(400, "invalid_key", "key must not be blank");
            }
            if (!action.equals("down") && !action.equals("up") && !action.equals("click")) {
                throw new RequestException(400, "invalid_action", "action must be down, up, or click");
            }
        } catch (RequestException e) {
            respondRequestFailure(exchange, e);
            return;
        }

        try {
            EndpointResult result = callOnMinecraftThread(
                    () -> applyRawKeyAction(key, action),
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, result.status(), result.body());
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "raw_key_action_failed", e);
        }
    }

    private static void handleControlLook(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/look", "POST")) return;

        JsonObject body = readJsonObjectOrRespond(exchange, false);
        if (body == null) return;

        final double yaw;
        final double pitch;
        final boolean relative;
        try {
            yaw = requiredFiniteDouble(body, "yaw");
            pitch = requiredFiniteDouble(body, "pitch");
            relative = optionalBoolean(body, "relative", false);
        } catch (RequestException e) {
            respondRequestFailure(exchange, e);
            return;
        }

        try {
            EndpointResult result = callOnMinecraftThread(
                    () -> applyLook(yaw, pitch, relative),
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, result.status(), result.body());
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "look_action_failed", e);
        }
    }

    private static void handleControlMouse(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/mouse", "POST")) return;

        JsonObject body = readJsonObjectOrRespond(exchange, false);
        if (body == null) return;

        final double x;
        final double y;
        final int button;
        final String action;
        final double scrollY;
        try {
            x = requiredBoundedCoordinate(body, "x");
            y = requiredBoundedCoordinate(body, "y");
            action = requiredString(body, "action").trim().toLowerCase(Locale.ROOT);
            if (!action.equals("move") && !action.equals("down") && !action.equals("up")
                    && !action.equals("release") && !action.equals("click") && !action.equals("scroll")) {
                throw new RequestException(400, "invalid_action",
                        "action must be move, down, up, release, click, or scroll");
            }
            button = optionalInteger(body, "button", 0);
            if (button < 0 || button > 7) {
                throw new RequestException(400, "invalid_button", "button must be between 0 and 7");
            }
            scrollY = action.equals("scroll") ? requiredFiniteDouble(body, "scrollY") : 0.0;
        } catch (RequestException e) {
            respondRequestFailure(exchange, e);
            return;
        }

        try {
            EndpointResult result = callOnMinecraftThread(
                    () -> applyMouseAction(x, y, button, action, scrollY),
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, result.status(), result.body());
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "mouse_action_failed", e);
        }
    }

    private static void handleControlText(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/text", "POST")) return;

        JsonObject body = readJsonObjectOrRespond(exchange, false);
        if (body == null) return;

        final String text;
        final boolean submit;
        try {
            text = requiredString(body, "text");
            submit = optionalBoolean(body, "submit", false);
            validateScreenText(text);
        } catch (RequestException e) {
            respondRequestFailure(exchange, e);
            return;
        }

        try {
            EndpointResult result = callOnMinecraftThread(
                    () -> applyScreenText(text, submit),
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, result.status(), result.body());
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "text_input_failed", e);
        }
    }

    private static void handleControlCommand(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/command", "POST")) return;

        JsonObject body = readJsonObjectOrRespond(exchange, false);
        if (body == null) return;

        final String command;
        try {
            command = normalizeCommand(requiredString(body, "command"));
        } catch (RequestException e) {
            respondRequestFailure(exchange, e);
            return;
        }

        try {
            EndpointResult result = callOnMinecraftThread(
                    () -> applyCommand(command),
                    MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, result.status(), result.body());
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "command_submission_failed", e);
        }
    }

    private static void handleControlReleaseAll(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/release-all", "POST")) return;
        if (readJsonObjectOrRespond(exchange, true) == null) return;

        try {
            JsonObject result = callOnMinecraftThread(() -> {
                int rawKeysReleased = releaseAllInputs();
                JsonObject obj = ok();
                obj.addProperty("released", true);
                obj.addProperty("raw_keys_released", rawKeysReleased);
                return obj;
            }, MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, 200, result);
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "release_all_failed", e);
        }
    }

    private static void handleControlClose(HttpExchange exchange) throws IOException {
        if (!requireControlAccess(exchange, "/control/close", "POST")) return;
        if (readJsonObjectOrRespond(exchange, true) == null) return;

        try {
            JsonObject result = callOnMinecraftThread(() -> {
                int rawKeysReleased = releaseAllInputs();
                Minecraft.getInstance().stop();
                JsonObject obj = ok();
                obj.addProperty("released", true);
                obj.addProperty("raw_keys_released", rawKeysReleased);
                obj.addProperty("closing", true);
                return obj;
            }, MINECRAFT_TIMEOUT_SECONDS);
            respondJson(exchange, 200, result);
        } catch (Exception e) {
            respondMinecraftFailure(exchange, "close_failed", e);
        }
    }

    private static JsonObject createCapabilities() {
        JsonObject obj = protocolOk();
        JsonArray operations = new JsonArray();
        addOperation(operations, "GET", "/control/status", "session_identity");
        addOperation(operations, "GET", "/control/capabilities", "capability_discovery");
        addOperation(operations, "GET", "/control/frame", "framebuffer_png");
        addOperation(operations, "GET", "/control/keymaps", "keymap_discovery");
        addOperation(operations, "GET", "/control/state", "client_state_snapshot");
        addOperation(operations, "GET", "/control/screen", "screen_snapshot");
        addOperation(operations, "POST", "/control/key", "keymap_input");
        addOperation(operations, "POST", "/control/raw-key", "internal_keyboard_input");
        addOperation(operations, "POST", "/control/look", "player_view");
        addOperation(operations, "POST", "/control/mouse", "screen_mouse_input");
        addOperation(operations, "POST", "/control/text", "focused_screen_text");
        addOperation(operations, "POST", "/control/command", "minecraft_command");
        addOperation(operations, "POST", "/control/release-all", "release_keymaps");
        addOperation(operations, "POST", "/control/close", "graceful_client_stop");
        obj.add("operations", operations);

        JsonObject limits = new JsonObject();
        limits.addProperty("request_body_bytes", MAX_BODY_BYTES);
        limits.addProperty("json_response_bytes", MAX_JSON_BYTES);
        limits.addProperty("frame_bytes", MAX_FRAME_BYTES);
        limits.addProperty("minecraft_timeout_seconds", MINECRAFT_TIMEOUT_SECONDS);
        limits.addProperty("frame_timeout_seconds", FRAME_TIMEOUT_SECONDS);
        limits.addProperty("nearby_radius_max", MAX_ENTITY_RADIUS);
        limits.addProperty("nearby_entities_max", MAX_NEARBY_ENTITIES);
        limits.addProperty("keymaps_max", MAX_KEYMAPS);
        limits.addProperty("screen_children_max", MAX_SCREEN_CHILDREN);
        limits.addProperty("container_slots_max", MAX_CONTAINER_SLOTS);
        limits.addProperty("text_characters_max", MAX_TEXT_LENGTH);
        limits.addProperty("command_characters_max", MAX_COMMAND_LENGTH);
        obj.add("limits", limits);

        JsonObject safety = new JsonObject();
        safety.addProperty("loopback_only", true);
        safety.addProperty("bearer_required", true);
        safety.addProperty("authenticated_minecraft_commands", true);
        safety.addProperty("internal_keyboard_events", true);
        safety.addProperty("arbitrary_scripts", false);
        safety.addProperty("direct_file_api", false);
        safety.addProperty("direct_outbound_network_api", false);
        safety.addProperty("direct_world_mutation_api", false);
        safety.addProperty("input_can_trigger_gameplay_and_gui_actions", true);
        safety.addProperty("os_input", false);
        obj.add("safety", safety);
        return obj;
    }

    private static void addOperation(JsonArray operations, String method, String path, String capability) {
        JsonObject operation = new JsonObject();
        operation.addProperty("method", method);
        operation.addProperty("path", path);
        operation.addProperty("capability", capability);
        operations.add(operation);
    }

    private static JsonObject createKeymapsSnapshot() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject obj = protocolOk();
        JsonArray entries = new JsonArray();
        int total = mc.options.keyMappings.length;
        int returned = Math.min(total, MAX_KEYMAPS);
        for (int i = 0; i < returned; i++) {
            KeyMapping mapping = mc.options.keyMappings[i];
            JsonObject entry = new JsonObject();
            entry.addProperty("name", boundedText(mapping.getName()));
            entry.addProperty("category", boundedText(mapping.getCategory()));
            entry.addProperty("bound_key", boundedText(mapping.saveString()));
            entry.addProperty("down", mapping.isDown());
            entry.addProperty("unbound", mapping.isUnbound());
            entries.add(entry);
        }
        obj.addProperty("total", total);
        obj.addProperty("returned", returned);
        obj.addProperty("truncated", total > returned);
        obj.add("keymaps", entries);
        return obj;
    }

    private static EndpointResult createStateSnapshot(double radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return new EndpointResult(409, error("not_in_world"));
        }

        JsonObject obj = protocolOk();
        JsonObject player = new JsonObject();
        player.addProperty("uuid", mc.player.getUUID().toString());
        player.addProperty("name", boundedText(mc.player.getGameProfile().getName()));
        player.addProperty("x", mc.player.getX());
        player.addProperty("y", mc.player.getY());
        player.addProperty("z", mc.player.getZ());
        player.addProperty("yaw", mc.player.getYRot());
        player.addProperty("pitch", mc.player.getXRot());
        player.add("velocity", vector(mc.player.getDeltaMovement()));
        player.addProperty("health", mc.player.getHealth());
        player.addProperty("max_health", mc.player.getMaxHealth());
        player.addProperty("food", mc.player.getFoodData().getFoodLevel());
        player.addProperty("saturation", mc.player.getFoodData().getSaturationLevel());
        player.addProperty("air", mc.player.getAirSupply());
        player.addProperty("max_air", mc.player.getMaxAirSupply());
        player.addProperty("xp_level", mc.player.experienceLevel);
        player.addProperty("xp_progress", mc.player.experienceProgress);
        player.addProperty("xp_total", mc.player.totalExperience);
        player.addProperty("gamemode", mc.gameMode == null
                ? "unknown"
                : mc.gameMode.getPlayerMode().getName());
        player.addProperty("dimension", mc.level.dimension().location().toString());
        player.addProperty("on_ground", mc.player.onGround());

        Inventory inventory = mc.player.getInventory();
        player.addProperty("selected_slot", inventory.selected);
        JsonArray inventoryItems = new JsonArray();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            JsonObject item = itemSnapshot(inventory.items.get(slot));
            item.addProperty("slot", slot);
            inventoryItems.add(item);
        }
        player.addProperty("inventory_total", inventory.items.size());
        player.addProperty("inventory_truncated", false);
        player.add("inventory", inventoryItems);

        JsonArray armor = new JsonArray();
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET}) {
            JsonObject item = itemSnapshot(mc.player.getItemBySlot(slot));
            item.addProperty("slot", slot.getName());
            armor.add(item);
        }
        player.add("armor", armor);
        player.add("offhand", itemSnapshot(mc.player.getOffhandItem()));

        JsonArray effects = new JsonArray();
        int effectTotal = mc.player.getActiveEffects().size();
        int effectCount = 0;
        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            if (effectCount >= MAX_STATUS_EFFECTS) break;
            JsonObject effectJson = new JsonObject();
            effectJson.addProperty("id", BuiltInRegistries.MOB_EFFECT
                    .getKey(effect.getEffect().value()).toString());
            effectJson.addProperty("amplifier", effect.getAmplifier());
            effectJson.addProperty("duration_ticks", effect.getDuration());
            effectJson.addProperty("ambient", effect.isAmbient());
            effectJson.addProperty("visible", effect.isVisible());
            effects.add(effectJson);
            effectCount++;
        }
        player.addProperty("effects_total", effectTotal);
        player.addProperty("effects_returned", effectCount);
        player.addProperty("effects_truncated", effectTotal > effectCount);
        player.add("effects", effects);
        obj.add("player", player);

        obj.add("crosshair", crosshairSnapshot(mc));

        JsonObject world = new JsonObject();
        world.addProperty("dimension", mc.level.dimension().location().toString());
        world.addProperty("game_time", mc.level.getGameTime());
        world.addProperty("day_time", mc.level.getDayTime());
        world.addProperty("raining", mc.level.isRaining());
        world.addProperty("thundering", mc.level.isThundering());
        world.addProperty("rain_level", mc.level.getRainLevel(1.0F));
        world.addProperty("thunder_level", mc.level.getThunderLevel(1.0F));
        obj.add("world", world);
        obj.add("nearby", nearbyEntitiesSnapshot(mc, radius));
        return new EndpointResult(200, obj);
    }

    private static JsonObject crosshairSnapshot(Minecraft mc) {
        JsonObject target = new JsonObject();
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            target.addProperty("type", "miss");
            return target;
        }

        target.addProperty("distance", hit.distanceTo(mc.player));
        target.add("location", vector(hit.getLocation()));
        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            target.addProperty("type", "block");
            target.addProperty("id", BuiltInRegistries.BLOCK
                    .getKey(mc.level.getBlockState(pos).getBlock()).toString());
            target.addProperty("x", pos.getX());
            target.addProperty("y", pos.getY());
            target.addProperty("z", pos.getZ());
            target.addProperty("face", blockHit.getDirection().getName());
            return target;
        }
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            target.addProperty("type", "entity");
            target.addProperty("entity_id", entity.getId());
            target.addProperty("uuid", entity.getUUID().toString());
            target.addProperty("entity_type", BuiltInRegistries.ENTITY_TYPE
                    .getKey(entity.getType()).toString());
            target.addProperty("name", boundedText(entity.getName().getString()));
            return target;
        }
        target.addProperty("type", hit.getType().name().toLowerCase(Locale.ROOT));
        return target;
    }

    private static JsonObject nearbyEntitiesSnapshot(Minecraft mc, double radius) {
        Comparator<EntityDistance> farthestFirst = Comparator
                .comparingDouble(EntityDistance::distance)
                .reversed();
        PriorityQueue<EntityDistance> nearest = new PriorityQueue<>(MAX_NEARBY_ENTITIES, farthestFirst);
        int total = 0;
        double radiusSquared = radius * radius;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            double distanceSquared = entity.distanceToSqr(mc.player);
            if (distanceSquared > radiusSquared) continue;
            total++;
            EntityDistance candidate = new EntityDistance(entity, Math.sqrt(distanceSquared));
            if (nearest.size() < MAX_NEARBY_ENTITIES) {
                nearest.add(candidate);
            } else if (candidate.distance() < nearest.peek().distance()) {
                nearest.poll();
                nearest.add(candidate);
            }
        }

        ArrayList<EntityDistance> sorted = new ArrayList<>(nearest);
        sorted.sort(Comparator.comparingDouble(EntityDistance::distance));
        JsonArray entities = new JsonArray();
        for (EntityDistance entry : sorted) {
            Entity entity = entry.entity();
            JsonObject entityJson = new JsonObject();
            entityJson.addProperty("entity_id", entity.getId());
            entityJson.addProperty("uuid", entity.getUUID().toString());
            entityJson.addProperty("type", BuiltInRegistries.ENTITY_TYPE
                    .getKey(entity.getType()).toString());
            entityJson.addProperty("class", boundedText(entity.getClass().getName()));
            entityJson.addProperty("name", boundedText(entity.getName().getString()));
            entityJson.addProperty("x", entity.getX());
            entityJson.addProperty("y", entity.getY());
            entityJson.addProperty("z", entity.getZ());
            entityJson.addProperty("yaw", entity.getYRot());
            entityJson.addProperty("pitch", entity.getXRot());
            entityJson.add("velocity", vector(entity.getDeltaMovement()));
            entityJson.addProperty("distance", entry.distance());
            entityJson.addProperty("alive", entity.isAlive());
            entityJson.addProperty("on_ground", entity.onGround());
            if (entity instanceof LivingEntity living) {
                entityJson.addProperty("health", living.getHealth());
                entityJson.addProperty("max_health", living.getMaxHealth());
            }
            entities.add(entityJson);
        }

        JsonObject nearby = new JsonObject();
        nearby.addProperty("radius", radius);
        nearby.addProperty("total", total);
        nearby.addProperty("returned", sorted.size());
        nearby.addProperty("truncated", total > sorted.size());
        nearby.add("entities", entities);
        return nearby;
    }

    private static JsonObject createScreenSnapshot() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject obj = protocolOk();
        Screen screen = mc.screen;
        obj.addProperty("open", screen != null);
        if (screen == null) {
            obj.addProperty("children_total", 0);
            obj.addProperty("children_returned", 0);
            obj.addProperty("children_truncated", false);
            obj.add("children", new JsonArray());
            return obj;
        }

        obj.addProperty("class", screen.getClass().getName());
        obj.addProperty("title", boundedText(screen.getTitle().getString()));
        obj.addProperty("gui_width", screen.width);
        obj.addProperty("gui_height", screen.height);
        GuiEventListener focused = screen.getFocused();
        obj.addProperty("focused_class", focused == null ? "" : focused.getClass().getName());

        JsonArray children = new JsonArray();
        int totalChildren = screen.children().size();
        int childLimit = Math.min(totalChildren, MAX_SCREEN_CHILDREN);
        for (int i = 0; i < childLimit; i++) {
            GuiEventListener child = screen.children().get(i);
            JsonObject childJson = new JsonObject();
            childJson.addProperty("index", i);
            childJson.addProperty("class", boundedText(child.getClass().getName()));
            childJson.addProperty("focused", child == focused || child.isFocused());
            if (child instanceof AbstractWidget widget) {
                childJson.addProperty("visible", widget.visible);
                childJson.addProperty("active", widget.active);
                childJson.addProperty("message", boundedText(widget.getMessage().getString()));
                childJson.addProperty("x", widget.getX());
                childJson.addProperty("y", widget.getY());
                childJson.addProperty("width", widget.getWidth());
                childJson.addProperty("height", widget.getHeight());
            } else {
                addChildRectangle(childJson, child);
            }
            children.add(childJson);
        }
        obj.addProperty("children_total", totalChildren);
        obj.addProperty("children_returned", childLimit);
        obj.addProperty("children_truncated", totalChildren > childLimit);
        obj.add("children", children);

        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();
            obj.addProperty("menu_class", menu.getClass().getName());
            obj.addProperty("container_id", menu.containerId);
            JsonArray slots = new JsonArray();
            int totalSlots = menu.slots.size();
            int slotLimit = Math.min(totalSlots, MAX_CONTAINER_SLOTS);
            for (int i = 0; i < slotLimit; i++) {
                Slot slot = menu.slots.get(i);
                JsonObject slotJson = new JsonObject();
                slotJson.addProperty("menu_index", i);
                slotJson.addProperty("slot_index", slot.index);
                slotJson.addProperty("container_slot", slot.getContainerSlot());
                slotJson.addProperty("x", slot.x);
                slotJson.addProperty("y", slot.y);
                slotJson.addProperty("active", slot.isActive());
                slotJson.add("item", itemSnapshot(slot.getItem()));
                slots.add(slotJson);
            }
            obj.addProperty("slots_total", totalSlots);
            obj.addProperty("slots_returned", slotLimit);
            obj.addProperty("slots_truncated", totalSlots > slotLimit);
            obj.add("slots", slots);
        }
        return obj;
    }

    private static void addChildRectangle(JsonObject childJson, GuiEventListener child) {
        try {
            ScreenRectangle rectangle = child.getRectangle();
            childJson.addProperty("x", rectangle.left());
            childJson.addProperty("y", rectangle.top());
            childJson.addProperty("width", rectangle.width());
            childJson.addProperty("height", rectangle.height());
            childJson.addProperty("geometry_available", true);
        } catch (RuntimeException e) {
            childJson.addProperty("geometry_available", false);
        }
    }

    private static JsonObject itemSnapshot(ItemStack stack) {
        JsonObject item = new JsonObject();
        boolean empty = stack == null || stack.isEmpty();
        item.addProperty("empty", empty);
        if (empty) {
            item.addProperty("id", "");
            item.addProperty("count", 0);
            item.addProperty("damage", 0);
            item.addProperty("max_damage", 0);
            return item;
        }
        item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());
        item.addProperty("damage", stack.getDamageValue());
        item.addProperty("max_damage", stack.getMaxDamage());
        return item;
    }

    private static JsonObject vector(Vec3 vector) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", vector.x);
        obj.addProperty("y", vector.y);
        obj.addProperty("z", vector.z);
        return obj;
    }

    private static JsonObject protocolOk() {
        JsonObject obj = ok();
        obj.addProperty("protocol", PROTOCOL_NAME);
        obj.addProperty("schema_version", PROTOCOL_SCHEMA_VERSION);
        return obj;
    }

    private static JsonObject createControlStatus() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject obj = ok();
        obj.addProperty("process_id", ProcessHandle.current().pid());
        obj.addProperty("protocol", PROTOCOL_NAME);
        obj.addProperty("schema_version", PROTOCOL_SCHEMA_VERSION);
        obj.addProperty("run_id", runId());
        obj.addProperty("desktop_name", identityValue(
                "mineclientBridge.desktopName",
                "MINECLIENT_BRIDGE_DESKTOP_NAME"));
        obj.addProperty("runtime_root", identityValue(
                "mineclientBridge.runtimeRoot",
                "MINECLIENT_BRIDGE_RUNTIME_ROOT"));
        obj.addProperty("evidence_root", identityValue(
                "mineclientBridge.evidenceRoot",
                "MINECLIENT_BRIDGE_EVIDENCE_ROOT"));
        obj.addProperty("bridge_running", isRunning());
        obj.addProperty("in_world", mc.level != null && mc.player != null);

        JsonObject world = new JsonObject();
        world.addProperty("present", mc.level != null);
        if (mc.level != null) {
            world.addProperty("dimension", mc.level.dimension().location().toString());
            world.addProperty("game_time", mc.level.getGameTime());
        }
        obj.add("world", world);

        JsonObject player = new JsonObject();
        player.addProperty("present", mc.player != null);
        if (mc.player != null) {
            player.addProperty("name", boundedText(mc.player.getGameProfile().getName()));
            player.addProperty("uuid", mc.player.getUUID().toString());
            player.addProperty("x", mc.player.getX());
            player.addProperty("y", mc.player.getY());
            player.addProperty("z", mc.player.getZ());
            player.addProperty("yaw", mc.player.getYRot());
            player.addProperty("pitch", mc.player.getXRot());
        }
        obj.add("player", player);

        Screen currentScreen = mc.screen;
        JsonObject screen = new JsonObject();
        screen.addProperty("present", currentScreen != null);
        if (currentScreen != null) {
            screen.addProperty("class", currentScreen.getClass().getName());
            screen.addProperty("title", boundedText(currentScreen.getTitle().getString()));
            screen.addProperty("width", currentScreen.width);
            screen.addProperty("height", currentScreen.height);
        }
        obj.add("screen", screen);

        JsonObject window = new JsonObject();
        window.addProperty("width", mc.getWindow().getWidth());
        window.addProperty("height", mc.getWindow().getHeight());
        window.addProperty("screen_width", mc.getWindow().getScreenWidth());
        window.addProperty("screen_height", mc.getWindow().getScreenHeight());
        window.addProperty("gui_width", mc.getWindow().getGuiScaledWidth());
        window.addProperty("gui_height", mc.getWindow().getGuiScaledHeight());
        window.addProperty("gui_scale", mc.getWindow().getGuiScale());
        window.addProperty("fullscreen", mc.getWindow().isFullscreen());
        window.addProperty("active", mc.isWindowActive());
        obj.add("window", window);

        JsonArray heldMappings = new JsonArray();
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (mapping.isDown()) {
                heldMappings.add(mapping.getName());
            }
        }
        obj.add("held_mappings", heldMappings);
        return obj;
    }

    private static byte[] captureFrame() throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null) {
            throw new IOException("Minecraft main render target is unavailable");
        }

        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            byte[] png = image.asByteArray();
            if (png.length > MAX_FRAME_BYTES) {
                throw new FrameTooLargeException(png.length);
            }
            return png;
        }
    }

    private static EndpointResult applyKeyAction(String mappingName, String action) {
        Minecraft mc = Minecraft.getInstance();
        KeyMapping selected = null;
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (mappingName.equals(mapping.getName())) {
                selected = mapping;
                break;
            }
        }

        if (selected == null) {
            return new EndpointResult(404, error("mapping_not_found", "No exact KeyMapping.getName() match"));
        }
        if (action.equals("click") && selected.isUnbound()) {
            return new EndpointResult(409, error("mapping_unbound", "Cannot click an unbound mapping"));
        }

        switch (action) {
            case "down" -> selected.setDown(true);
            case "up" -> selected.setDown(false);
            case "click" -> {
                selected.setDown(true);
                KeyMapping.click(selected.getKey());
                selected.setDown(false);
            }
            default -> throw new IllegalArgumentException("Unsupported key action: " + action);
        }

        JsonObject obj = ok();
        obj.addProperty("mapping", selected.getName());
        obj.addProperty("action", action);
        obj.addProperty("key", selected.saveString());
        obj.addProperty("down", selected.isDown());
        return new EndpointResult(200, obj);
    }

    private static EndpointResult applyRawKeyAction(String keyName, String action) {
        final InputConstants.Key key;
        try {
            key = resolveKeyboardKey(keyName);
        } catch (IllegalArgumentException e) {
            return new EndpointResult(400, error("invalid_key", e.getMessage()));
        }

        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            throw new IllegalStateException("Raw key input must run on the Minecraft thread");
        }
        long window = mc.getWindow().getWindow();
        boolean wasDown = HELD_RAW_KEYS.contains(key);
        int events = 0;
        switch (action) {
            case "down" -> {
                if (!wasDown) {
                    HELD_RAW_KEYS.add(key);
                    mc.keyboardHandler.keyPress(window, key.getValue(), -1, InputConstants.PRESS, 0);
                    events = 1;
                }
            }
            case "up" -> {
                if (wasDown) {
                    mc.keyboardHandler.keyPress(window, key.getValue(), -1, InputConstants.RELEASE, 0);
                    HELD_RAW_KEYS.remove(key);
                    events = 1;
                }
            }
            case "click" -> {
                if (!wasDown) {
                    HELD_RAW_KEYS.add(key);
                    mc.keyboardHandler.keyPress(window, key.getValue(), -1, InputConstants.PRESS, 0);
                    events++;
                }
                mc.keyboardHandler.keyPress(window, key.getValue(), -1, InputConstants.RELEASE, 0);
                HELD_RAW_KEYS.remove(key);
                events++;
            }
            default -> throw new IllegalArgumentException("Unsupported raw key action: " + action);
        }

        JsonObject obj = ok();
        obj.addProperty("key", key.getName());
        obj.addProperty("key_code", key.getValue());
        obj.addProperty("action", action);
        obj.addProperty("was_down", wasDown);
        obj.addProperty("down", HELD_RAW_KEYS.contains(key));
        obj.addProperty("events", events);
        return new EndpointResult(200, obj);
    }

    private static InputConstants.Key resolveKeyboardKey(String input) {
        String keyName = input.trim().toLowerCase(Locale.ROOT);
        if (!keyName.startsWith("key.keyboard.")) {
            keyName = "key.keyboard." + keyName
                    .replace('_', '.')
                    .replace('-', '.')
                    .replace(' ', '.');
        }

        InputConstants.Key key = InputConstants.getKey(keyName);
        if (key.getType() != InputConstants.Type.KEYSYM
                || key.getValue() < 0
                || key.getValue() > MAX_GLFW_KEY_CODE) {
            throw new IllegalArgumentException("key must identify a valid GLFW keyboard key");
        }
        return key;
    }

    private static EndpointResult applyLook(double yawInput, double pitchInput, boolean relative) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return new EndpointResult(409, error("not_in_world"));
        }
        if (Math.abs(yawInput) > 1_000_000.0 || Math.abs(pitchInput) > 1_000_000.0) {
            return new EndpointResult(400, error("look_value_out_of_range"));
        }

        float yaw = relative
                ? mc.player.getYRot() + (float) yawInput
                : (float) yawInput;
        float pitch = relative
                ? mc.player.getXRot() + (float) pitchInput
                : (float) pitchInput;
        yaw = Mth.wrapDegrees(yaw);
        pitch = Mth.clamp(pitch, -90.0F, 90.0F);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setYHeadRot(yaw);
        mc.player.setYBodyRot(yaw);

        JsonObject obj = ok();
        obj.addProperty("yaw", mc.player.getYRot());
        obj.addProperty("pitch", mc.player.getXRot());
        obj.addProperty("relative", relative);
        return new EndpointResult(200, obj);
    }

    private static EndpointResult applyMouseAction(
            double x,
            double y,
            int button,
            String action,
            double scrollY) {
        Minecraft mc = Minecraft.getInstance();
        Screen currentScreen = mc.screen;
        if (currentScreen == null) {
            return new EndpointResult(409, error("no_active_screen"));
        }

        currentScreen.mouseMoved(x, y);
        boolean handled = false;
        boolean pressed = false;
        boolean released = false;
        switch (action) {
            case "move" -> handled = true;
            case "down" -> handled = pressed = currentScreen.mouseClicked(x, y, button);
            case "up", "release" -> handled = released = currentScreen.mouseReleased(x, y, button);
            case "click" -> {
                pressed = currentScreen.mouseClicked(x, y, button);
                released = currentScreen.mouseReleased(x, y, button);
                handled = pressed || released;
            }
            case "scroll" -> handled = currentScreen.mouseScrolled(x, y, 0.0, scrollY);
            default -> throw new IllegalArgumentException("Unsupported mouse action: " + action);
        }

        JsonObject obj = ok();
        obj.addProperty("screen", currentScreen.getClass().getName());
        obj.addProperty("action", action);
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("button", button);
        obj.addProperty("scroll_y", scrollY);
        obj.addProperty("handled", handled);
        if (action.equals("click")) {
            obj.addProperty("pressed", pressed);
            obj.addProperty("released", released);
        }
        return new EndpointResult(200, obj);
    }

    private static EndpointResult applyScreenText(String text, boolean submit) {
        Minecraft mc = Minecraft.getInstance();
        Screen currentScreen = mc.screen;
        if (currentScreen == null) {
            return new EndpointResult(409, error("no_active_screen"));
        }

        if (submit) {
            if (!(currentScreen instanceof ChatScreen chatScreen)) {
                return new EndpointResult(409, error(
                        "submit_requires_chat_screen",
                        "submit=true is supported only for an active ChatScreen"));
            }
            chatScreen.handleChatInput(text, true);
            boolean closed = mc.screen == chatScreen;
            if (closed) {
                mc.setScreen(null);
            }
            JsonObject obj = ok();
            obj.addProperty("screen", chatScreen.getClass().getName());
            obj.addProperty("submitted", true);
            obj.addProperty("closed", closed);
            return new EndpointResult(200, obj);
        }

        GuiEventListener focused = currentScreen.getFocused();
        if (focused == null) {
            return new EndpointResult(409, error("no_focused_widget"));
        }
        int handledCharacters = 0;
        for (int i = 0; i < text.length(); i++) {
            if (currentScreen.charTyped(text.charAt(i), 0)) {
                handledCharacters++;
            }
        }

        JsonObject obj = ok();
        obj.addProperty("screen", currentScreen.getClass().getName());
        obj.addProperty("focused", focused.getClass().getName());
        obj.addProperty("characters", text.length());
        obj.addProperty("handled_characters", handledCharacters);
        obj.addProperty("handled", handledCharacters > 0);
        obj.addProperty("submitted", false);
        return new EndpointResult(200, obj);
    }

    private static EndpointResult applyCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return new EndpointResult(409, error("not_in_world"));
        }

        mc.getConnection().sendCommand(command);
        JsonObject obj = ok();
        obj.addProperty("submitted", true);
        return new EndpointResult(200, obj);
    }

    private static <T> T callOnMinecraftThread(Callable<T> operation, long timeoutSeconds)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Minecraft.getInstance().execute(() -> {
                if (result.isDone()) {
                    return;
                }
                try {
                    result.complete(operation.call());
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException | IllegalStateException e) {
            result.completeExceptionally(e);
        }
        try {
            return result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            result.cancel(false);
            throw e;
        }
    }

    private static boolean requireControlAccess(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!path.equals(exchange.getRequestURI().getPath())) {
            respondJson(exchange, 404, error("unknown_endpoint"));
            return false;
        }

        InetAddress remoteAddress = exchange.getRemoteAddress().getAddress();
        if (remoteAddress == null || !remoteAddress.isLoopbackAddress()) {
            respondJson(exchange, 403, error("loopback_only"));
            return false;
        }
        if (!isBearerAuthorized(exchange)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            respondJson(exchange, 401, error("unauthorized"));
            return false;
        }
        return requireMethod(exchange, method);
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().add("Allow", method.toUpperCase(Locale.ROOT));
        respondJson(exchange, 405, error("method_not_allowed"));
        return false;
    }

    private static boolean isBearerAuthorized(HttpExchange exchange) {
        String expected = config().token();
        if (expected.isBlank()) {
            return false;
        }

        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String supplied = authorization.substring(7);
        return BridgeSecurity.bearerMatches(expected, authorization);
    }

    private static JsonObject readJsonObjectOrRespond(HttpExchange exchange, boolean allowEmpty)
            throws IOException {
        try {
            byte[] bytes = readBody(exchange);
            if (bytes.length == 0) {
                if (allowEmpty) {
                    return new JsonObject();
                }
                throw new RequestException(400, "empty_body", "A JSON object body is required");
            }

            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new RequestException(400, "invalid_json", "Request body is not valid JSON");
            }
            if (!parsed.isJsonObject()) {
                throw new RequestException(400, "invalid_json", "Request body must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (RequestException e) {
            respondRequestFailure(exchange, e);
            return null;
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException, RequestException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long declaredLength = Long.parseLong(contentLength);
                if (declaredLength < 0) {
                    throw new RequestException(400, "invalid_content_length", "Content-Length must not be negative");
                }
                if (declaredLength > MAX_BODY_BYTES) {
                    throw new RequestException(413, "body_too_large",
                            "Request body exceeds " + MAX_BODY_BYTES + " bytes");
                }
            } catch (NumberFormatException e) {
                throw new RequestException(400, "invalid_content_length", "Content-Length is not a number");
            }
        }

        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new RequestException(413, "body_too_large",
                        "Request body exceeds " + MAX_BODY_BYTES + " bytes");
            }
            return bytes;
        }
    }

    private static void respondRequestFailure(HttpExchange exchange, RequestException e) throws IOException {
        respondJson(exchange, e.status(), error(e.code(), e.getMessage()));
    }

    private static void respondMinecraftFailure(HttpExchange exchange, String operationCode, Exception e)
            throws IOException {
        if (e instanceof TimeoutException) {
            respondJson(exchange, 504, error("minecraft_thread_timeout",
                    "Minecraft did not complete the operation within the configured timeout"));
            return;
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            respondJson(exchange, 503, error("request_interrupted"));
            return;
        }

        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof FrameTooLargeException frameTooLarge) {
            respondJson(exchange, 500, error("frame_too_large", frameTooLarge.getMessage()));
            return;
        }

        JsonObject obj = error(operationCode);
        obj.addProperty("detail", exceptionDetail(cause));
        respondJson(exchange, 500, obj);
    }

    private static void respondJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            status = 500;
            bytes = GSON.toJson(error("response_too_large",
                    "JSON response exceeds " + MAX_JSON_BYTES + " bytes"))
                    .getBytes(StandardCharsets.UTF_8);
        }
        respondBytes(exchange, status, "application/json; charset=utf-8", bytes);
    }

    private static void respondBytes(HttpExchange exchange, int status, String contentType, byte[] bytes)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static JsonObject ok() {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        return obj;
    }

    private static JsonObject error(String code) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", false);
        obj.addProperty("error", code);
        return obj;
    }

    private static JsonObject error(String code, String message) {
        JsonObject obj = error(code);
        obj.addProperty("message", boundedText(message));
        return obj;
    }

    private static String requiredString(JsonObject obj, String key) throws RequestException {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new RequestException(400, "invalid_" + key, key + " must be a string");
        }
        String text = value.getAsString();
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new RequestException(400, "invalid_" + key,
                    key + " exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        return text;
    }

    private static double requiredFiniteDouble(JsonObject obj, String key) throws RequestException {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new RequestException(400, "invalid_" + key, key + " must be a number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) {
            throw new RequestException(400, "invalid_" + key, key + " must be finite");
        }
        return number;
    }

    private static double requiredBoundedCoordinate(JsonObject obj, String key) throws RequestException {
        double coordinate = requiredFiniteDouble(obj, key);
        if (Math.abs(coordinate) > MAX_GUI_COORDINATE) {
            throw new RequestException(400, "invalid_" + key,
                    key + " is outside the supported GUI coordinate range");
        }
        return coordinate;
    }

    private static boolean optionalBoolean(JsonObject obj, String key, boolean fallback)
            throws RequestException {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new RequestException(400, "invalid_" + key, key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int optionalInteger(JsonObject obj, String key, int fallback) throws RequestException {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new RequestException(400, "invalid_" + key, key + " must be an integer");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new RequestException(400, "invalid_" + key, key + " must be an integer");
        }
        return (int) number;
    }

    private static double queryDouble(
            HttpExchange exchange,
            String key,
            double fallback,
            double minimum,
            double maximum) throws RequestException {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) return fallback;

        String found = null;
        try {
            for (String part : rawQuery.split("&")) {
                int separator = part.indexOf('=');
                String rawName = separator < 0 ? part : part.substring(0, separator);
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                if (!key.equals(name)) continue;
                if (found != null) {
                    throw new RequestException(400, "duplicate_" + key,
                            key + " must be supplied at most once");
                }
                String rawValue = separator < 0 ? "" : part.substring(separator + 1);
                found = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException e) {
            throw new RequestException(400, "invalid_query", "Query string encoding is invalid");
        }
        if (found == null) return fallback;

        final double value;
        try {
            value = Double.parseDouble(found);
        } catch (NumberFormatException e) {
            throw new RequestException(400, "invalid_" + key, key + " must be a number");
        }
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new RequestException(400, "invalid_" + key,
                    key + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static void validateScreenText(String text) throws RequestException {
        if (text.isEmpty()) {
            throw new RequestException(400, "empty_text", "text must not be empty");
        }
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (codePoint > Character.MAX_VALUE) {
                throw new RequestException(400, "unsupported_character",
                        "The active Screen charTyped API accepts only BMP characters");
            }
            if (Character.isISOControl(codePoint)) {
                throw new RequestException(400, "unsupported_character",
                        "Control characters are not accepted by the text endpoint");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static String normalizeCommand(String input) throws RequestException {
        String command = input.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        if (command.isEmpty()) {
            throw new RequestException(400, "empty_command", "command must not be empty");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new RequestException(400, "invalid_command",
                    "command exceeds " + MAX_COMMAND_LENGTH + " characters");
        }
        for (int offset = 0; offset < command.length();) {
            int codePoint = command.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new RequestException(400, "unsupported_character",
                        "Control characters are not accepted by the command endpoint");
            }
            offset += Character.charCount(codePoint);
        }
        return command;
    }

    private static String runId() {
        String value = identityValue(
                "mineclientBridge.runId",
                "MINECLIENT_BRIDGE_RUN_ID");
        if (value.isBlank()) {
            return "manual";
        }
        return value;
    }

    private static String identityValue(String propertyName, String environmentName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentName);
        }
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private static String boundedText(String value) {
        if (value == null) return "";
        if (value.length() <= MAX_TEXT_LENGTH) return value;
        return value.substring(0, MAX_TEXT_LENGTH);
    }

    private static String exceptionDetail(Throwable error) {
        String message = error.getMessage();
        String detail = error.getClass().getSimpleName();
        if (message != null && !message.isBlank()) {
            detail += ": " + message;
        }
        return boundedText(detail);
    }

    private static void releaseAllOnMinecraftThread() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) {
                releaseAllInputs();
                return;
            }
            callOnMinecraftThread(() -> {
                releaseAllInputs();
                return null;
            }, MINECRAFT_TIMEOUT_SECONDS);
        } catch (Exception e) {
            BridgeLog.LOGGER.warn("Failed to release held input while stopping bridge", e);
        }
    }

    private static int releaseAllInputs() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            throw new IllegalStateException("Input cleanup must run on the Minecraft thread");
        }

        ArrayList<InputConstants.Key> rawKeys = new ArrayList<>(HELD_RAW_KEYS);
        RuntimeException failure = null;
        for (InputConstants.Key key : rawKeys) {
            try {
                mc.keyboardHandler.keyPress(
                        mc.getWindow().getWindow(),
                        key.getValue(),
                        -1,
                        InputConstants.RELEASE,
                        0);
                HELD_RAW_KEYS.remove(key);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        KeyMapping.releaseAll();
        if (failure != null) {
            throw failure;
        }
        return rawKeys.size();
    }

    private record EntityDistance(Entity entity, double distance) {
    }

    private record EndpointResult(int status, JsonObject body) {
    }

    private static final class RequestException extends Exception {
        private final int status;
        private final String code;

        private RequestException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        private int status() {
            return status;
        }

        private String code() {
            return code;
        }
    }

    private static final class FrameTooLargeException extends IOException {
        private FrameTooLargeException(int bytes) {
            super("Captured PNG is " + bytes + " bytes; limit is " + MAX_FRAME_BYTES + " bytes");
        }
    }
}
