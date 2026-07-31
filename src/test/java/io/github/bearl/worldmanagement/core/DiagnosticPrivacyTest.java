package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DiagnosticPrivacyTest {

    @Test
    void masksIpAndJdbcEndpointWithoutCredentialsOrQuery() {
        assertEquals("203.0.113.xxx", DiagnosticPrivacy.maskIpAddress("203.0.113.42").orElseThrow());
        assertEquals("jdbc:mysql://db.example.test:3306/worlds",
            DiagnosticPrivacy.maskJdbcEndpoint("jdbc:mysql://user:secret@db.example.test:3306/worlds?ssl=true#token").orElseThrow());
    }

    @Test
    void omitsUnsafeValuesThatCannotBeParsed() {
        assertTrue(DiagnosticPrivacy.maskIpAddress("not-an-ip").isEmpty());
        assertTrue(DiagnosticPrivacy.maskJdbcEndpoint("jdbc:opaque-secret").isEmpty());
    }
}