package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.module.ModuleId;
import java.util.List;
import java.util.Objects;

record CommandAccess(ModuleId module, CommandPermissionMode permissionMode, List<String> permissions) {

    CommandAccess {
        Objects.requireNonNull(permissionMode, "permissionMode");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (permissionMode == CommandPermissionMode.INHERIT) {
            if (module != null || !permissions.isEmpty()) {
                throw new IllegalArgumentException("Inherited command access cannot declare a module or permissions.");
            }
        } else if (permissionMode == CommandPermissionMode.HELP) {
            if (module != null || !permissions.equals(List.of(
                "worldmanagement.command.help",
                "worldmanagement.command.help.all"
            ))) {
                throw new IllegalArgumentException("Help command access must declare the help permissions without a module.");
            }
        } else {
            Objects.requireNonNull(module, "module");
            if (permissions.isEmpty() || permissions.stream().anyMatch(permission -> permission == null || permission.isBlank())) {
                throw new IllegalArgumentException("Command access requires non-blank permissions.");
            }
        }
    }

    CommandAccess(final ModuleId module, final List<String> permissions) {
        this(module, CommandPermissionMode.ANY, permissions);
    }

    static CommandAccess inherit() {
        return new CommandAccess(null, CommandPermissionMode.INHERIT, List.of());
    }

    static CommandAccess help() {
        return new CommandAccess(null, CommandPermissionMode.HELP, List.of(
            "worldmanagement.command.help",
            "worldmanagement.command.help.all"
        ));
    }
}