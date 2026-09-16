package io.github.campione01.mineclientbridge;

import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Observes the NeoForge input events the client publishes, so the bridge can report whether a
 * dispatched key or mouse action actually reached mod input handling and whether a mod consumed it.
 *
 * <p>Every listener runs at {@link EventPriority#LOWEST} and still receives cancelled events, so it
 * records the outcome after every other handler has had its turn. It only reads the events.</p>
 */
public final class InputEventProbe {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong MOUSE_BUTTON_PRE_EVENTS = new AtomicLong();
    private static final AtomicLong MOUSE_BUTTON_PRE_CANCELLED = new AtomicLong();
    private static final AtomicLong KEY_EVENTS = new AtomicLong();
    private static final AtomicLong SCROLL_EVENTS = new AtomicLong();
    private static final AtomicLong SCROLL_CANCELLED = new AtomicLong();
    private static final AtomicLong INTERACTION_EVENTS = new AtomicLong();
    private static final AtomicLong INTERACTION_CANCELLED = new AtomicLong();
    private static volatile boolean installed;
    private static volatile Observation lastMouseButton;
    private static volatile Observation lastKey;
    private static volatile Observation lastInteraction;
    private static volatile Observation lastScroll;

    private InputEventProbe() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
                InputEvent.MouseButton.Pre.class, InputEventProbe::onMouseButtonPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
                InputEvent.Key.class, InputEventProbe::onKey);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
                InputEvent.MouseScrollingEvent.class, InputEventProbe::onScroll);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
                InputEvent.InteractionKeyMappingTriggered.class, InputEventProbe::onInteraction);
        installed = true;
    }

    public static boolean installed() {
        return installed;
    }

    private static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        MOUSE_BUTTON_PRE_EVENTS.incrementAndGet();
        if (event.isCanceled()) {
            MOUSE_BUTTON_PRE_CANCELLED.incrementAndGet();
        }
        lastMouseButton = new Observation(
                SEQUENCE.incrementAndGet(),
                "mouse",
                event.getButton(),
                event.getAction(),
                event.getModifiers(),
                event.isCanceled(),
                "");
    }

    private static void onKey(InputEvent.Key event) {
        KEY_EVENTS.incrementAndGet();
        lastKey = new Observation(
                SEQUENCE.incrementAndGet(),
                "keyboard",
                event.getKey(),
                event.getAction(),
                event.getModifiers(),
                false,
                "");
    }

    private static void onScroll(InputEvent.MouseScrollingEvent event) {
        SCROLL_EVENTS.incrementAndGet();
        if (event.isCanceled()) {
            SCROLL_CANCELLED.incrementAndGet();
        }
        lastScroll = new Observation(
                SEQUENCE.incrementAndGet(),
                "scroll",
                -1,
                -1,
                0,
                event.isCanceled(),
                "");
    }

    private static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        INTERACTION_EVENTS.incrementAndGet();
        if (event.isCanceled()) {
            INTERACTION_CANCELLED.incrementAndGet();
        }
        lastInteraction = new Observation(
                SEQUENCE.incrementAndGet(),
                event.isAttack() ? "attack" : event.isUseItem() ? "use_item" : "pick_block",
                -1,
                -1,
                0,
                event.isCanceled(),
                event.getKeyMapping() == null ? "" : event.getKeyMapping().getName());
    }

    public static long mouseButtonSequence() {
        Observation observation = lastMouseButton;
        return observation == null ? 0L : observation.sequence();
    }

    public static long keySequence() {
        Observation observation = lastKey;
        return observation == null ? 0L : observation.sequence();
    }

    public static Observation mouseButtonSince(long sequence, int button, int action) {
        Observation observation = lastMouseButton;
        if (observation == null || observation.sequence() <= sequence) {
            return null;
        }
        return observation.code() == button && observation.action() == action ? observation : null;
    }

    public static long scrollSequence() {
        Observation observation = lastScroll;
        return observation == null ? 0L : observation.sequence();
    }

    public static Observation scrollSince(long sequence) {
        Observation observation = lastScroll;
        return observation == null || observation.sequence() <= sequence ? null : observation;
    }

    public static Observation keySince(long sequence, int key, int action) {
        Observation observation = lastKey;
        if (observation == null || observation.sequence() <= sequence) {
            return null;
        }
        return observation.code() == key && observation.action() == action ? observation : null;
    }

    public static Statistics statistics() {
        return new Statistics(
                installed,
                MOUSE_BUTTON_PRE_EVENTS.get(),
                MOUSE_BUTTON_PRE_CANCELLED.get(),
                KEY_EVENTS.get(),
                SCROLL_EVENTS.get(),
                SCROLL_CANCELLED.get(),
                INTERACTION_EVENTS.get(),
                INTERACTION_CANCELLED.get(),
                lastMouseButton,
                lastKey,
                lastScroll,
                lastInteraction);
    }

    public record Observation(
            long sequence,
            String device,
            int code,
            int action,
            int modifiers,
            boolean cancelled,
            String mapping) {
    }

    public record Statistics(
            boolean installed,
            long mouseButtonEvents,
            long mouseButtonEventsCancelled,
            long keyEvents,
            long scrollEvents,
            long scrollEventsCancelled,
            long interactionEvents,
            long interactionEventsCancelled,
            Observation lastMouseButton,
            Observation lastKey,
            Observation lastScroll,
            Observation lastInteraction) {
    }
}
