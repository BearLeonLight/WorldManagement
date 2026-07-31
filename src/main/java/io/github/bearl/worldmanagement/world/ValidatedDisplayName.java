package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/** A display name whose MiniMessage input and rendered plain text passed the safe limits. */
public record ValidatedDisplayName(String miniMessage, Component component, String plainText) {

    public ValidatedDisplayName {
        Objects.requireNonNull(miniMessage, "miniMessage");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(plainText, "plainText");
    }
}