package io.github.bearl.worldmanagement;

import io.github.bearl.worldmanagement.command.CommandAliasConfiguration;
import io.github.bearl.worldmanagement.command.CommandAliasConfigurationLoader;
import io.github.bearl.worldmanagement.command.WorldManagementCommandComposition;
import io.github.bearl.worldmanagement.module.ModuleId;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/** Registers the immutable command topology before Paper parses datapack commands. */
public final class WorldManagementBootstrap implements PluginBootstrap {

    private final WorldManagementCommandComposition commandComposition = new WorldManagementCommandComposition();

    @Override
    public void bootstrap(final BootstrapContext context) {
        final CommandAliasConfiguration aliases = loadAliases(context);
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(
                commandComposition.command().buildRoot("wm"),
                "Manage WorldManagement worlds.",
                availableAliases(commands, aliases.rootAliases(), context)
            );
            registerModuleAliases(commands, aliases, context);
        });
    }

    @Override
    public JavaPlugin createPlugin(final PluginProviderContext context) {
        return new WorldManagementPlugin(commandComposition);
    }

    private CommandAliasConfiguration loadAliases(final BootstrapContext context) {
        try {
            return new CommandAliasConfigurationLoader().load(context.getDataDirectory());
        } catch (final RuntimeException exception) {
            context.getLogger().warn("Could not load commands.yml; only /wm will be registered.", exception);
            return new CommandAliasConfiguration(List.of(), java.util.Map.of());
        }
    }

    private void registerModuleAliases(
        final Commands commands,
        final CommandAliasConfiguration aliases,
        final BootstrapContext context
    ) {
        for (final ModuleId module : List.of(ModuleId.WARP, ModuleId.OWNERSHIP, ModuleId.STORAGE)) {
            for (final String alias : availableAliases(commands, aliases.aliases(module), context)) {
                commands.register(
                    commandComposition.command().buildModuleRoot(module, alias),
                    "WorldManagement " + module.name().toLowerCase(java.util.Locale.ROOT) + " commands."
                );
            }
        }
    }

    private List<String> availableAliases(
        final Commands commands,
        final List<String> requestedAliases,
        final BootstrapContext context
    ) {
        final List<String> available = new ArrayList<>();
        for (final String alias : requestedAliases) {
            if (commands.getDispatcher().getRoot().getChild(alias) == null) {
                available.add(alias);
            } else {
                context.getLogger().warn("WorldManagement skipped command alias /{} because it is already registered.", alias);
            }
        }
        return List.copyOf(available);
    }
}