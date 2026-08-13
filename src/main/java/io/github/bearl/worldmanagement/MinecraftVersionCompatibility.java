package io.github.bearl.worldmanagement;

import java.util.regex.Pattern;

final class MinecraftVersionCompatibility {

    private static final Pattern SUPPORTED_VERSION = Pattern.compile("(?:1\\.)?26\\.\\d+");

    private MinecraftVersionCompatibility() {
    }

    static boolean isSupported(final String minecraftVersion) {
        return minecraftVersion != null && SUPPORTED_VERSION.matcher(minecraftVersion).matches();
    }
}
