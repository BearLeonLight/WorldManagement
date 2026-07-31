package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.ModuleManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Static command visibility policy. Dynamic world and warp authorization stays in command handlers. */
public final class CommandAccessPolicy {

    private final AtomicReference<ModuleManager> moduleManager = new AtomicReference<>();

    public void initialize(final ModuleManager manager) {
        moduleManager.set(Objects.requireNonNull(manager, "manager"));
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

    private boolean enabled(final ModuleId module) {
        final ModuleManager manager = moduleManager.get();
        return manager == null || manager.enabled(module);
    }
}