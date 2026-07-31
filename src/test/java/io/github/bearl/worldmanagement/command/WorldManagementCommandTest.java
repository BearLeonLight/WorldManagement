package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.audit.AuditPolicy;
import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.StorageConfiguration;
import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.storage.StorageMigrationService;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.lifecycle.PaperWorldStorageGateway;
import io.github.bearl.worldmanagement.world.lifecycle.WorldDirectoryRemover;
import io.github.bearl.worldmanagement.world.lifecycle.WorldLifecycleCoordinator;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import io.github.bearl.worldmanagement.world.lifecycle.WorldStorageGateway;
import io.github.bearl.worldmanagement.warp.WarpService;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldManagementCommandTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void strictAuditRejectionLeavesDetachedMetadataUntouched() {
        final PluginIoExecutor executor = new PluginIoExecutor("CommandTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.remove("creative").join();
            final ImmediateDispatcher dispatcher = new ImmediateDispatcher();
            final AuditService audit = new AuditService(
                executor,
                event -> { throw new StorageException("simulated audit failure"); },
                Logger.getLogger("CommandTest"),
                AuditPolicy.BEST_EFFORT,
                Map.of("world.remove", AuditPolicy.STRICT)
            );
            final WorldLifecycleCoordinator lifecycle = new WorldLifecycleCoordinator(
                new EmptyRuntimeGateway(), metadata, true, executor,
                new PaperWorldStorageGateway(
                    temporaryDirectory, temporaryDirectory, new WorldNameValidator(),
                    new WorldDirectoryRemover(new WorldNameValidator())
                ),
                Duration.ZERO, dispatcher, Optional.empty()
            );
            final InputStream messagesResource = WorldManagementCommandTest.class
                .getResourceAsStream("/messages_zh_TW.yml");
            final MessageService messages = MessageService.load(
                temporaryDirectory, "zh_TW", java.util.Objects.requireNonNull(messagesResource), ignored -> { }
            );
            final WorldManagementCommand command = new WorldManagementCommand(
                metadata,
                new WorldNameValidator(),
                dispatcher,
                lifecycle,
                new WarpService(metadata, new WorldAccessPolicy()),
                (playerId, worldName, warp) -> CompletableFuture.completedFuture(false),
                (playerId, worldName, coordinates) -> CompletableFuture.completedFuture(false),
                audit,
                true,
                5,
                new StorageMigrationService(
                    executor, StorageConfiguration.defaults(), Map.of(), temporaryDirectory
                ),
                messages,
                new CommandMessageSender(dispatcher),
                new OnlinePlayerSnapshot()
            );

            command.execute(permittedSender(), new String[] {"remove", "creative", "purge", "confirm"});
            executor.submit(() -> null).join();

            assertTrue(metadata.detachedWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static CommandSender permittedSender() {
        return (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "hasPermission" -> true;
                case "getName" -> "console";
                case "equals" -> instance == arguments[0];
                case "hashCode" -> System.identityHashCode(instance);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }

    private static final class ImmediateDispatcher implements WorldThreadDispatcher {
        @Override
        public void executeGlobal(final Runnable task) { task.run(); }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() { }
    }

    private static final class EmptyRuntimeGateway implements WorldRuntimeGateway {
        @Override
        public boolean canMutateWorldsNow() { return true; }

        @Override
        public LifecycleWorld create(
            final String worldName,
            final WorldEnvironment environment,
            final WorldType type,
            final Long seed
        ) { return null; }

        @Override
        public LoadResult loadUnmanaged(final String worldName, final WorldEnvironment environment) {
            return LoadResult.failed();
        }

        @Override
        public LoadResult load(final WorldStorageGateway.LoadClaim claim) { return LoadResult.failed(); }

        @Override
        public boolean unload(final LifecycleWorld world, final boolean save) { return false; }

        @Override
        public java.util.Optional<LifecycleWorld> findWorld(
            final io.github.bearl.worldmanagement.world.VerifiedWorldRef expected
        ) { return java.util.Optional.empty(); }

        @Override
        public java.util.Optional<LifecycleWorld> findLoadedWorldById(final String worldId) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<LifecycleWorld> findWorldByPaperKey(final String paperKey) {
            return java.util.Optional.empty();
        }

        @Override
        public int playerCount(final LifecycleWorld world) { return 0; }

        @Override
        public CompletableFuture<Boolean> teleportPlayersToWorld(
            final LifecycleWorld source,
            final LifecycleWorld target
        ) { return CompletableFuture.completedFuture(false); }
    }
}