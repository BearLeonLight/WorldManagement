package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.module.ModuleId;
import java.lang.reflect.Proxy;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class CommandAccessPolicyTest {

    @Test
    void commandVisibilityFailsClosedWithoutSourceAndRequiresPermission() {
        final CommandAccessPolicy policy = new CommandAccessPolicy();

        assertFalse(policy.command(ModuleId.WARP, "worldmanagement.command.warp").test(null));
        assertFalse(policy.command(ModuleId.WARP, "worldmanagement.command.warp").test(source(false)));
        assertTrue(policy.command(ModuleId.WARP, "worldmanagement.command.warp").test(source(true)));
    }

    @Test
    void allCommandsRequiresEveryPermission() {
        final CommandAccessPolicy policy = new CommandAccessPolicy();

        assertFalse(policy.allCommands(ModuleId.LIFECYCLE, "explicit", "bypass").test(
            source(permission -> permission.equals("explicit"))
        ));
        assertTrue(policy.allCommands(ModuleId.LIFECYCLE, "explicit", "bypass").test(
            source(permission -> true)
        ));
    }

    @Test
    void helpAccessUsesPlayerDefaultAndExplicitPermissions() {
        final CommandAccessPolicy enabled = new CommandAccessPolicy();
        enabled.initializeHelpAccess(true);
        assertTrue(enabled.helpAllowed(sender(Player.class, permission -> false)));

        final CommandAccessPolicy disabled = new CommandAccessPolicy();
        disabled.initializeHelpAccess(false);
        assertFalse(disabled.helpAllowed(sender(Player.class, permission -> false)));
        assertTrue(disabled.helpAllowed(sender(Player.class, permission -> permission.equals("worldmanagement.command.help"))));
        assertTrue(disabled.helpAllowed(sender(Player.class, permission -> permission.equals("worldmanagement.command.help.all"))));
        assertTrue(disabled.helpAllowed(sender(ConsoleCommandSender.class, permission -> false)));
    }

    private static io.papermc.paper.command.brigadier.CommandSourceStack source(final boolean permitted) {
        return source(ignored -> permitted);
    }

    private static io.papermc.paper.command.brigadier.CommandSourceStack source(
        final java.util.function.Predicate<String> permissions
    ) {
        final CommandSender sender = sender(CommandSender.class, permissions);
        return (io.papermc.paper.command.brigadier.CommandSourceStack) Proxy.newProxyInstance(
            io.papermc.paper.command.brigadier.CommandSourceStack.class.getClassLoader(),
            new Class<?>[] {io.papermc.paper.command.brigadier.CommandSourceStack.class},
            (proxy, method, arguments) -> method.getName().equals("getSender") ? sender : null
        );
    }

    @SuppressWarnings("unchecked")
    private static <T extends CommandSender> T sender(
        final Class<T> type,
        final java.util.function.Predicate<String> permissions
    ) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, arguments) -> method.getName().equals("hasPermission")
                ? permissions.test((String) arguments[0])
                : null
        );
    }
}