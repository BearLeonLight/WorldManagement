package io.github.bearl.worldmanagement;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.PluginShutdownCoordinator;
import io.github.bearl.worldmanagement.core.PaperWorldThreadDispatcher;
import io.github.bearl.worldmanagement.core.StartupDiagnostics;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.ConfigService;
import io.github.bearl.worldmanagement.core.LocaleService;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.ConsoleOutput;
import io.github.bearl.worldmanagement.core.DiagnosticFileWriter;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.core.DiagnosticPrivacy;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugLevel;
import io.github.bearl.worldmanagement.hook.LuckPermsHook;
import io.github.bearl.worldmanagement.hook.MultiverseWorldTrackingHook;
import io.github.bearl.worldmanagement.hook.WorldTrackingHook;
import io.github.bearl.worldmanagement.command.WorldManagementCommand;
import io.github.bearl.worldmanagement.command.BrigadierWorldManagementCommand;
import io.github.bearl.worldmanagement.command.CommandAuthorizationSnapshot;
import io.github.bearl.worldmanagement.command.OnlinePlayerSnapshot;
import io.github.bearl.worldmanagement.command.SuggestionCatalog;
import io.github.bearl.worldmanagement.command.WorldManagementCommandComposition;
import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.audit.JsonlAuditLog;
import io.github.bearl.worldmanagement.audit.SqlAuditStore;
import io.github.bearl.worldmanagement.audit.AuditStore;
import io.github.bearl.worldmanagement.config.PluginConfiguration;
import io.github.bearl.worldmanagement.protection.WorldProtectionListener;
import io.github.bearl.worldmanagement.protection.TeleportBypassTokens;
import io.github.bearl.worldmanagement.protection.PlayerIsolationService;
import io.github.bearl.worldmanagement.world.lifecycle.LifecycleFeatureModule;
import io.github.bearl.worldmanagement.world.lifecycle.LifecycleFallbackValidator;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.github.bearl.worldmanagement.world.lifecycle.PaperWorldRuntimeGateway;
import io.github.bearl.worldmanagement.world.lifecycle.PaperWorldIdentity;
import io.github.bearl.worldmanagement.world.lifecycle.WorldLifecycleCoordinator;
import io.github.bearl.worldmanagement.world.lifecycle.PaperWorldStorageGateway;
import io.github.bearl.worldmanagement.world.lifecycle.WorldLifecycleReconciler;
import io.github.bearl.worldmanagement.world.lifecycle.WorldIdentityAutoSynchronizer;
import io.github.bearl.worldmanagement.world.lifecycle.WorldLifecycleListener;
import io.github.bearl.worldmanagement.world.lifecycle.WorldIsolationListener;
import io.github.bearl.worldmanagement.world.lifecycle.WorldGeneratorCatalog;
import io.github.bearl.worldmanagement.world.PaperWorldTeleportGateway;
import io.github.bearl.worldmanagement.world.lifecycle.WorldDirectoryRemover;
import io.github.bearl.worldmanagement.world.lifecycle.WorldLifecycleCoordinator;
import io.github.bearl.worldmanagement.module.ModuleManager;
import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.ownership.OwnershipModule;
import io.github.bearl.worldmanagement.protection.ProtectionModule;
import io.github.bearl.worldmanagement.storage.PluginConfigurationLoader;
import io.github.bearl.worldmanagement.storage.StorageModule;
import io.github.bearl.worldmanagement.storage.StorageRepositoryFactory;
import io.github.bearl.worldmanagement.storage.WorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.StorageMigrationService;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.MetadataMutationGate;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.warp.PaperWarpTeleportGateway;
import io.github.bearl.worldmanagement.warp.DestinationWorldPermissionResolver;
import io.github.bearl.worldmanagement.warp.WarpService;
import io.github.bearl.worldmanagement.warp.WarpModule;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.time.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldManagementPlugin extends JavaPlugin {

    private final WorldManagementCommandComposition commandComposition;
    private PluginIoExecutor ioExecutor;
    private PluginShutdownCoordinator shutdownCoordinator;
    private WorldManagementService worldManagementService;
    private WorldLifecycleCoordinator lifecycleCoordinator;
    private WorldLifecycleReconciler lifecycleReconciler;
    private WorldIdentityAutoSynchronizer identityAutoSynchronizer;
    private PlayerIsolationService playerIsolationService;
    private PaperWorldTeleportGateway worldTeleportGateway;
    private PaperWarpTeleportGateway warpTeleportGateway;
    private BrigadierWorldManagementCommand paperCommand;
    private OnlinePlayerSnapshot onlinePlayers;
    private CommandAuthorizationSnapshot commandAuthorizations;
    private AuditService auditService;
    private CommandMessageSender messageSender;
    private ModuleManager moduleManager;
    private StartupDiagnostics startupDiagnostics;
    private ConsoleOutput consoleOutput;
    private DiagnosticLogger diagnostics;
    private DiagnosticFileWriter diagnosticWriter;
    private final LoadedWorldCatalog loadedWorldCatalog = new LoadedWorldCatalog();
    private final TeleportBypassTokens teleportBypassTokens = new TeleportBypassTokens();
    private final AtomicReference<InitializedStorage> pendingStorage = new AtomicReference<>();

    public WorldManagementPlugin() {
        this(new WorldManagementCommandComposition());
    }

    public WorldManagementPlugin(final WorldManagementCommandComposition commandComposition) {
        this.commandComposition = Objects.requireNonNull(commandComposition, "commandComposition");
    }

    @Override
    public void onEnable() {
        this.startupDiagnostics = new StartupDiagnostics();
        this.consoleOutput = new ConsoleOutput(getLogger().getName());
        consoleOutput.info(startupDiagnostics.headingComponent("Enabling %s %s...".formatted(getName(), getPluginMeta().getVersion())));
        this.ioExecutor = new PluginIoExecutor(getName());
        final WorldThreadDispatcher threadDispatcher = new PaperWorldThreadDispatcher(this);
        this.paperCommand = commandComposition.command();
        commandComposition.suggestions().useLoadedWorldCatalog(loadedWorldCatalog);
        this.messageSender = new CommandMessageSender(threadDispatcher, consoleOutput);
        this.paperCommand.initializeLoadingResponder(sender -> messageSender.send(
            sender,
            net.kyori.adventure.text.Component.text("WorldManagement 正在載入資料。")
        ));
        this.shutdownCoordinator = new PluginShutdownCoordinator(threadDispatcher, ioExecutor, getLogger());
        this.onlinePlayers = commandComposition.onlinePlayers();
        this.onlinePlayers.replace(getServer().getOnlinePlayers());
        getServer().getPluginManager().registerEvents(onlinePlayers, this);
        this.commandAuthorizations = commandComposition.authorizations();
        getServer().getPluginManager().registerEvents(commandAuthorizations, this);
        refreshCommandAuthorizations(threadDispatcher);
        final long storageStartedAt = startupDiagnostics.beginStage();
        consoleOutput.info(startupDiagnostics.headingComponent(startupDiagnostics.stageStarted("Loading configuration and storage")));
        ioExecutor.submit(() -> {
            final PluginConfiguration configuration = new PluginConfigurationLoader().load(getDataFolder().toPath());
            copyDefaultResource("messages_zh_TW.yml");
            final String locale = LocaleService.normalize(configuration.locale());
            final InputStream bundledMessages = getResource("messages_" + locale + ".yml");
            if (bundledMessages == null) {
                throw new IllegalArgumentException("Missing bundled locale resource: messages_" + locale + ".yml");
            }
            final WorldMetadataRepository repository = StorageRepositoryFactory.create(
                configuration.storage(), getDataFolder().toPath()
            );
            AuditStore auditStore = null;
            try {
                auditStore = createAuditStore(configuration);
                return new InitializedStorage(
                    configuration,
                    repository,
                    auditStore,
                    MessageService.load(getDataFolder().toPath(), locale, bundledMessages, getLogger()::warning)
                );
            } catch (final RuntimeException exception) {
                closeAuditStore(auditStore, exception);
                try {
                    repository.close();
                } catch (final RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        }).whenComplete((initializedStorage, throwable) -> {
            if (initializedStorage != null) {
                pendingStorage.set(initializedStorage);
                if (!isEnabled() && pendingStorage.compareAndSet(initializedStorage, null)) {
                    closeInitializedStorage(initializedStorage);
                    return;
                }
            }
            threadDispatcher.executeGlobal(
                () -> completeEnable(threadDispatcher, initializedStorage, throwable, storageStartedAt)
            );
        });
    }

    private void completeEnable(
        final WorldThreadDispatcher threadDispatcher,
        final InitializedStorage initializedStorage,
        final Throwable throwable,
        final long storageStartedAt
    ) {
        if (!isEnabled()) {
            closePendingStorage();
            return;
        }
        if (throwable != null) {
            getLogger().log(Level.SEVERE, startupDiagnostics.stageFailed("configuration and storage loading", storageStartedAt), throwable);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pendingStorage.compareAndSet(initializedStorage, null);
        final PluginConfiguration configuration = initializedStorage.configuration();
        initializeDiagnostics(configuration);
        paperCommand.initializeLoadingResponder(sender -> messageSender.send(
            sender,
            initializedStorage.messages().component("command.loading")
        ));
        consoleOutput.info(startupDiagnostics.successComponent(startupDiagnostics.stageCompleted(
            "Configuration and storage loaded",
            storageStartedAt,
            "%s metadata storage, locale %s".formatted(configuration.storage().provider(), configuration.locale())
        )));
        this.moduleManager = new ModuleManager(List.of(
            new LifecycleFeatureModule(), new WarpModule(), new OwnershipModule(), new ProtectionModule(), new StorageModule()
        ), configuration.modules());
        logStorage(configuration);
        logModules();
        this.auditService = new AuditService(ioExecutor, initializedStorage.auditStore(), getLogger(), configuration.auditPolicy(), Map.of(
            "world.delete", io.github.bearl.worldmanagement.audit.AuditPolicy.STRICT,
            "world.remove", io.github.bearl.worldmanagement.audit.AuditPolicy.STRICT,
            "world.owner", io.github.bearl.worldmanagement.audit.AuditPolicy.STRICT,
            "world.identity.sync.admission", io.github.bearl.worldmanagement.audit.AuditPolicy.STRICT,
            "world.identity.accept-replacement.admission", io.github.bearl.worldmanagement.audit.AuditPolicy.STRICT,
            "world.identity.abandon.admission", io.github.bearl.worldmanagement.audit.AuditPolicy.STRICT,
            "storage.migrate", io.github.bearl.worldmanagement.audit.AuditPolicy.STRICT
        ), diagnostics);
        final MetadataMutationGate mutationGate = new MetadataMutationGate(ioExecutor);
        this.worldManagementService = new WorldManagementService(
            ioExecutor,
            initializedStorage.repository(),
            new WorldRegistry(),
            auditService,
            diagnostics,
            mutationGate
        );
        final long metadataStartedAt = startupDiagnostics.beginStage();
        consoleOutput.info(startupDiagnostics.headingComponent(startupDiagnostics.stageStarted("Loading world metadata")));
        this.worldManagementService.load().whenComplete((unused, loadFailure) -> threadDispatcher.executeGlobal(() -> {
            if (loadFailure != null) {
                getLogger().log(Level.SEVERE, startupDiagnostics.stageFailed("metadata loading", metadataStartedAt), loadFailure);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            final List<PaperWorldIdentity> loadedWorlds;
            try {
                loadedWorlds = getServer().getWorlds().stream().map(PaperWorldIdentity::capture).toList();
                loadedWorldCatalog.replaceAll(loadedWorlds.stream()
                    .map(identity -> new io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway.LifecycleWorld(
                        identity.snapshot(), identity.lifecycleCapability(), identity.bukkitWorldName()
                    ))
                    .toList());
            } catch (final RuntimeException captureFailure) {
                getLogger().log(Level.SEVERE, "Could not capture loaded world identities.", captureFailure);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            classifyLoadedWorlds(loadedWorlds).whenComplete((classified, classificationFailure) ->
                threadDispatcher.executeGlobal(() -> completeMetadataStartup(
                    threadDispatcher,
                    configuration,
                    initializedStorage.messages(),
                    mutationGate,
                    metadataStartedAt,
                    classificationFailure
                ))
            );
        }));
    }

    private java.util.concurrent.CompletableFuture<Void> classifyLoadedWorlds(
        final List<PaperWorldIdentity> loadedWorlds
    ) {
        return java.util.concurrent.CompletableFuture.allOf(loadedWorlds.stream()
            .map(identity -> worldManagementService.classifyLoadedIdentity(
                identity.snapshot(), identity.lifecycleCapability()
            ))
            .toArray(java.util.concurrent.CompletableFuture[]::new));
    }

    private void completeMetadataStartup(
        final WorldThreadDispatcher threadDispatcher,
        final PluginConfiguration configuration,
        final MessageService messages,
        final MetadataMutationGate mutationGate,
        final long metadataStartedAt,
        final Throwable classificationFailure
    ) {
        if (!isEnabled()) {
            return;
        }
        if (classificationFailure != null) {
            getLogger().log(Level.SEVERE, "Could not classify loaded world identities.", classificationFailure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
            consoleOutput.info(startupDiagnostics.successComponent(startupDiagnostics.stageCompleted(
                "World metadata loaded",
                metadataStartedAt,
                "%d managed worlds".formatted(worldManagementService.managedWorlds().size())
            )));
            logMetadata();
            final long servicesStartedAt = startupDiagnostics.beginStage();
            consoleOutput.info(startupDiagnostics.headingComponent(startupDiagnostics.stageStarted("Initializing hooks and services")));
            final PaperWorldStorageGateway storageGateway = new PaperWorldStorageGateway(
                getServer().getLevelDirectory(),
                getServer().getWorldContainer().toPath(),
                new WorldNameValidator(),
                new WorldDirectoryRemover(new WorldNameValidator())
            );
            final java.util.Set<WorldMetadata> metadataWorlds = java.util.stream.Stream.concat(
                worldManagementService.managedWorlds().stream(),
                worldManagementService.detachedWorlds().stream()
            ).collect(java.util.stream.Collectors.toUnmodifiableSet());
            final java.util.Set<WorldMetadata> allMetadataWorlds = java.util.stream.Stream.concat(
                metadataWorlds.stream(), worldManagementService.deletingWorlds().stream()
            ).collect(java.util.stream.Collectors.toUnmodifiableSet());
            ioExecutor.submit(() -> storageGateway.recoverQuarantined(allMetadataWorlds))
                .thenCompose(completedDeletes -> java.util.concurrent.CompletableFuture.allOf(
                    completedDeletes.stream()
                        .map(worldId -> worldManagementService.purge(worldId, null))
                        .toArray(java.util.concurrent.CompletableFuture[]::new)
                ))
                .whenComplete((recovered, recoveryFailure) -> threadDispatcher.executeGlobal(() -> {
                    if (recoveryFailure != null) {
                        getLogger().log(Level.SEVERE, "Could not recover quarantined world storage.", recoveryFailure);
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }
                    completeServiceEnable(
                        threadDispatcher, configuration, messages, servicesStartedAt, storageGateway, mutationGate
                    );
                }));
    }

    private void completeServiceEnable(
        final WorldThreadDispatcher threadDispatcher,
        final PluginConfiguration configuration,
        final MessageService messages,
        final long servicesStartedAt,
        final PaperWorldStorageGateway storageGateway,
        final MetadataMutationGate mutationGate
    ) {
        if (!isEnabled()) {
            return;
        }
        final WorldGeneratorCatalog generatorCatalog = new WorldGeneratorCatalog(
            getServer().getPluginManager(),
            getLogger()::warning,
            commandComposition.suggestions()::replaceGeneratorPlugins,
            commandComposition.suggestions()::replaceBiomeProviderPlugins
        );
        generatorCatalog.refresh("worldmanagement-generator-probe");
        getServer().getPluginManager().registerEvents(generatorCatalog, this);
        final PaperWorldRuntimeGateway runtimeGateway = new PaperWorldRuntimeGateway(
            this, loadedWorldCatalog, generatorCatalog
        );
        final java.util.Optional<io.github.bearl.worldmanagement.world.VerifiedWorldRef> fallback;
        try {
            fallback = new LifecycleFallbackValidator().resolveUsable(
                configuration.fallbackWorld(),
                runtimeGateway::findLoadedWorldById
            );
        } catch (final IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Lifecycle fallback validation failed.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        final ConfigService configService = new ConfigService(configuration);
        final LuckPermsHook luckPermsHook = LuckPermsHook.detect(configuration.hooks().luckPermsEnabled());
        final MultiverseHookConnection multiverseHook = connectMultiverseHook(configuration);
        logHooks(configuration, luckPermsHook, multiverseHook.status());
        final DestinationWorldPermissionResolver destinationPermissions = new DestinationWorldPermissionResolver(
            loadedWorldCatalog,
            luckPermsHook.cachedPermissions()
        );
        consoleOutput.info(startupDiagnostics.sectionComponent("Services"));
        this.worldTeleportGateway = new PaperWorldTeleportGateway(threadDispatcher);
        this.warpTeleportGateway = new PaperWarpTeleportGateway(this, threadDispatcher);
        this.lifecycleCoordinator = new WorldLifecycleCoordinator(
            runtimeGateway,
            worldManagementService,
            configuration.defaultRankSystemEnabled(),
            ioExecutor,
            storageGateway,
            configuration.deletionDelay(),
            threadDispatcher,
            configuration.fallbackWorld(),
            multiverseHook.hook(),
            diagnostics
        );
        final WorldManagementCommand commandHandler = new WorldManagementCommand(
            worldManagementService,
            new WorldNameValidator(),
            threadDispatcher,
            lifecycleCoordinator,
            new WarpService(worldManagementService, new WorldAccessPolicy(), destinationPermissions, diagnostics),
            warpTeleportGateway,
            worldTeleportGateway,
            auditService,
            configuration.warpEnabled(),
            configuration.maximumCustomRanks(),
            new StorageMigrationService(
                ioExecutor,
                configService.configuration().storage(),
                configuration.migrationTargets(),
                getDataFolder().toPath(),
                diagnostics,
                mutationGate
            ),
            messages,
            messageSender,
            onlinePlayers,
            diagnostics,
            teleportBypassTokens
        );
        paperCommand.initialize(commandHandler, moduleManager, worldManagementService);
        this.lifecycleReconciler = new WorldLifecycleReconciler(
            worldManagementService, lifecycleCoordinator, threadDispatcher
        );
        this.identityAutoSynchronizer = new WorldIdentityAutoSynchronizer(
            worldManagementService, loadedWorldCatalog, threadDispatcher
        );
        this.playerIsolationService = new PlayerIsolationService(
            worldManagementService,
            loadedWorldCatalog,
            fallback,
            worldTeleportGateway,
            teleportBypassTokens,
            getLogger()::warning
        );
        for (final org.bukkit.World loadedWorld : getServer().getWorlds()) {
            final PaperWorldIdentity identity = PaperWorldIdentity.capture(loadedWorld);
            if (worldManagementService.resolveRuntimeWorld(
                identity.snapshot(), identity.lifecycleCapability()
            ).status() != io.github.bearl.worldmanagement.world.WorldRuntimeResolution.Status.ISOLATED) {
                continue;
            }
            loadedWorldCatalog.findUniqueByWorldId(identity.snapshot().keyValue())
                .filter(observed -> observed.identity().equals(identity.snapshot()))
                .ifPresent(observed -> playerIsolationService.relocatePlayersInWorld(
                    loadedWorld, observed, threadDispatcher
                ));
        }
        final java.util.function.BiConsumer<org.bukkit.World, io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway.LifecycleWorld>
            isolateExistingPlayers = (world, observed) -> playerIsolationService.forceRelocatePlayersInWorld(
                world, observed, threadDispatcher
            );
        getServer().getPluginManager().registerEvents(
            new WorldLifecycleListener(
                worldManagementService,
                lifecycleCoordinator,
                lifecycleReconciler,
                identityAutoSynchronizer,
                isolateExistingPlayers,
                threadDispatcher,
                loadedWorldCatalog
            ),
            this
        );
        getServer().getPluginManager().registerEvents(
            new WorldIsolationListener(worldManagementService, playerIsolationService),
            this
        );
        lifecycleReconciler.reconcileStartup()
            .whenComplete((unused, failure) -> {
                if (failure != null) {
                    getLogger().log(Level.WARNING, "World desired-state reconciliation did not fully complete.", failure);
                }
            });
        identityAutoSynchronizer.synchronizePendingLoadedWorlds()
            .whenComplete((unused, failure) -> {
                if (failure != null) {
                    getLogger().log(Level.WARNING, "Startup world identity synchronization did not fully complete.", failure);
                }
            });
        consoleOutput.info(startupDiagnostics.detailComponent("Command service: /wm Brigadier tree initialized"));
        consoleOutput.info(startupDiagnostics.detailComponent("Suggestion service: metadata and online-player snapshots initialized"));
        consoleOutput.info(startupDiagnostics.detailComponent("Audit service: %s policy, %s backend".formatted(
            configuration.auditPolicy(), auditBackend(configuration)
        )));
        consoleOutput.info(startupDiagnostics.detailComponent("Online player snapshot listener registered"));
        consoleOutput.info(startupDiagnostics.detailComponent("Command authorization snapshot refresh scheduled"));
        if (moduleManager.enabled(io.github.bearl.worldmanagement.module.ModuleId.PROTECTION)) {
            getServer().getPluginManager().registerEvents(
                new WorldProtectionListener(
                    worldManagementService,
                    new WorldAccessPolicy(),
                    diagnostics,
                    teleportBypassTokens
                ),
                this
            );
            consoleOutput.info(startupDiagnostics.detailComponent("Protection listener registered"));
        } else {
            consoleOutput.info(startupDiagnostics.detailComponent("Protection listener disabled with protection module"));
        }
        consoleOutput.info(startupDiagnostics.detailComponent("Lifecycle isolation listener registered"));
        consoleOutput.info(startupDiagnostics.successComponent(startupDiagnostics.stageCompleted(
            "Services ready",
            servicesStartedAt,
            "commands, suggestions, audit, and listeners initialized"
        )));
        consoleOutput.info(startupDiagnostics.successComponent(startupDiagnostics.startupCompleted(
            "Enabled %s %s with %s metadata storage"
                .formatted(getName(), getPluginMeta().getVersion(), configuration.storage().provider())
        )));
    }

    private void refreshCommandAuthorizations(final WorldThreadDispatcher threadDispatcher) {
        final List<org.bukkit.entity.Player> players = List.copyOf(getServer().getOnlinePlayers());
        final CommandAuthorizationSnapshot.Refresh refresh = commandAuthorizations.beginRefresh(players.size());
        for (final org.bukkit.entity.Player player : players) {
            threadDispatcher.executeFor(
                player,
                () -> refresh.capture(player),
                refresh::skip
            );
        }
        threadDispatcher.executeGlobalLater(
            Duration.ofSeconds(1),
            () -> refreshCommandAuthorizations(threadDispatcher),
            () -> { }
        );
    }

    @Override
    public void onDisable() {
        if (commandAuthorizations != null) {
            commandAuthorizations.beginShutdown();
        }
        teleportBypassTokens.clear();
        if (shutdownCoordinator == null) {
            return;
        }
        getLogger().info(() -> "Disabling %s %s".formatted(getName(), getPluginMeta().getVersion()));
        if (diagnostics != null) {
            diagnostics.basic(DebugArea.IO, "shutdown_started", Map::of);
        }
        final java.util.concurrent.CompletableFuture<Void> pendingOperations = java.util.concurrent.CompletableFuture.allOf(
            lifecycleCoordinator == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : beginShutdown(lifecycleCoordinator::beginShutdown),
            lifecycleReconciler == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : beginShutdown(lifecycleReconciler::beginShutdown),
            identityAutoSynchronizer == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : beginShutdown(identityAutoSynchronizer::beginShutdown),
            playerIsolationService == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : beginShutdown(playerIsolationService::beginShutdown),
            worldTeleportGateway == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : beginShutdown(worldTeleportGateway::beginShutdown),
            warpTeleportGateway == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : beginShutdown(warpTeleportGateway::beginShutdown)
        );
        shutdownCoordinator.shutdown(
            Duration.ofSeconds(3),
            pendingOperations,
            () -> {
                if (worldManagementService != null) {
                    worldManagementService.close();
                }
            },
            () -> {
                if (auditService != null) {
                    auditService.close();
                }
            },
            this::closePendingStorageNow
        ).whenComplete((unused, failure) -> {
            if (failure == null) {
                getLogger().info("WorldManagement terminal shutdown complete.");
            } else {
                getLogger().warning("WorldManagement terminal shutdown failed: " + failure.getMessage());
            }
        });
    }

    static java.util.concurrent.CompletableFuture<Void> beginShutdown(
        final java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> operation
    ) {
        try {
            return Objects.requireNonNull(operation.get(), "Shutdown operation future");
        } catch (final RuntimeException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    private void closePendingStorage() {
        final InitializedStorage initializedStorage = pendingStorage.get();
        if (initializedStorage != null) {
            ioExecutor.execute(() -> {
                if (pendingStorage.compareAndSet(initializedStorage, null)) {
                    closeInitializedStorage(initializedStorage);
                }
            });
        }
    }

    private void closePendingStorageNow() {
        final InitializedStorage initializedStorage = pendingStorage.getAndSet(null);
        if (initializedStorage != null) {
            closeInitializedStorage(initializedStorage);
        }
    }

    private static void closeInitializedStorage(final InitializedStorage initializedStorage) {
        RuntimeException failure = null;
        try {
            initializedStorage.repository().close();
        } catch (final RuntimeException exception) {
            failure = exception;
        }
        try {
            closeAuditStore(initializedStorage.auditStore(), failure);
        } catch (final RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeAuditStore(final AuditStore auditStore, final RuntimeException existingFailure) {
        if (!(auditStore instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (final Exception closeFailure) {
            if (existingFailure != null) {
                existingFailure.addSuppressed(closeFailure);
                return;
            }
            throw closeFailure instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException("Could not close audit store.", closeFailure);
        }
    }

    private void initializeDiagnostics(final PluginConfiguration configuration) {
        if (configuration.debug().level() != DebugLevel.OFF
            && configuration.debug().fileEnabled()
            && !configuration.debug().areas().isEmpty()) {
            this.diagnosticWriter = new DiagnosticFileWriter(
                getDataFolder().toPath(), configuration.debug().file(), getLogger()::warning
            );
            shutdownCoordinator.attachDiagnosticWriter(diagnosticWriter);
        }
        this.diagnostics = new DiagnosticLogger(configuration.debug(), getComponentLogger(), diagnosticWriter);
        diagnostics.basic(DebugArea.STARTUP, "diagnostics_initialized", () -> Map.of(
            "level", configuration.debug().level().name(),
            "console", Boolean.toString(configuration.debug().consoleEnabled()),
            "file", Boolean.toString(configuration.debug().fileEnabled()),
            "areas", configuration.debug().areas().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","))
        ));
        if (configuration.debug().privacy().includeMaskedJdbcEndpoints()) {
            DiagnosticPrivacy.maskJdbcEndpoint(configuration.storage().jdbcUrl()).ifPresent(endpoint ->
                diagnostics.verbose(DebugArea.STORAGE, "storage_endpoint_configured", () -> Map.of(
                    "provider", configuration.storage().provider().name(),
                    "endpoint", endpoint
                ))
            );
        }
    }

    private void copyDefaultResource(final String resourceName) {
        final var target = getDataFolder().toPath().resolve(resourceName);
        if (Files.exists(target)) {
            return;
        }
        try (InputStream resource = getResource(resourceName)) {
            if (resource == null) {
                throw new IllegalStateException("Missing bundled resource: " + resourceName);
            }
            Files.write(target, resource.readAllBytes());
        } catch (final IOException exception) {
            throw new IllegalStateException("Could not create default resource: " + resourceName, exception);
        }
    }

    private AuditStore createAuditStore(final PluginConfiguration configuration) {
        if (configuration.storage().provider() == io.github.bearl.worldmanagement.storage.StorageProvider.YAML) {
            return new JsonlAuditLog(getDataFolder().toPath());
        }
        return new SqlAuditStore(
            configuration.storage().jdbcUrl(),
            configuration.storage().username(),
            configuration.storage().password()
        );
    }

    private void logStorage(final PluginConfiguration configuration) {
        consoleOutput.info(startupDiagnostics.sectionComponent("Storage"));
        consoleOutput.info(startupDiagnostics.detailComponent("Metadata provider: %s".formatted(configuration.storage().provider())));
        consoleOutput.info(startupDiagnostics.detailComponent("Locale messages: %s".formatted(configuration.locale())));
        consoleOutput.info(startupDiagnostics.detailComponent("Audit policy: %s".formatted(configuration.auditPolicy())));
        consoleOutput.info(startupDiagnostics.detailComponent("Audit backend: %s".formatted(auditBackend(configuration))));
    }

    private void logModules() {
        consoleOutput.info(startupDiagnostics.sectionComponent("Modules"));
        for (final ModuleId module : ModuleId.values()) {
            consoleOutput.info(startupDiagnostics.detailComponent("%s: %s".formatted(
                module.name().toLowerCase(Locale.ROOT),
                moduleManager.enabled(module) ? "enabled" : "disabled"
            )));
        }
    }

    private void logMetadata() {
        final List<WorldMetadata> worlds = worldManagementService.managedWorlds();
        consoleOutput.info(startupDiagnostics.sectionComponent("Metadata"));
        if (worlds.isEmpty()) {
            consoleOutput.info(startupDiagnostics.detailComponent("No managed worlds registered"));
            return;
        }
        for (final WorldMetadata world : worlds) {
            consoleOutput.info(startupDiagnostics.detailComponent("World %s: %d ranks, %s access, %d assigned players, %d warps".formatted(
                world.worldName(),
                world.ranks().size(),
                world.accessControl().mode(),
                world.playerRanks().size(),
                world.warps().size()
            )));
        }
    }

    private MultiverseHookConnection connectMultiverseHook(final PluginConfiguration configuration) {
        if (!configuration.hooks().multiverseEnabled()) {
            return new MultiverseHookConnection(WorldTrackingHook.disabled(), "disabled by hooks.yml");
        }
        if (!getServer().getPluginManager().isPluginEnabled("Multiverse-Core")) {
            return new MultiverseHookConnection(WorldTrackingHook.disabled(), "not installed");
        }
        try {
            return new MultiverseHookConnection(MultiverseWorldTrackingHook.connect(), "available");
        } catch (final RuntimeException | LinkageError failure) {
            getLogger().log(Level.WARNING, "Multiverse-Core is installed but its API could not be initialized.", failure);
            return new MultiverseHookConnection(
                worldName -> WorldTrackingHook.UntrackStatus.FAILED,
                "API unavailable"
            );
        }
    }

    private void logHooks(
        final PluginConfiguration configuration,
        final LuckPermsHook luckPermsHook,
        final String multiverseStatus
    ) {
        consoleOutput.info(startupDiagnostics.sectionComponent("Hooks"));
        if (!configuration.hooks().luckPermsEnabled()) {
            consoleOutput.info(startupDiagnostics.detailComponent("LuckPerms: disabled by hooks.yml"));
        } else {
            consoleOutput.info(startupDiagnostics.detailComponent("LuckPerms: %s".formatted(
                luckPermsHook.available() ? "available" : "not installed or service unavailable"
            )));
        }
        consoleOutput.info(startupDiagnostics.detailComponent("Multiverse-Core: " + multiverseStatus));
    }

    private static String auditBackend(final PluginConfiguration configuration) {
        return configuration.storage().provider() == io.github.bearl.worldmanagement.storage.StorageProvider.YAML
            ? "JSONL"
            : "SQL";
    }

    private record InitializedStorage(
        PluginConfiguration configuration,
        WorldMetadataRepository repository,
        AuditStore auditStore,
        MessageService messages
    ) {
    }

    private record MultiverseHookConnection(WorldTrackingHook hook, String status) {
    }
}
