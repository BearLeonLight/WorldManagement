package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.bearl.worldmanagement.storage.StorageMigrationService.MigrationStatus;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.lifecycle.PaperWorldStorageGateway;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
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
    void mapsLifecycleDomainStatusesToSpecificMessages() {
        assertEquals("command.create.already-exists", WorldManagementCommand.createResultKey(
            "command.create", false, WorldLifecycleCoordinator.CreateStatus.ALREADY_EXISTS));
        assertEquals("command.create.operation-in-progress", WorldManagementCommand.createResultKey(
            "command.create", false, WorldLifecycleCoordinator.CreateStatus.OPERATION_IN_PROGRESS));
        assertEquals("command.import.already-exists", WorldManagementCommand.createResultKey(
            "command.import", true, WorldLifecycleCoordinator.CreateStatus.ALREADY_EXISTS));
        assertEquals("command.loading", WorldManagementCommand.createResultKey(
            "command.import", false, WorldLifecycleCoordinator.CreateStatus.NOT_READY));
        assertEquals("command.remove.operation-in-progress", WorldManagementCommand.removeResultKey(
            WorldLifecycleCoordinator.RemoveStatus.OPERATION_IN_PROGRESS));
        assertEquals("command.loading", WorldManagementCommand.removeResultKey(
            WorldLifecycleCoordinator.RemoveStatus.NOT_READY));
        assertEquals("command.lifecycle.operation-in-progress", WorldManagementCommand.lifecycleResultKey(
            WorldLifecycleCoordinator.LifecycleStatus.OPERATION_IN_PROGRESS));
        assertEquals("command.loading", WorldManagementCommand.lifecycleResultKey(
            WorldLifecycleCoordinator.LifecycleStatus.NOT_READY));
        assertEquals("command.adopt.operation-in-progress", WorldManagementCommand.adoptResultKey(
            WorldLifecycleCoordinator.AdoptStatus.OPERATION_IN_PROGRESS, false));
        assertEquals("command.storage.source-not-active", WorldManagementCommand.storageResultKey(
            MigrationStatus.SOURCE_NOT_ACTIVE));
        assertEquals("command.storage.same-provider", WorldManagementCommand.storageResultKey(
            MigrationStatus.SAME_PROVIDER));
        assertEquals("command.storage.target-not-configured", WorldManagementCommand.storageResultKey(
            MigrationStatus.TARGET_NOT_CONFIGURED));
        assertEquals("command.storage.target-not-empty", WorldManagementCommand.storageResultKey(
            MigrationStatus.TARGET_NOT_EMPTY));
    }

    @Test
    void loadedWorldListClassifiesOnlyExactMetadataIdentities() {
        final PluginIoExecutor executor = new PluginIoExecutor("CommandLoadedListTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final WorldIdentitySnapshot active = identity(
                "active", "11111111-1111-1111-1111-111111111111"
            );
            final WorldIdentitySnapshot detached = identity(
                "detached", "22222222-2222-2222-2222-222222222222"
            );
            final WorldIdentitySnapshot replaced = identity(
                "replaced", "33333333-3333-3333-3333-333333333333"
            );
            metadata.adopt(active, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.adopt(detached, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.adopt(replaced, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.remove("detached").join();

            final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
            loadedWorlds.replaceAll(java.util.List.of(
                lifecycleWorld(active),
                lifecycleWorld(detached),
                lifecycleWorld(identity("replaced", "44444444-4444-4444-4444-444444444444")),
                lifecycleWorld(identity("unknown", "55555555-5555-5555-5555-555555555555"))
            ));
            final WorldManagementCommand command = command(metadata, executor, loadedWorlds);

            assertEquals(
                java.util.List.of(
                    "active:ACTIVE", "detached:DETACHED", "replaced:UNKNOWN", "unknown:UNKNOWN"
                ),
                command.loadedWorldEntries().stream()
                    .map(entry -> entry.world().name() + ':' + entry.status().name())
                    .toList()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

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

    @Test
    void detachedTeleportSkipsWorldManagementGovernance() {
        final PluginIoExecutor executor = new PluginIoExecutor("CommandTeleportTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.update("creative", world -> world.withAccessControl(
                new AccessControl(AccessMode.WHITELIST, Set.of())
            )).join();
            final ImmediateDispatcher dispatcher = new ImmediateDispatcher();
            final AuditService audit = new AuditService(
                executor,
                event -> { },
                Logger.getLogger("CommandTeleportTest"),
                AuditPolicy.BEST_EFFORT
            );
            final WorldLifecycleCoordinator lifecycle = new WorldLifecycleCoordinator(
                new EmptyRuntimeGateway(), metadata, true, executor,
                new PaperWorldStorageGateway(
                    temporaryDirectory, temporaryDirectory, new WorldNameValidator(),
                    new WorldDirectoryRemover(new WorldNameValidator())
                ),
                Duration.ZERO, dispatcher, Optional.empty()
            );
            final MessageService messages = MessageService.load(
                temporaryDirectory, "zh_TW", java.util.Objects.requireNonNull(
                    WorldManagementCommandTest.class.getResourceAsStream("/messages_zh_TW.yml")
                ), ignored -> { }
            );
            final AtomicInteger teleports = new AtomicInteger();
            final AtomicReference<VerifiedWorldRef> target = new AtomicReference<>();
            final WorldManagementCommand command = new WorldManagementCommand(
                metadata,
                new WorldNameValidator(),
                dispatcher,
                lifecycle,
                new WarpService(metadata, new WorldAccessPolicy()),
                (playerId, worldName, warp) -> CompletableFuture.completedFuture(false),
                (playerId, world, coordinates) -> {
                    teleports.incrementAndGet();
                    target.set(world);
                    return CompletableFuture.completedFuture(true);
                },
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
            final UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final org.bukkit.entity.Player sender = player(playerId);

            command.execute(sender, new String[] {"tp", "self", "creative"});
            assertEquals(0, teleports.get());

            metadata.remove("creative").join();
            command.execute(sender, new String[] {"tp", "self", "creative"});

            assertEquals(1, teleports.get());
            assertEquals(
                VerifiedWorldRef.from(metadata.detachedWorld("creative").orElseThrow()).orElseThrow(),
                target.get()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void loadsUnknownStorageAsDetachedMetadataFromCommand() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("CommandDetachedLoadTest");
        try {
            createPaperStorage("archive");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final ImmediateDispatcher dispatcher = new ImmediateDispatcher();
            final LoadingRuntimeGateway runtime = new LoadingRuntimeGateway();
            final WorldLifecycleCoordinator lifecycle = new WorldLifecycleCoordinator(
                runtime, metadata, true, executor,
                new PaperWorldStorageGateway(
                    temporaryDirectory, temporaryDirectory, new WorldNameValidator(),
                    new WorldDirectoryRemover(new WorldNameValidator())
                ),
                Duration.ZERO, dispatcher, Optional.empty()
            );
            final AuditService audit = new AuditService(
                executor, event -> { }, Logger.getLogger("CommandDetachedLoadTest"),
                AuditPolicy.BEST_EFFORT
            );
            final MessageService messages = MessageService.load(
                temporaryDirectory, "zh_TW", java.util.Objects.requireNonNull(
                    WorldManagementCommandTest.class.getResourceAsStream("/messages_zh_TW.yml")
                ), ignored -> { }
            );
            final WorldManagementCommand command = new WorldManagementCommand(
                metadata, new WorldNameValidator(), dispatcher, lifecycle,
                new WarpService(metadata, new WorldAccessPolicy()),
                (playerId, worldName, warp) -> CompletableFuture.completedFuture(false),
                (playerId, world, coordinates) -> CompletableFuture.completedFuture(false),
                audit, true, 5,
                new StorageMigrationService(
                    executor, StorageConfiguration.defaults(), Map.of(), temporaryDirectory
                ),
                messages, new CommandMessageSender(dispatcher), new OnlinePlayerSnapshot()
            );

            final RespondingSender sender = new RespondingSender();
            command.execute(sender.sender, new String[] {
                "load", "archive", "NETHER", "--detached"
            });
            sender.response.get(2, TimeUnit.SECONDS);

            assertTrue(runtime.loaded);
            assertEquals(WorldRuntimeGateway.WorldEnvironment.NETHER, runtime.environment);
            assertTrue(metadata.managedWorld("archive").isEmpty());
            assertTrue(metadata.detachedWorld("archive").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private void createPaperStorage(final String worldId) throws Exception {
        final Path data = java.nio.file.Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve(worldId).resolve("data")
        );
        java.nio.file.Files.createDirectories(data.resolve("minecraft"));
        java.nio.file.Files.createDirectories(data.resolve("paper"));
        java.nio.file.Files.writeString(data.resolve("minecraft").resolve("world_gen_settings.dat"), "worldgen");
        java.nio.file.Files.writeString(data.resolve("paper").resolve("metadata.dat"), "metadata");
        java.nio.file.Files.writeString(data.resolve("paper").resolve("level_overrides.dat"), "overrides");
    }

    private WorldManagementCommand command(
        final WorldManagementService metadata,
        final PluginIoExecutor executor,
        final LoadedWorldCatalog loadedWorlds
    ) {
        final ImmediateDispatcher dispatcher = new ImmediateDispatcher();
        final WorldLifecycleCoordinator lifecycle = new WorldLifecycleCoordinator(
            new EmptyRuntimeGateway(), metadata, true, executor,
            new PaperWorldStorageGateway(
                temporaryDirectory, temporaryDirectory, new WorldNameValidator(),
                new WorldDirectoryRemover(new WorldNameValidator())
            ),
            Duration.ZERO, dispatcher, Optional.empty()
        );
        final MessageService messages = MessageService.load(
            temporaryDirectory, "zh_TW", java.util.Objects.requireNonNull(
                WorldManagementCommandTest.class.getResourceAsStream("/messages_zh_TW.yml")
            ), ignored -> { }
        );
        return new WorldManagementCommand(
            metadata, new WorldNameValidator(), dispatcher, lifecycle,
            new WarpService(metadata, new WorldAccessPolicy()),
            (playerId, worldName, warp) -> CompletableFuture.completedFuture(false),
            (playerId, world, coordinates) -> CompletableFuture.completedFuture(false),
            new AuditService(
                executor, event -> { }, Logger.getLogger("CommandLoadedListTest"), AuditPolicy.BEST_EFFORT
            ),
            true, 5,
            new StorageMigrationService(
                executor, StorageConfiguration.defaults(), Map.of(), temporaryDirectory
            ),
            messages, new CommandMessageSender(dispatcher), new OnlinePlayerSnapshot(), null,
            new io.github.bearl.worldmanagement.protection.TeleportBypassTokens(), loadedWorlds
        );
    }

    private static WorldIdentitySnapshot identity(final String worldId, final String uuid) {
        return new WorldIdentitySnapshot(
            "minecraft:" + worldId, UUID.fromString(uuid),
            io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
        );
    }

    private static WorldRuntimeGateway.LifecycleWorld lifecycleWorld(
        final WorldIdentitySnapshot identity
    ) {
        return new WorldRuntimeGateway.LifecycleWorld(identity, LifecycleCapability.MANAGED);
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

    private static org.bukkit.entity.Player player(final UUID playerId) {
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(
            org.bukkit.entity.Player.class.getClassLoader(),
            new Class<?>[] {org.bukkit.entity.Player.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "hasPermission" -> "worldmanagement.command.tp".equals(arguments[0]);
                case "getUniqueId" -> playerId;
                case "getName" -> "Player";
                case "equals" -> instance == arguments[0];
                case "hashCode" -> System.identityHashCode(instance);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static final class RespondingSender {
        private final CompletableFuture<Component> response = new CompletableFuture<>();
        private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (instance, method, arguments) -> {
                if (method.getName().equals("hasPermission")) return true;
                if (method.getName().equals("getName")) return "console";
                if (method.getName().equals("sendMessage") && arguments != null) {
                    for (final Object argument : arguments) {
                        if (argument instanceof Component component) response.complete(component);
                    }
                }
                if (method.getName().equals("equals")) return instance == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
                return defaultValue(method.getReturnType());
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
        public boolean save(final LifecycleWorld world) { return true; }

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

        @Override
        public CompletableFuture<Void> beginShutdown() { return CompletableFuture.completedFuture(null); }
    }

    private static final class LoadingRuntimeGateway implements WorldRuntimeGateway {
        private boolean loaded;
        private WorldEnvironment environment;
        private LifecycleWorld world;

        @Override
        public boolean canMutateWorldsNow() { return true; }

        @Override
        public LifecycleWorld create(
            final String worldName, final WorldEnvironment environment,
            final WorldType type, final Long seed
        ) { return null; }

        @Override
        public LoadResult loadUnmanaged(final String worldName, final WorldEnvironment environment) {
            this.environment = environment;
            this.loaded = true;
            this.world = new LifecycleWorld(
                new WorldIdentitySnapshot(
                    "minecraft:" + worldName,
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    io.github.bearl.worldmanagement.world.WorldEnvironment.valueOf(environment.name()),
                    0L, true
                ),
                LifecycleCapability.MANAGED
            );
            return LoadResult.loaded(world, true);
        }

        @Override
        public LoadResult load(final WorldStorageGateway.LoadClaim claim) { return LoadResult.failed(); }

        @Override
        public boolean unload(final LifecycleWorld world, final boolean save) {
            loaded = false;
            return true;
        }

        @Override
        public boolean save(final LifecycleWorld world) { return true; }

        @Override
        public Optional<LifecycleWorld> findWorld(final VerifiedWorldRef expected) {
            return loaded && world != null && world.reference().equals(expected)
                ? Optional.of(world) : Optional.empty();
        }

        @Override
        public Optional<LifecycleWorld> findLoadedWorldById(final String worldId) {
            return loaded && world != null && world.name().equals(worldId)
                ? Optional.of(world) : Optional.empty();
        }

        @Override
        public Optional<LifecycleWorld> findWorldByPaperKey(final String paperKey) {
            return loaded && world != null && world.identity().paperKey().equals(paperKey)
                ? Optional.of(world) : Optional.empty();
        }

        @Override
        public int playerCount(final LifecycleWorld world) { return 0; }

        @Override
        public CompletableFuture<Boolean> teleportPlayersToWorld(
            final LifecycleWorld source, final LifecycleWorld target
        ) { return CompletableFuture.completedFuture(false); }

        @Override
        public CompletableFuture<Void> beginShutdown() { return CompletableFuture.completedFuture(null); }
    }
}