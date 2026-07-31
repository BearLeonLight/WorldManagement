package io.github.bearl.worldmanagement.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.TreeMap;

final class DiagnosticTextFormatter {

    private DiagnosticTextFormatter() {
    }

    static String format(final DiagnosticEvent event, final boolean timestamp) {
        final StringBuilder output = new StringBuilder();
        if (timestamp) {
            output.append(event.occurredAt()).append(' ');
        }
        output.append('[').append(event.level()).append(']')
            .append(" [Debug/").append(event.area().name().toLowerCase(java.util.Locale.ROOT)).append("] ")
            .append(escape(event.event()));
        for (final Map.Entry<String, String> field : new TreeMap<>(event.fields()).entrySet()) {
            output.append(' ').append(escape(field.getKey())).append('=').append(escape(field.getValue()));
        }
        event.failure().ifPresent(failure -> output.append(" failure=").append(escape(stackTrace(failure))));
        return output.toString();
    }

    private static String stackTrace(final Throwable failure) {
        final StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    private static String escape(final String value) {
        if (value == null) {
            return "null";
        }
        final StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '=' -> escaped.append("\\=");
                default -> {
                    if (Character.isISOControl(character)) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}