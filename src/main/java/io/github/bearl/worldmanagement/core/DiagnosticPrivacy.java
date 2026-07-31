package io.github.bearl.worldmanagement.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public final class DiagnosticPrivacy {

    private DiagnosticPrivacy() {
    }

    public static Optional<String> maskIpAddress(final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.contains(":")) {
            final String[] groups = value.split(":", -1);
            return groups.length >= 2 ? Optional.of(groups[0] + ":" + groups[1] + "::/32") : Optional.empty();
        }
        final String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return Optional.empty();
        }
        try {
            for (final String octet : octets) {
                final int parsed = Integer.parseInt(octet);
                if (parsed < 0 || parsed > 255) {
                    return Optional.empty();
                }
            }
        } catch (final NumberFormatException exception) {
            return Optional.empty();
        }
        return Optional.of(octets[0] + "." + octets[1] + "." + octets[2] + ".xxx");
    }

    public static Optional<String> maskJdbcEndpoint(final String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return Optional.empty();
        }
        try {
            final URI endpoint = new URI(jdbcUrl.substring("jdbc:".length()));
            if (endpoint.getScheme() == null || endpoint.getHost() == null) {
                return Optional.empty();
            }
            final String port = endpoint.getPort() >= 0 ? ":" + endpoint.getPort() : "";
            return Optional.of("jdbc:" + endpoint.getScheme() + "://" + endpoint.getHost() + port + endpoint.getPath());
        } catch (final URISyntaxException exception) {
            return Optional.empty();
        }
    }
}