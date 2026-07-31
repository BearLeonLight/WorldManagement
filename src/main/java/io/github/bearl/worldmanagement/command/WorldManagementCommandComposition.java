package io.github.bearl.worldmanagement.command;

import java.util.Objects;

/** Command objects created during Paper bootstrap and initialized when plugin services are ready. */
public final class WorldManagementCommandComposition {

    private final OnlinePlayerSnapshot onlinePlayers;
    private final SuggestionCatalog suggestions;
    private final BrigadierWorldManagementCommand command;

    public WorldManagementCommandComposition() {
        this.onlinePlayers = new OnlinePlayerSnapshot();
        this.suggestions = new SuggestionCatalog(onlinePlayers);
        this.command = new BrigadierWorldManagementCommand(suggestions);
    }

    public OnlinePlayerSnapshot onlinePlayers() {
        return onlinePlayers;
    }

    public SuggestionCatalog suggestions() {
        return suggestions;
    }

    public BrigadierWorldManagementCommand command() {
        return command;
    }
}