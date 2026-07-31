package io.github.bearl.worldmanagement.config;

public record DebugPrivacyConfiguration(
    boolean includePlayerNames,
    boolean includeMaskedIpAddresses,
    boolean includeMaskedJdbcEndpoints
) {
}