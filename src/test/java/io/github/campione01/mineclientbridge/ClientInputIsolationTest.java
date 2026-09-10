package io.github.campione01.mineclientbridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientInputIsolationTest {
    private String originalEnabled;
    private String originalDesktop;

    @BeforeEach
    void configureIsolatedProcess() {
        originalEnabled = System.getProperty("mineclientBridge.isolatedInput");
        originalDesktop = System.getProperty("mineclientBridge.desktopName");
        System.setProperty("mineclientBridge.isolatedInput", "true");
        ClientInputIsolation.releaseAllKeys();
        ClientInputIsolation.setClipboard("");
    }

    @AfterEach
    void restoreProperties() {
        restore("mineclientBridge.isolatedInput", originalEnabled);
        restore("mineclientBridge.desktopName", originalDesktop);
        ClientInputIsolation.releaseAllKeys();
        ClientInputIsolation.setClipboard("");
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test
    void isolatedProcessSuppressesNativeCursorAndCallbacks() {
        var before = ClientInputIsolation.statistics();
        assertTrue(ClientInputIsolation.suppressCursorOperation());
        assertTrue(ClientInputIsolation.suppressRawMouseUpdate());
        assertFalse(ClientInputIsolation.acceptsInputCallback());
        var after = ClientInputIsolation.statistics();
        assertEquals(before.suppressedCursorOperations() + 1, after.suppressedCursorOperations());
        assertEquals(before.suppressedRawMouseUpdates() + 1, after.suppressedRawMouseUpdates());
        assertEquals(before.suppressedNativeCallbacks() + 1, after.suppressedNativeCallbacks());
    }

    @Test
    void nativeRegistrationIsRejectedEvenInsideReentrantSyntheticDispatch() {
        long before = ClientInputIsolation.statistics().suppressedNativeRegistrations();
        ClientInputIsolation.syntheticDispatch(() -> {
            assertTrue(ClientInputIsolation.suppressNativeCallbackRegistration());
            ClientInputIsolation.syntheticDispatch(() ->
                    assertTrue(ClientInputIsolation.suppressNativeCallbackRegistration()));
            assertTrue(ClientInputIsolation.acceptsInputCallback());
        });
        assertEquals(before + 2, ClientInputIsolation.statistics().suppressedNativeRegistrations());
        System.setProperty("mineclientBridge.isolatedInput", "false");
        assertFalse(ClientInputIsolation.suppressNativeCallbackRegistration());
    }

    @Test
    void syntheticDispatchIsNestedExceptionSafeAndThreadLocal() throws Exception {
        assertFalse(ClientInputIsolation.isSyntheticDispatch());
        ClientInputIsolation.syntheticDispatch(() -> {
            assertTrue(ClientInputIsolation.acceptsInputCallback());
            ClientInputIsolation.syntheticDispatch(() -> assertTrue(ClientInputIsolation.isSyntheticDispatch()));
            assertTrue(ClientInputIsolation.isSyntheticDispatch());
            assertThrows(IllegalStateException.class, () -> ClientInputIsolation.syntheticDispatch(() -> {
                throw new IllegalStateException("expected");
            }));
            assertTrue(ClientInputIsolation.isSyntheticDispatch());
        });
        assertFalse(ClientInputIsolation.isSyntheticDispatch());
        AtomicBoolean otherThreadDispatch = new AtomicBoolean(true);
        Thread thread = new Thread(() -> otherThreadDispatch.set(ClientInputIsolation.isSyntheticDispatch()));
        ClientInputIsolation.syntheticDispatch(() -> thread.start());
        thread.join();
        assertFalse(otherThreadDispatch.get());
    }

    @Test
    void modifiersAndPollingReadOnlyVirtualHeldKeys() {
        assertFalse(ClientInputIsolation.isKeyDown(65));
        ClientInputIsolation.setKeyDown(65, true);
        ClientInputIsolation.setKeyDown(340, true);
        ClientInputIsolation.setKeyDown(345, true);
        assertTrue(ClientInputIsolation.isKeyDown(65));
        assertEquals(3, ClientInputIsolation.modifiers());
        ClientInputIsolation.setKeyDown(340, false);
        assertEquals(2, ClientInputIsolation.modifiers());
        ClientInputIsolation.releaseAllKeys();
        assertEquals(0, ClientInputIsolation.modifiers());
        assertEquals(0, ClientInputIsolation.statistics().heldKeys());
    }

    @Test
    void clipboardIsProcessLocalAndDoesNotRequireAnOperatingSystemClipboard() {
        ClientInputIsolation.setClipboard("background-client-only");
        assertEquals("background-client-only", ClientInputIsolation.getClipboard());
        assertThrows(NullPointerException.class, () -> ClientInputIsolation.setClipboard(null));
    }

    @Test
    void ordinaryClientsRetainNativeBehaviorAndMalformedConfigurationFails() {
        System.setProperty("mineclientBridge.isolatedInput", "false");
        assertFalse(ClientInputIsolation.enabled());
        assertFalse(ClientInputIsolation.suppressCursorOperation());
        assertTrue(ClientInputIsolation.acceptsInputCallback());
        System.setProperty("mineclientBridge.isolatedInput", "invalid");
        assertThrows(IllegalArgumentException.class, ClientInputIsolation::enabled);
    }
}
