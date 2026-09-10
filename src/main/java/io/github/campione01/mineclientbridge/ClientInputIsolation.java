package io.github.campione01.mineclientbridge;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientInputIsolation {
    private static final String ENABLED_PROPERTY = "mineclientBridge.isolatedInput";
    private static final String ENABLED_ENVIRONMENT = "MINECLIENT_BRIDGE_ISOLATED_INPUT";
    private static final String DESKTOP_PROPERTY = "mineclientBridge.desktopName";
    private static final String DESKTOP_ENVIRONMENT = "MINECLIENT_BRIDGE_DESKTOP_NAME";

    private static final ThreadLocal<Integer> SYNTHETIC_DEPTH = new ThreadLocal<>();
    private static final Set<Integer> HELD_KEYS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong SYNTHETIC_DISPATCHES = new AtomicLong();
    private static final AtomicLong SYNTHETIC_INPUT_CALLBACKS = new AtomicLong();
    private static final AtomicLong SUPPRESSED_NATIVE_CALLBACKS = new AtomicLong();
    private static final AtomicLong SUPPRESSED_NATIVE_REGISTRATIONS = new AtomicLong();
    private static final AtomicLong SUPPRESSED_CURSOR_OPERATIONS = new AtomicLong();
    private static final AtomicLong SUPPRESSED_RAW_MOUSE_UPDATES = new AtomicLong();
    private static final AtomicLong VIRTUAL_KEY_READS = new AtomicLong();
    private static final AtomicLong VIRTUAL_CLIPBOARD_READS = new AtomicLong();
    private static final AtomicLong VIRTUAL_CLIPBOARD_WRITES = new AtomicLong();
    private static volatile String clipboard = "";

    private ClientInputIsolation() {
    }

    public static boolean enabled() {
        String explicit = setting(ENABLED_PROPERTY, ENABLED_ENVIRONMENT);
        if (!explicit.isEmpty()) {
            if ("true".equalsIgnoreCase(explicit)) {
                return true;
            }
            if ("false".equalsIgnoreCase(explicit)) {
                return false;
            }
            throw new IllegalArgumentException(ENABLED_PROPERTY + " must be true or false");
        }
        String desktop = setting(DESKTOP_PROPERTY, DESKTOP_ENVIRONMENT);
        return !desktop.isEmpty() && !"Default".equalsIgnoreCase(desktop);
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null ? "" : value.trim();
    }

    public static void syntheticDispatch(Runnable action) {
        Objects.requireNonNull(action, "action");
        Integer previousDepth = SYNTHETIC_DEPTH.get();
        SYNTHETIC_DEPTH.set(previousDepth == null ? 1 : previousDepth + 1);
        try {
            if (enabled()) {
                SYNTHETIC_DISPATCHES.incrementAndGet();
            }
            action.run();
        } finally {
            if (previousDepth == null) {
                SYNTHETIC_DEPTH.remove();
            } else {
                SYNTHETIC_DEPTH.set(previousDepth);
            }
        }
    }

    public static boolean isSyntheticDispatch() {
        Integer depth = SYNTHETIC_DEPTH.get();
        return depth != null && depth > 0;
    }

    public static boolean acceptsInputCallback() {
        if (!enabled()) {
            return true;
        }
        if (isSyntheticDispatch()) {
            SYNTHETIC_INPUT_CALLBACKS.incrementAndGet();
            return true;
        }
        SUPPRESSED_NATIVE_CALLBACKS.incrementAndGet();
        return false;
    }

    public static boolean suppressCursorOperation() {
        if (!enabled()) {
            return false;
        }
        SUPPRESSED_CURSOR_OPERATIONS.incrementAndGet();
        return true;
    }

    public static boolean suppressNativeCallbackRegistration() {
        if (!enabled()) return false;
        SUPPRESSED_NATIVE_REGISTRATIONS.incrementAndGet();
        return true;
    }

    public static boolean suppressRawMouseUpdate() {
        if (!enabled()) {
            return false;
        }
        SUPPRESSED_RAW_MOUSE_UPDATES.incrementAndGet();
        return true;
    }

    public static void setKeyDown(int key, boolean down) {
        if (key < 0) {
            return;
        }
        if (down) {
            HELD_KEYS.add(key);
        } else {
            HELD_KEYS.remove(key);
        }
    }

    public static boolean isKeyDown(int key) {
        if (enabled()) {
            VIRTUAL_KEY_READS.incrementAndGet();
        }
        return HELD_KEYS.contains(key);
    }

    public static int modifiers() {
        // GLFW's key and modifier values are constants; no GLFW class is loaded here.
        int modifiers = 0;
        if (HELD_KEYS.contains(340) || HELD_KEYS.contains(344)) {
            modifiers |= 1;
        }
        if (HELD_KEYS.contains(341) || HELD_KEYS.contains(345)) {
            modifiers |= 2;
        }
        if (HELD_KEYS.contains(342) || HELD_KEYS.contains(346)) {
            modifiers |= 4;
        }
        if (HELD_KEYS.contains(343) || HELD_KEYS.contains(347)) {
            modifiers |= 8;
        }
        return modifiers;
    }

    public static void releaseAllKeys() {
        HELD_KEYS.clear();
    }

    public static String getClipboard() {
        if (enabled()) {
            VIRTUAL_CLIPBOARD_READS.incrementAndGet();
        }
        return clipboard;
    }

    public static void setClipboard(String value) {
        clipboard = Objects.requireNonNull(value, "value");
        if (enabled()) {
            VIRTUAL_CLIPBOARD_WRITES.incrementAndGet();
        }
    }

    public static Statistics statistics() {
        return new Statistics(
                SYNTHETIC_DISPATCHES.get(),
                SYNTHETIC_INPUT_CALLBACKS.get(),
                SUPPRESSED_NATIVE_CALLBACKS.get(),
                SUPPRESSED_NATIVE_REGISTRATIONS.get(),
                SUPPRESSED_CURSOR_OPERATIONS.get(),
                SUPPRESSED_RAW_MOUSE_UPDATES.get(),
                VIRTUAL_KEY_READS.get(),
                VIRTUAL_CLIPBOARD_READS.get(),
                VIRTUAL_CLIPBOARD_WRITES.get(),
                HELD_KEYS.size());
    }

    public record Statistics(
            long syntheticDispatches,
            long syntheticInputCallbacks,
            long suppressedNativeCallbacks,
            long suppressedNativeRegistrations,
            long suppressedCursorOperations,
            long suppressedRawMouseUpdates,
            long virtualKeyReads,
            long virtualClipboardReads,
            long virtualClipboardWrites,
            int heldKeys) {
    }
}
