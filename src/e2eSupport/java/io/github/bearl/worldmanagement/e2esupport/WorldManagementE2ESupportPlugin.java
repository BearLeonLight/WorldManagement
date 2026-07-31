package io.github.bearl.worldmanagement.e2esupport;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.context.MutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

/** Console-only test fixtures that do not access WorldManagement internals or storage. */
public final class WorldManagementE2ESupportPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private final AtomicReference<WorldLoadFixture> worldLoadFixture = new AtomicReference<>();
    private final AtomicReference<WorldRelocation> worldRelocation = new AtomicReference<>();
    private final AtomicReference<RespawnFixture> respawnFixture = new AtomicReference<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
            Commands.literal("wme2e")
                    .requires(source -> source.getSender() instanceof ConsoleCommandSender)
                .then(Commands.literal("permission")
                    .then(permissionSetTree())
                    .then(permissionClearTree()))
                .then(Commands.literal("player")
                    .then(playerRespawnTree())
                    .then(playerPermissionTree())
                    .then(playerLuckPermsTree()))
                .then(Commands.literal("world")
                    .then(worldLoadRelocateTree()))
                .build()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeBreak(final BlockBreakEvent event) {
        getLogger().info("WM_E2E_EVENT_BREAK player=" + event.getPlayer().getName()
            + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observePlace(final BlockPlaceEvent event) {
        getLogger().info("WM_E2E_EVENT_PLACE player=" + event.getPlayer().getName()
            + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeInteract(final PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) {
            getLogger().info("WM_E2E_EVENT_INTERACT player=" + event.getPlayer().getName()
                + " use-block=" + event.useInteractedBlock().name()
                + " use-item=" + event.useItemInHand().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeContainer(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            getLogger().info("WM_E2E_EVENT_CONTAINER player=" + player.getName()
                + " cancelled=" + event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeJoin(final PlayerJoinEvent event) {
        getLogger().info("WM_E2E_EVENT_JOIN player=" + event.getPlayer().getName()
            + " world=" + event.getPlayer().getWorld().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeChangedWorld(final PlayerChangedWorldEvent event) {
        getLogger().info("WM_E2E_EVENT_CHANGED_WORLD player=" + event.getPlayer().getName()
            + " from=" + event.getFrom().getKey()
            + " to=" + event.getPlayer().getWorld().getKey());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void clearWorldLoadBypass(final PlayerChangedWorldEvent event) {
        final WorldRelocation relocation = worldRelocation.get();
        if (relocation == null
            || !relocation.playerId().equals(event.getPlayer().getUniqueId())
            || !relocation.worldKey().equals(event.getPlayer().getWorld().getKey())
            || !worldRelocation.compareAndSet(relocation, null)) {
            return;
        }
        event.getPlayer().removeAttachment(relocation.bypass());
        getLogger().info("WM_E2E_WORLD_LOAD_BYPASS_CLEARED player=" + event.getPlayer().getName()
            + " world=" + event.getPlayer().getWorld().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observePostRespawn(final PlayerPostRespawnEvent event) {
        getLogger().info("WM_E2E_EVENT_POST_RESPAWN player=" + event.getPlayer().getName()
            + " world=" + event.getPlayer().getWorld().getKey());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void forceRespawnDestination(final PlayerRespawnEvent event) {
        final RespawnFixture fixture = respawnFixture.get();
        if (fixture == null
            || !fixture.playerId().equals(event.getPlayer().getUniqueId())
            || !respawnFixture.compareAndSet(fixture, null)) {
            return;
        }
        final World world = Bukkit.getWorld(fixture.worldKey());
        if (world == null) {
            getLogger().warning("WM_E2E_RESPAWN_DESTINATION_UNAVAILABLE world=" + fixture.worldKey());
            return;
        }
        event.setRespawnLocation(world.getSpawnLocation());
        getLogger().info("WM_E2E_RESPAWN_DESTINATION_FORCED player=" + event.getPlayer().getName()
            + " world=" + fixture.worldKey());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeTeleport(final PlayerTeleportEvent event) {
        getLogger().info("WM_E2E_EVENT_TELEPORT player=" + event.getPlayer().getName()
            + " cause=" + event.getCause().name()
            + " from=" + event.getFrom().getWorld().getKey()
            + " to=" + (event.getTo() == null ? "none" : event.getTo().getWorld().getKey())
            + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void relocateOnWorldLoad(final WorldLoadEvent event) {
        final WorldLoadFixture fixture = worldLoadFixture.get();
        if (fixture == null || !fixture.worldKey().equals(event.getWorld().getKey())) {
            return;
        }
        final Player player = Bukkit.getPlayerExact(fixture.playerName());
        if (player == null || !worldLoadFixture.compareAndSet(fixture, null)) {
            return;
        }
        player.getScheduler().execute(this, () -> {
            final PermissionAttachment bypass = player.addAttachment(this);
            bypass.setPermission("worldmanagement.bypass.protection", true);
            player.recalculatePermissions();
            final WorldRelocation relocation = new WorldRelocation(
                player.getUniqueId(), event.getWorld().getKey(), bypass
            );
            if (!worldRelocation.compareAndSet(null, relocation)) {
                player.removeAttachment(bypass);
                return;
            }
            final boolean bypassed = player.hasPermission("worldmanagement.bypass.protection");
            getLogger().info("WM_E2E_WORLD_LOAD_RELOCATION_ARMED player=" + player.getName()
                + " world=" + event.getWorld().getKey()
                + " bypass=" + bypassed);
            final World target = Bukkit.getWorld(relocation.worldKey());
            final boolean teleported = target != null && player.teleport(target.getSpawnLocation());
            if (!teleported && worldRelocation.compareAndSet(relocation, null)) {
                player.removeAttachment(bypass);
            }
            getLogger().info("WM_E2E_WORLD_LOAD_RELOCATED player=" + player.getName()
                + " world=" + relocation.worldKey()
                + " teleported=" + teleported);
        }, () -> { }, 1L);
    }

    private LiteralArgumentBuilder<CommandSourceStack> permissionSetTree() {
        return Commands.literal("set")
            .then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("permission", StringArgumentType.word())
                    .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> {
                            final String playerName = context.getArgument("player", String.class);
                            final String permission = context.getArgument("permission", String.class);
                            final boolean value = BoolArgumentType.getBool(context, "value");
                            final Player player = Bukkit.getPlayerExact(playerName);
                            if (player == null) {
                                context.getSource().getSender().sendPlainMessage(
                                    "WM_E2E_PERMISSION_PLAYER_UNAVAILABLE player=" + playerName
                                );
                                return 0;
                            }
                            player.getScheduler().execute(this, () -> {
                                final PermissionAttachment attachment = attachments.computeIfAbsent(
                                    player.getUniqueId(), ignored -> player.addAttachment(this)
                                );
                                attachment.setPermission(permission, value);
                                player.updateCommands();
                                context.getSource().getSender().sendPlainMessage(
                                    "WM_E2E_PERMISSION_APPLIED player=" + player.getName()
                                        + " permission=" + permission + " value=" + value + " tree=refreshed"
                                );
                            }, () -> context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_PERMISSION_RETIRED player=" + playerName
                            ), 1L);
                            return 1;
                        }))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> permissionClearTree() {
        return Commands.literal("clear")
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(context -> {
                    final String playerName = context.getArgument("player", String.class);
                    final Player player = Bukkit.getPlayerExact(playerName);
                    if (player == null) {
                        context.getSource().getSender().sendPlainMessage(
                            "WM_E2E_PERMISSION_PLAYER_UNAVAILABLE player=" + playerName
                        );
                        return 0;
                    }
                    player.getScheduler().execute(this, () -> {
                        final PermissionAttachment attachment = attachments.remove(player.getUniqueId());
                        if (attachment != null) {
                            player.removeAttachment(attachment);
                        }
                        player.updateCommands();
                        context.getSource().getSender().sendPlainMessage(
                            "WM_E2E_PERMISSION_CLEARED player=" + player.getName() + " tree=refreshed"
                        );
                    }, () -> context.getSource().getSender().sendPlainMessage(
                        "WM_E2E_PERMISSION_RETIRED player=" + playerName
                    ), 1L);
                    return 1;
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> playerRespawnTree() {
        return Commands.literal("respawn")
            .then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("world", StringArgumentType.word())
                    .executes(context -> {
                        final String playerName = context.getArgument("player", String.class);
                        final String worldName = context.getArgument("world", String.class);
                        final Player player = Bukkit.getPlayerExact(playerName);
                        final NamespacedKey worldKey = NamespacedKey.minecraft(worldName);
                        if (player == null || Bukkit.getWorld(worldKey) == null) {
                            context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_PLAYER_RESPAWN_UNAVAILABLE player=" + playerName + " world=" + worldKey
                            );
                            return 0;
                        }
                        final RespawnFixture fixture = new RespawnFixture(player.getUniqueId(), worldKey);
                        if (!respawnFixture.compareAndSet(null, fixture)) {
                            context.getSource().getSender().sendPlainMessage("WM_E2E_PLAYER_RESPAWN_ALREADY_PENDING");
                            return 0;
                        }
                        player.getScheduler().execute(this, () -> {
                            if (player.getHealth() > 0) {
                                player.setHealth(0);
                            }
                            player.getScheduler().execute(this, () -> {
                                final double health = player.getHealth();
                                final boolean online = player.isOnline();
                                final boolean requested = health <= 0 && online;
                                if (requested) {
                                    player.spigot().respawn();
                                } else {
                                    respawnFixture.compareAndSet(fixture, null);
                                }
                                context.getSource().getSender().sendPlainMessage(
                                    "WM_E2E_PLAYER_RESPAWN_REQUESTED player=" + player.getName()
                                        + " health=" + health
                                        + " online=" + online
                                        + " requested=" + requested
                                );
                            }, () -> {
                                respawnFixture.compareAndSet(fixture, null);
                                context.getSource().getSender().sendPlainMessage(
                                    "WM_E2E_PLAYER_RESPAWN_RETIRED player=" + playerName
                                );
                            }, 1L);
                        }, () -> {
                            respawnFixture.compareAndSet(fixture, null);
                            context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_PLAYER_RESPAWN_RETIRED player=" + playerName
                            );
                        }, 1L);
                        return 1;
                    })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> playerPermissionTree() {
        return Commands.literal("permission")
            .then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("permission", StringArgumentType.word())
                    .executes(context -> {
                        final String playerName = context.getArgument("player", String.class);
                        final String permission = context.getArgument("permission", String.class);
                        final Player player = Bukkit.getPlayerExact(playerName);
                        if (player == null) {
                            context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_PLAYER_PERMISSION_UNAVAILABLE player=" + playerName
                            );
                            return 0;
                        }
                        player.getScheduler().execute(this, () ->
                            context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_PLAYER_PERMISSION player=" + player.getName()
                                    + " world=" + player.getWorld().getName()
                                    + " permission=" + permission
                                    + " value=" + player.hasPermission(permission)
                            ), () -> context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_PLAYER_PERMISSION_RETIRED player=" + playerName
                            ), 1L);
                        return 1;
                    })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> playerLuckPermsTree() {
        return Commands.literal("luckperms")
            .then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("world", StringArgumentType.word())
                    .then(Commands.argument("permission", StringArgumentType.word())
                        .executes(context -> {
                            final String playerName = context.getArgument("player", String.class);
                            final String worldName = context.getArgument("world", String.class);
                            final String permission = context.getArgument("permission", String.class);
                            final Player player = Bukkit.getPlayerExact(playerName);
                            final LuckPerms luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
                            if (player == null || luckPerms == null) {
                                context.getSource().getSender().sendPlainMessage(
                                    "WM_E2E_LUCKPERMS_UNAVAILABLE player=" + playerName
                                );
                                return 0;
                            }
                            player.getScheduler().execute(this, () -> {
                                final User user = luckPerms.getUserManager().getUser(player.getUniqueId());
                                final QueryOptions source = user == null
                                    ? null : luckPerms.getContextManager().getQueryOptions(user).orElse(null);
                                boolean value = false;
                                String failure = "none";
                                try {
                                    if (user != null && source != null) {
                                        final MutableContextSet destination = source.context().mutableCopy();
                                        destination.removeAll(DefaultContextKeys.WORLD_KEY);
                                        destination.add(DefaultContextKeys.WORLD_KEY, worldName);
                                        final QueryOptions options = source.toBuilder().context(destination).build();
                                        value = user.getCachedData().getPermissionData(options)
                                            .checkPermission(permission).asBoolean();
                                    }
                                } catch (final RuntimeException exception) {
                                    failure = exception.getClass().getSimpleName();
                                }
                                context.getSource().getSender().sendPlainMessage(
                                    "WM_E2E_LUCKPERMS player=" + player.getName()
                                        + " source-mode=" + (source == null ? "none" : source.mode().name())
                                        + " source-world=" + (source == null ? "none" : source.context().getValues(DefaultContextKeys.WORLD_KEY))
                                        + " destination=" + worldName
                                        + " permission=" + permission
                                        + " value=" + value
                                        + " failure=" + failure
                                );
                            }, () -> context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_LUCKPERMS_RETIRED player=" + playerName
                            ), 1L);
                            return 1;
                        }))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> worldLoadRelocateTree() {
        return Commands.literal("load-relocate")
            .then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("world", StringArgumentType.word())
                    .executes(context -> {
                        final String playerName = context.getArgument("player", String.class);
                        final String worldName = context.getArgument("world", String.class);
                        final Player player = Bukkit.getPlayerExact(playerName);
                        if (player == null) {
                            context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_WORLD_LOAD_PLAYER_UNAVAILABLE player=" + playerName
                            );
                            return 0;
                        }
                        final NamespacedKey worldKey = NamespacedKey.minecraft(worldName);
                        final WorldLoadFixture fixture = new WorldLoadFixture(playerName, worldKey);
                        if (!worldLoadFixture.compareAndSet(null, fixture)) {
                            context.getSource().getSender().sendPlainMessage("WM_E2E_WORLD_LOAD_ALREADY_PENDING");
                            return 0;
                        }
                        final World world;
                        try {
                            world = Bukkit.createWorld(WorldCreator.ofKey(worldKey));
                        } catch (final RuntimeException failure) {
                            worldLoadFixture.compareAndSet(fixture, null);
                            throw failure;
                        }
                        if (world == null) {
                            worldLoadFixture.compareAndSet(fixture, null);
                            context.getSource().getSender().sendPlainMessage(
                                "WM_E2E_WORLD_LOAD_FAILED world=" + worldKey
                            );
                            return 0;
                        }
                        context.getSource().getSender().sendPlainMessage(
                            "WM_E2E_WORLD_LOAD_COMPLETE world=" + worldKey
                        );
                        return 1;
                    })));
    }

    @Override
    public void onDisable() {
        attachments.forEach((playerId, attachment) -> {
            final Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.removeAttachment(attachment);
            }
        });
        attachments.clear();
        worldLoadFixture.set(null);
        respawnFixture.set(null);
        final WorldRelocation relocation = worldRelocation.getAndSet(null);
        if (relocation != null) {
            final Player player = Bukkit.getPlayer(relocation.playerId());
            if (player != null) {
                player.removeAttachment(relocation.bypass());
            }
        }
    }

    private record WorldLoadFixture(String playerName, NamespacedKey worldKey) { }

    private record WorldRelocation(UUID playerId, NamespacedKey worldKey, PermissionAttachment bypass) { }

    private record RespawnFixture(UUID playerId, NamespacedKey worldKey) { }
}