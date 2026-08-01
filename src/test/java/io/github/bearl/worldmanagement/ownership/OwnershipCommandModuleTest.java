package io.github.bearl.worldmanagement.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bearl.worldmanagement.audit.AuditPolicy;
import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.command.OnlinePlayerSnapshot;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OwnershipCommandModuleTest {

    private static final String COMMAND_PERMISSION = "worldmanagement.command.owner";
    private static final String ADMIN_PERMISSION = "worldmanagement.admin.ownership.manage";
    private static final String LEGACY_BYPASS = "worldmanagement.bypass.protection";
    private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path temporaryDirectory;

    @Test
    void ownerMutationRequiresOwnershipAdministratorPermission() {
        final PluginIoExecutor executor = new PluginIoExecutor("OwnershipCommandModuleTest");
        try {
            final WorldManagementService metadata = service(executor);
            final OnlinePlayerSnapshot players = new OnlinePlayerSnapshot();
            players.replace(List.of(player(TARGET_ID, "Target")));
            final OwnershipCommandModule module = module(executor, metadata, players);

            module.execute(sender(Set.of(COMMAND_PERMISSION, LEGACY_BYPASS)),
                new String[] {"owner", "set", "creative", "Target"});
            assertEquals(WorldMetadata.SERVER_OWNER, metadata.managedWorld("creative").orElseThrow().owner());

            module.execute(sender(Set.of(COMMAND_PERMISSION, ADMIN_PERMISSION)),
                new String[] {"owner", "set", "creative", "Target"});
            executor.submit(() -> null).join();

            assertEquals(TARGET_ID.toString(), metadata.managedWorld("creative").orElseThrow().owner());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private WorldManagementService service(final PluginIoExecutor executor) {
        final WorldManagementService metadata = new WorldManagementService(
            executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
        );
        metadata.load().join();
        metadata.adopt("creative", true).join();
        return metadata;
    }

    private OwnershipCommandModule module(
        final PluginIoExecutor executor,
        final WorldManagementService metadata,
        final OnlinePlayerSnapshot players
    ) {
        final AuditService audit = new AuditService(
            executor,
            event -> CompletableFuture.completedFuture(null).join(),
            Logger.getLogger("OwnershipCommandModuleTest"),
            AuditPolicy.OFF
        );
        final ImmediateDispatcher dispatcher = new ImmediateDispatcher();
        return new OwnershipCommandModule(
            metadata,
            new WorldNameValidator(),
            dispatcher,
            audit,
            5,
            players,
            messages(),
            new CommandMessageSender(dispatcher)
        );
    }

    private MessageService messages() {
        final InputStream bundled = getClass().getResourceAsStream("/messages_zh_TW.yml");
        return MessageService.load(
            temporaryDirectory, "zh_TW", java.util.Objects.requireNonNull(bundled), ignored -> { }
        );
    }

    private static CommandSender sender(final Set<String> permissions) {
        return (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "hasPermission" -> permissions.contains(arguments[0]);
                case "getName" -> "Administrator";
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Player player(final UUID playerId, final String name) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> name;
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
        public void executeGlobal(final Runnable task) {
            task.run();
        }

        @Override
        public void executeAt(final Location location, final Runnable task) {
            task.run();
        }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
        }
    }
}