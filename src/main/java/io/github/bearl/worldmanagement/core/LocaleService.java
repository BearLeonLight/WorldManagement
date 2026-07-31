package io.github.bearl.worldmanagement.core;

import java.util.Locale;

/** Normalizes the configured locale identifier used to select a message resource. */
public final class LocaleService {

    private LocaleService() {
    }

    public static String normalize(final String configuredLocale) {
        final String normalized = configuredLocale.replace('-', '_').trim();
        final Locale locale = Locale.forLanguageTag(normalized.replace('_', '-'));
        if (locale.getLanguage().isBlank()) {
            throw new IllegalArgumentException("Invalid locale: " + configuredLocale);
        }
        return locale.getCountry().isBlank() ? locale.getLanguage() : locale.getLanguage() + "_" + locale.getCountry();
    }
}