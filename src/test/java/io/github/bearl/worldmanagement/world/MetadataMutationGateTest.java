package io.github.bearl.worldmanagement.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MetadataMutationGateTest {

    @Test
    void drainsAcceptedMutationsAndFreezesAfterSuccessfulMigration() {
        final PluginIoExecutor executor = new PluginIoExecutor("MutationGateTest");
        try {
            final MetadataMutationGate gate = new MetadataMutationGate(executor);
            final List<String> order = new ArrayList<>();

            final var accepted = gate.submitMutation(() -> {
                order.add("mutation");
                return null;
            });
            final var migration = gate.submitMigration(() -> {
                order.add("migration");
                return null;
            });
            final var rejectedDuringMigration = gate.submitMutation(() -> null);

            accepted.join();
            migration.join();

            assertEquals(List.of("mutation", "migration"), order);
            assertTrue(rejectedDuringMigration.isCompletedExceptionally());
            assertTrue(gate.submitMutation(() -> null).isCompletedExceptionally());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void reopensMutationsAfterFailedMigration() {
        final PluginIoExecutor executor = new PluginIoExecutor("MutationGateTest");
        try {
            final MetadataMutationGate gate = new MetadataMutationGate(executor);

            final var migration = gate.submitMigration(() -> {
                throw new IllegalStateException("simulated migration failure");
            });
            try {
                migration.join();
            } catch (final java.util.concurrent.CompletionException ignored) {
            }

            assertEquals("accepted", gate.submitMutation(() -> "accepted").join());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }
}