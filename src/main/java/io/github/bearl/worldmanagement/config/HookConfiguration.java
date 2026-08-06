package io.github.bearl.worldmanagement.config;

/** Immutable optional integration settings. */
public record HookConfiguration(
	boolean luckPermsEnabled,
	boolean multiverseEnabled,
	boolean placeholderApiEnabled,
	boolean miniPlaceholdersEnabled
) {
}