package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.audit.AuditPolicy;
import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.WorldWarp;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdentityCommandModuleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void showIncludesAcceptedAndPendingIdentity() throws Exception {
        try (Fixture fixture = fixture()) {
            final WorldIdentitySnapshot pending = identity("11111111-1111-1111-1111-111111111111", 99L);
            fixture.service.classifyLoadedIdentity(pending).join();
            final CapturingSender sender = new CapturingSender();

            fixture.module.execute(sender.sender(), new String[] {"identity", "show", "creative"});

            final String rendered = PlainTextComponentSerializer.plainText().serialize(sender.message());
            assertTrue(rendered.contains("minecraft:creative"));
            assertTrue(rendered.contains("99"));
            assertTrue(rendered.contains("環境：主世界"));
            assertTrue(rendered.contains("狀態：等待同步"));
            assertTrue(rendered.contains("生命週期能力：可管理"));
            assertTrue(rendered.contains("待確認觀察：Paper 鍵=minecraft:creative"));
            assertTrue(rendered.contains("環境=主世界"));
            assertFalse(rendered.contains("SYNC_PENDING"));
        }
    }

    @Test
    void showLocalizesVerifiedIdentityWithoutPendingObservation() throws Exception {
        try (Fixture fixture = fixture()) {
            final CapturingSender sender = new CapturingSender();

            fixture.module.execute(sender.sender(), new String[] {"identity", "show", "creative"});

            final String rendered = PlainTextComponentSerializer.plainText().serialize(sender.message());
            assertTrue(rendered.contains("狀態：已驗證"));
            assertTrue(rendered.contains("生命週期能力：可管理"));
            assertTrue(rendered.contains("待確認觀察：無"));
        }
    }

    @Test
    void showLocalizesExternalOnlyLifecycleCapability() throws Exception {
        try (Fixture fixture = fixture(LifecycleCapability.EXTERNAL_ONLY)) {
            final CapturingSender sender = new CapturingSender();

            fixture.module.execute(sender.sender(), new String[] {"identity", "show", "creative"});

            final String rendered = PlainTextComponentSerializer.plainText().serialize(sender.message());
            assertTrue(rendered.contains("生命週期能力：僅外部管理"));
        }
    }

    @Test
    void manualSyncRequiresAdmissionAndPersistsSuccessAudit() throws Exception {
        try (Fixture fixture = fixture()) {
            final WorldIdentitySnapshot pending = identity("11111111-1111-1111-1111-111111111111", 99L);
            fixture.service.classifyLoadedIdentity(pending).join();
            final CapturingSender sender = new CapturingSender();

            fixture.module.execute(sender.sender(), new String[] {"identity", "sync", "creative"});
            sender.message();
            fixture.executor.submit(() -> null).join();

            assertEquals(IdentityVerificationState.VERIFIED,
                fixture.service.managedWorld("creative").orElseThrow().identityState());
            assertEquals(
                List.of("world.identity.sync.admission", "world.identity.sync.success"),
                fixture.auditEvents.stream().map(AuditEvent::action).toList()
            );
        }
    }

    @Test
    void acceptsReplacementUsingTheExplicitClearWarpsPolicy() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.service.update("creative", metadata -> metadata.withWarp(new WorldWarp(
                "spawn", 1, 64, 2, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
            ))).join();
            final WorldIdentitySnapshot replacement = identity("22222222-2222-2222-2222-222222222222", 42L);
            fixture.service.classifyLoadedIdentity(replacement).join();
            final CapturingSender sender = new CapturingSender();

            fixture.module.execute(sender.sender(), new String[] {
                "identity", "accept-replacement", "creative", "confirm", "clear-warps"
            });
            sender.message();

            final WorldMetadata accepted = fixture.service.managedWorld("creative").orElseThrow();
            assertEquals(replacement, accepted.identity());
            assertTrue(accepted.warps().isEmpty());
            assertTrue(PlainTextComponentSerializer.plainText().serialize(sender.message())
                .contains("世界傳送點處理方式：清除世界傳送點"));
        }
    }

    @Test
    void abandonsConflictMetadataWithoutDeletingIt() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.service.classifyLoadedIdentity(
                identity("22222222-2222-2222-2222-222222222222", 42L)
            ).join();
            final CapturingSender sender = new CapturingSender();

            fixture.module.execute(sender.sender(), new String[] {"identity", "abandon", "creative", "confirm"});
            sender.message();

            assertEquals(WorldManagementState.DETACHED,
                fixture.service.metadataWorld("creative").orElseThrow().managementState());
        }
    }

    private Fixture fixture() {
        return fixture(LifecycleCapability.MANAGED);
    }

    private Fixture fixture(final LifecycleCapability capability) {
        final PluginIoExecutor executor = new PluginIoExecutor("IdentityCommandTest");
        final List<AuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        final AuditService auditService = new AuditService(
            executor,
            auditEvents::add,
            Logger.getLogger("IdentityCommandTest"),
            AuditPolicy.BEST_EFFORT,
            Map.of(
                "world.identity.sync.admission", AuditPolicy.STRICT,
                "world.identity.accept-replacement.admission", AuditPolicy.STRICT,
                "world.identity.abandon.admission", AuditPolicy.STRICT
            )
        );
        final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
        repository.create(WorldMetadata.createDefault(
            "creative", identity("11111111-1111-1111-1111-111111111111", 42L),
            capability, Optional.empty(), true
        ));
        final WorldManagementService service = new WorldManagementService(
            executor, repository, new WorldRegistry(), auditService
        );
        service.load().join();
        final InputStream bundled = IdentityCommandModuleTest.class.getResourceAsStream("/messages_zh_TW.yml");
        final MessageService messages = MessageService.load(
            temporaryDirectory,
            "zh_TW",
            java.util.Objects.requireNonNull(bundled),
            ignored -> { }
        );
        return new Fixture(
            executor,
            service,
            auditEvents,
            new IdentityCommandModule(
                service,
                new WorldNameValidator(),
                auditService,
                messages,
                new CommandMessageSender(new ImmediateDispatcher())
            )
        );
    }

    private static WorldIdentitySnapshot identity(final String uuid, final long seed) {
        return new WorldIdentitySnapshot(
            "minecraft:creative", UUID.fromString(uuid), WorldEnvironment.NORMAL, seed, true
        );
    }

    private record Fixture(
        PluginIoExecutor executor,
        WorldManagementService service,
        List<AuditEvent> auditEvents,
        IdentityCommandModule module
    ) implements AutoCloseable {
        @Override
        public void close() {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static final class CapturingSender {
        private final CompletableFuture<Component> message = new CompletableFuture<>();
        private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("hasPermission")) return true;
                if (method.getName().equals("getName")) return "console";
                if (method.getName().equals("sendMessage") && arguments != null) {
                    for (final Object argument : arguments) {
                        if (argument instanceof Component component) message.complete(component);
                    }
                }
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return defaultValue(method.getReturnType());
            }
        );

        CommandSender sender() {
            return sender;
        }

        Component message() throws Exception {
            return message.get(2, TimeUnit.SECONDS);
        }
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
}