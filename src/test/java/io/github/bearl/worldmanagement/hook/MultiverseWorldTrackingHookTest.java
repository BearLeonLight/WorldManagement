package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void failsClosedWhenTheInstalledMultiverseApiIsBinaryIncompatible() {
        final FakeGateway gateway = new FakeGateway();
        gateway.tracked = true;
        gateway.removeFailure = new NoClassDefFoundError(
            "org/mvplugins/multiverse/core/world/options/RemoveWorldOptions"
        );
        final MultiverseWorldTrackingHook hook = new MultiverseWorldTrackingHook(gateway);

        assertEquals(WorldTrackingHook.UntrackStatus.FAILED, hook.untrack("creative"));
    }

    @Test
    void rejectsAnApiClassLoaderWithoutSafeUntrackingOptions() {
        final ClassLoader incompatible = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
                if (name.equals("org.mvplugins.multiverse.core.world.options.RemoveWorldOptions")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };

        assertThrows(
            IllegalStateException.class,
            () -> MultiverseWorldTrackingHook.requireCompatibleApi(incompatible)
        );
    }

    @Test
    void rejectsAnApiThatHasEveryMethodButAnIncompatibleDescriptor() {
        assertThrows(
            NoSuchMethodException.class,
            () -> MultiverseWorldTrackingHook.verifyRequiredMethods(
                IncompleteRemoveWorldOptions.class,
                FakeMultiverseWorld.class,
                FakeWorldManager.class,
                FakeAttempt.class,
                FakeTry.class
            )
        );
    }

    private static final class IncompleteRemoveWorldOptions {

        public static IncompleteRemoveWorldOptions world(final FakeMultiverseWorld world) {
            return new IncompleteRemoveWorldOptions();
        }

        public IncompleteRemoveWorldOptions unloadBukkitWorld(final boolean unload) {
            return this;
        }
    }

    private static final class FakeMultiverseWorld {
    }

    private static final class FakeWorldManager {

        public boolean removeWorld(final IncompleteRemoveWorldOptions options) {
            return true;
        }

        public FakeTry saveWorldsConfig() {
            return new FakeTry();
        }
    }

    private static final class FakeAttempt {
    }

    private static final class FakeTry {
    }

    private static final class FakeGateway implements MultiverseWorldTrackingHook.Gateway {
        private boolean tracked;
        private boolean removeSucceeds = true;
        private boolean saveSucceeds = true;
        private boolean removeCalled;
        private boolean saveCalled;
        private boolean keepRuntime;
        private LinkageError removeFailure;

        @Override
        public boolean isTracked(final String worldName) {
            return tracked;
        }

        @Override
        public boolean remove(final String worldName, final boolean keepBukkitRuntime) {
            removeCalled = true;
            keepRuntime = keepBukkitRuntime;
            if (removeFailure != null) {
                throw removeFailure;
            }
            return removeSucceeds;
        }

        @Override
        public boolean save() {
            saveCalled = true;
            return saveSucceeds;
        }
    }
}