package io.github.bearl.worldmanagement.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.kyori.ansi.ColorLevel;

/** Writes colored components through Paper's terminal and ANSI-stripping file appenders. */
public final class ConsoleOutput {

    private static final ANSIComponentSerializer ANSI = ANSIComponentSerializer.builder()
        .colorLevel(ColorLevel.INDEXED_16)
        .build();

    private final ComponentLogger logger;

    public ConsoleOutput(final String loggerName) {
        this.logger = ComponentLogger.logger(loggerName);
    }

    public void info(final Component message) {
        logger.info(serialize(message));
    }

    static String serialize(final Component message) {
        return ANSI.serialize(message);
    }
}