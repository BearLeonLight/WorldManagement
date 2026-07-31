package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.junit.jupiter.api.Test;

final class PaperWorldIdentityTest {

    @Test
    void capturesCompleteVanillaWorldIdentityAsManaged() {
        final UUID worldUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");

        final PaperWorldIdentity captured = PaperWorldIdentity.capture(world(
            NamespacedKey.minecraft("creative"), worldUuid, World.Environment.NORMAL, 42L, true, null, null
        ));

        assertEquals(new WorldIdentitySnapshot(
            "minecraft:creative", worldUuid, WorldEnvironment.NORMAL, 42L, true
        ), captured.snapshot());
        assertEquals("runtime-creative", captured.bukkitWorldName());
        assertEquals(LifecycleCapability.MANAGED, captured.lifecycleCapability());
    }

    @Test
    void classifiesCustomRuntimeDependenciesAsExternalOnly() {
        final UUID worldUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");

        final PaperWorldIdentity captured = PaperWorldIdentity.capture(world(
            new NamespacedKey("external", "creative"), worldUuid, World.Environment.CUSTOM,
            99L, false, new ChunkGenerator() { }, null
        ));

        assertEquals(new WorldIdentitySnapshot(
            "external:creative", worldUuid, WorldEnvironment.CUSTOM, 99L, false
        ), captured.snapshot());
        assertEquals(LifecycleCapability.EXTERNAL_ONLY, captured.lifecycleCapability());
    }

    private static World world(
        final NamespacedKey key,
        final UUID uuid,
        final World.Environment environment,
        final long seed,
        final boolean generateStructures,
        final ChunkGenerator generator,
        final org.bukkit.generator.BiomeProvider biomeProvider
    ) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> "runtime-creative";
                case "getKey" -> key;
                case "getUID" -> uuid;
                case "getEnvironment" -> environment;
                case "getSeed" -> seed;
                case "canGenerateStructures" -> generateStructures;
                case "getGenerator" -> generator;
                case "getBiomeProvider" -> biomeProvider;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}