package io.github.bearl.worldmanagement.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class TeleportBypassTokensTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final VerifiedWorldRef CREATIVE = new VerifiedWorldRef(
        "creative",
        "minecraft:creative",
        UUID.fromString("22222222-2222-2222-2222-222222222222")
    );

    @Test
    void consumesOnlyOnceForTheBoundPlayerAndExactTarget() {
        final AtomicLong now = new AtomicLong(100L);
        final TeleportBypassTokens tokens = new TeleportBypassTokens(now::get, Duration.ofSeconds(5));

        tokens.issue(PLAYER_ID, CREATIVE);

        assertFalse(tokens.consume(UUID.randomUUID(), CREATIVE));
        assertFalse(tokens.consume(PLAYER_ID, new VerifiedWorldRef(
            "creative",
            "minecraft:creative",
            UUID.fromString("33333333-3333-3333-3333-333333333333")
        )));
        assertTrue(tokens.consume(PLAYER_ID, CREATIVE));
        assertFalse(tokens.consume(PLAYER_ID, CREATIVE));
    }

    @Test
    void rejectsExpiredTokens() {
        final AtomicLong now = new AtomicLong(100L);
        final TeleportBypassTokens tokens = new TeleportBypassTokens(now::get, Duration.ofNanos(10));

        tokens.issue(PLAYER_ID, CREATIVE);
        now.set(111L);

        assertFalse(tokens.consume(PLAYER_ID, CREATIVE));
    }

    @Test
    void completionHandleCannotClearANewerToken() {
        final TeleportBypassTokens tokens = new TeleportBypassTokens(System::nanoTime, Duration.ofSeconds(5));

        final TeleportBypassTokens.Token first = tokens.issue(PLAYER_ID, CREATIVE);
        tokens.issue(PLAYER_ID, CREATIVE);
        tokens.clear(first);

        assertTrue(tokens.consume(PLAYER_ID, CREATIVE));
    }
}
