package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class CommandAuthorizationSnapshotTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void capturesPlayerIdentityAndOwnershipAdministratorPermission() {
        final Player owner = player(OWNER_ID, Set.of());
        final Player administrator = player(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            Set.of(CommandAuthorizationSnapshot.OWNERSHIP_ADMIN_PERMISSION)
        );
        final CommandAuthorizationSnapshot snapshot = new CommandAuthorizationSnapshot();

        snapshot.replace(List.of(owner, administrator));

        final CommandAuthorizationSnapshot.Scope ownerScope = snapshot.scope(
            owner, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP
        );
        assertTrue(ownerScope.known());
        assertEquals(OWNER_ID, ownerScope.playerId().orElseThrow());
        assertFalse(ownerScope.managesAllWorlds());
        assertTrue(snapshot.scope(
            administrator, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP
        ).managesAllWorlds());
        assertFalse(snapshot.scope(
            administrator, CommandAuthorizationSnapshot.ManagementArea.WARP
        ).managesAllWorlds());
    }

    @Test
    void failsClosedForUnknownPlayersAndTreatsSystemSendersAsAdministrators() {
        final CommandAuthorizationSnapshot snapshot = new CommandAuthorizationSnapshot();
        final Player unknown = player(OWNER_ID, Set.of(CommandAuthorizationSnapshot.OWNERSHIP_ADMIN_PERMISSION));
        final CommandSender customSender = commandSender();
        final ConsoleCommandSender console = consoleSender();

        assertFalse(snapshot.scope(unknown, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).known());
        assertFalse(snapshot.scope(unknown, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).managesAllWorlds());
        assertFalse(snapshot.scope(customSender, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).known());
        assertTrue(snapshot.scope(console, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).known());
        assertTrue(snapshot.scope(console, CommandAuthorizationSnapshot.ManagementArea.WARP).managesAllWorlds());
    }

    @Test
    void publishesCompletedRefreshOnceAndRejectsStaleRounds() {
        final Player owner = player(OWNER_ID, Set.of());
        final Player administrator = player(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            Set.of(CommandAuthorizationSnapshot.OWNERSHIP_ADMIN_PERMISSION)
        );
        final CommandAuthorizationSnapshot snapshot = new CommandAuthorizationSnapshot();
        snapshot.replace(List.of(owner));
        final CommandAuthorizationSnapshot.Refresh stale = snapshot.beginRefresh(1);
        final CommandAuthorizationSnapshot.Refresh current = snapshot.beginRefresh(2);

        stale.capture(administrator);
        assertTrue(snapshot.scope(owner, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).known());
        current.capture(administrator);
        assertTrue(snapshot.scope(owner, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).known());
        current.skip();

        assertFalse(snapshot.scope(owner, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).known());
        assertTrue(snapshot.scope(administrator, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).managesAllWorlds());
    }

    @Test
    void shutdownInvalidatesPendingAndPublishedAuthorization() {
        final Player administrator = player(
            OWNER_ID, Set.of(CommandAuthorizationSnapshot.OWNERSHIP_ADMIN_PERMISSION)
        );
        final CommandAuthorizationSnapshot snapshot = new CommandAuthorizationSnapshot();
        snapshot.replace(List.of(administrator));
        final CommandAuthorizationSnapshot.Refresh pending = snapshot.beginRefresh(1);

        snapshot.beginShutdown();
        pending.capture(administrator);

        assertFalse(snapshot.scope(administrator, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP).known());
    }

    private static Player player(final UUID playerId, final Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "hasPermission" -> permissions.contains(arguments[0]);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static CommandSender commandSender() {
        return (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private static ConsoleCommandSender consoleSender() {
        return (ConsoleCommandSender) Proxy.newProxyInstance(
            ConsoleCommandSender.class.getClassLoader(),
            new Class<?>[] {ConsoleCommandSender.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}