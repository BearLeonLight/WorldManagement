package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.ModuleManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Static command visibility policy. Dynamic world and warp authorization stays in command handlers. */
public final class CommandAccessPolicy {

    private static final String HELP_PERMISSION = "worldmanagement.command.help";
    private static final String HELP_ALL_PERMISSION = "worldmanagement.command.help.all";

    private final AtomicReference<ModuleManager> moduleManager = new AtomicReference<>();
    private final AtomicReference<Boolean> helpPlayersEnabled = new AtomicReference<>(true);

    public void initialize(final ModuleManager manager) {
        moduleManager.set(Objects.requireNonNull(manager, "manager"));
    }

    public void initializeHelpAccess(final boolean playersEnabled) {
        helpPlayersEnabled.set(playersEnabled);
    }

    public boolean helpAllowed(final CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        return !(sender instanceof Player)
            || helpPlayersEnabled.get()
            || sender.hasPermission(HELP_PERMISSION)
            || sender.hasPermission(HELP_ALL_PERMISSION);
    }

    public Predicate<CommandSourceStack> command(final ModuleId module, final String permission) {
        return source -> source != null && enabled(module) && source.getSender().hasPermission(permission);
    }

    public Predicate<CommandSourceStack> anyCommand(final ModuleId module, final String... permissions) {
        return source -> {
            if (source == null || !enabled(module)) {
                return false;
            }
            for (final String permission : permissions) {
                if (source.getSender().hasPermission(permission)) {
                    return true;
                }
            }
            return false;
        };
    }

    public Predicate<CommandSourceStack> allCommands(final ModuleId module, final String... permissions) {
        return source -> {
            if (source == null || !enabled(module)) {
                return false;
            }
            for (final String permission : permissions) {
                if (!source.getSender().hasPermission(permission)) {
                    return false;
                }
            }
            return true;
        };
    }

    boolean visible(final CommandAccess access, final CommandSender sender, final boolean ignoreCommandPermissions) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(sender, "sender");
        if (access.permissionMode() == CommandPermissionMode.INHERIT) {
            throw new IllegalArgumentException("Help visibility requires resolved command access.");
        }
        if (access.permissionMode() == CommandPermissionMode.HELP) {
            return ignoreCommandPermissions || helpAllowed(sender);
        }
        if (!enabled(access.module())) {
            return false;
        }
        if (ignoreCommandPermissions) {
            return true;
        }
        return switch (access.permissionMode()) {
            case HELP -> throw new IllegalStateException("Help access must be handled before command permissions.");
            case ANY -> access.permissions().stream().anyMatch(sender::hasPermission);
            case ALL -> access.permissions().stream().allMatch(sender::hasPermission);
            case INHERIT -> throw new IllegalStateException("Resolved access cannot inherit.");
        };
    }

    Predicate<CommandSourceStack> helpCommand() {
        return source -> source != null && helpAllowed(source.getSender());
    }

    private boolean enabled(final ModuleId module) {
        final ModuleManager manager = moduleManager.get();
        return manager == null || manager.enabled(module);
    }
}