package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MultiverseWorldTrackingHookTest {

    @Test
    void doesNothingWhenMultiverseDoesNotTrackTheWorld() {
        final FakeGateway gateway = new FakeGateway();
        final MultiverseWorldTrackingHook hook = new MultiverseWorldTrackingHook(gateway);

        assertEquals(WorldTrackingHook.UntrackStatus.NOT_TRACKED, hook.untrack("creative"));
        assertFalse(gateway.removeCalled);
    }

    @Test
    void removesTrackedWorldWithoutUnloadingTheBukkitRuntime() {
        final FakeGateway gateway = new FakeGateway();
        gateway.tracked = true;
        final MultiverseWorldTrackingHook hook = new MultiverseWorldTrackingHook(gateway);

        assertEquals(WorldTrackingHook.UntrackStatus.UNTRACKED, hook.untrack("creative"));
        assertTrue(gateway.removeCalled);
        assertTrue(gateway.keepRuntime);
        assertTrue(gateway.saveCalled);

        gateway.removeSucceeds = false;
        assertEquals(WorldTrackingHook.UntrackStatus.FAILED, hook.untrack("creative"));
    }

    @Test
    void retriesWorldsConfigurationPersistenceBeforeAllowingLifecycleMutation() {
        final FakeGateway gateway = new FakeGateway();
        gateway.tracked = true;
        gateway.saveSucceeds = false;
        final MultiverseWorldTrackingHook hook = new MultiverseWorldTrackingHook(gateway);

        assertEquals(WorldTrackingHook.UntrackStatus.FAILED, hook.untrack("creative"));
        assertTrue(gateway.removeCalled);

        gateway.tracked = false;
        gateway.removeCalled = false;
        gateway.saveSucceeds = true;
        gateway.saveCalled = false;

        assertEquals(WorldTrackingHook.UntrackStatus.UNTRACKED, hook.untrack("creative"));
        assertFalse(gateway.removeCalled);
        assertTrue(gateway.saveCalled);
    }

    private static final class FakeGateway implements MultiverseWorldTrackingHook.Gateway {
        private boolean tracked;
        private boolean removeSucceeds = true;
        private boolean saveSucceeds = true;
        private boolean removeCalled;
        private boolean saveCalled;
        private boolean keepRuntime;

        @Override
        public boolean isTracked(final String worldName) {
            return tracked;
        }

        @Override
        public boolean remove(final String worldName, final boolean keepBukkitRuntime) {
            removeCalled = true;
            keepRuntime = keepBukkitRuntime;
            return removeSucceeds;
        }

        @Override
        public boolean save() {
            saveCalled = true;
            return saveSucceeds;
        }
    }
}