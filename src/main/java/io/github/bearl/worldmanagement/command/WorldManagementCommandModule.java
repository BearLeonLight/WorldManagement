package io.github.bearl.worldmanagement.command;

import org.bukkit.command.CommandSender;

/** Handles one top-level `/wm` feature command. */
public interface WorldManagementCommandModule {

    String command();

    boolean execute(CommandSender sender, String[] arguments);
}
