package io.github.bearl.worldmanagement.protection;

import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Single-use authorization for one player teleport to one verified world identity. */
public final class TeleportBypassTokens {

    private final ConcurrentHashMap<UUID, Token> tokens = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;
    private final long lifetimeNanos;

    public TeleportBypassTokens() {
        this(System::nanoTime, Duration.ofSeconds(5));
    }

    TeleportBypassTokens(final LongSupplier nanoTime, final Duration lifetime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.lifetimeNanos = Objects.requireNonNull(lifetime, "lifetime").toNanos();
        if (lifetimeNanos <= 0L) {
            throw new IllegalArgumentException("Token lifetime must be positive.");
        }
    }

    public Token issue(final UUID playerId, final VerifiedWorldRef target) {
        final long now = nanoTime.getAsLong();
        final Token token = new Token(
            UUID.randomUUID(),
            Objects.requireNonNull(playerId, "playerId"),
            Objects.requireNonNull(target, "target"),
            saturatedAdd(now, lifetimeNanos)
        );
        tokens.put(playerId, token);
        return token;
    }

    public boolean consume(final UUID playerId, final VerifiedWorldRef target) {
        final UUID requiredPlayerId = Objects.requireNonNull(playerId, "playerId");
        final VerifiedWorldRef requiredTarget = Objects.requireNonNull(target, "target");
        final long now = nanoTime.getAsLong();
        final AtomicBoolean consumed = new AtomicBoolean();
        tokens.computeIfPresent(requiredPlayerId, (ignored, token) -> {
            if (now > token.expiresAtNanos()) {
                return null;
            }
            if (!token.target().equals(requiredTarget)) {
                return token;
            }
            consumed.set(true);
            return null;
        });
        return consumed.get();
    }

    public void clear(final Token token) {
        final Token requiredToken = Objects.requireNonNull(token, "token");
        tokens.remove(requiredToken.playerId(), requiredToken);
    }

    public void clear() {
        tokens.clear();
    }

    private static long saturatedAdd(final long value, final long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (final ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public record Token(UUID tokenId, UUID playerId, VerifiedWorldRef target, long expiresAtNanos) {
        public Token {
            Objects.requireNonNull(tokenId, "tokenId");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(target, "target");
        }
    }
}