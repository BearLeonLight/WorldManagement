package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.world.WorldGeneratorReference;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

final class WorldGeneratorCatalogTest {

    @Test
    void snapshotsOnlyEnabledPluginsWithAValidGeneratorOrBiomeProvider() {
        final ChunkGenerator generator = new ChunkGenerator() { };
        final BiomeProvider biomeProvider = biomeProvider();
        final Plugin terra = plugin("Terra", true, (world, id) -> generator);
        final Plugin biomeOnly = plugin(
            "BiomeOnly", true, (world, id) -> null, (world, id) -> biomeProvider
        );
        final Plugin disabled = plugin("Disabled", false, (world, id) -> generator);
        final Plugin empty = plugin("Empty", true, (world, id) -> null);
        final Plugin broken = plugin("Broken", true, (world, id) -> { throw new IllegalStateException("broken"); });
        final WorldGeneratorCatalog catalog = new WorldGeneratorCatalog(
            pluginManager(terra, biomeOnly, disabled, empty, broken), ignored -> { }
        );

        catalog.refresh("probe");

        assertEquals(List.of("BiomeOnly", "Terra"), catalog.pluginNames());
        assertEquals(List.of("Terra"), catalog.generatorPluginNames());
        assertEquals(List.of("BiomeOnly"), catalog.biomeProviderPluginNames());
    }

    @Test
    void resolvesPluginIdAgainstTheActualWorldAndFailsClosed() {
        final ChunkGenerator generator = new ChunkGenerator() { };
        final AtomicReference<String> requested = new AtomicReference<>();
        final Plugin terra = plugin("Terra", true, (world, id) -> {
            requested.set(world + ':' + id);
            return id.equals("normal") ? generator : null;
        });
        final WorldGeneratorCatalog catalog = new WorldGeneratorCatalog(pluginManager(terra), ignored -> { });

        assertSame(generator, catalog.resolve(
            "creative", WorldGeneratorReference.parse("Terra:normal")
        ).orElseThrow());
        assertEquals("creative:normal", requested.get());
        assertTrue(catalog.resolve("creative", WorldGeneratorReference.parse("Terra:missing")).isEmpty());
        assertTrue(catalog.resolve("creative", WorldGeneratorReference.parse("Unknown")).isEmpty());
    }

    @Test
    void resolvesBiomeProviderAgainstTheActualWorldAndFailsClosed() {
        final BiomeProvider biomeProvider = biomeProvider();
        final AtomicReference<String> requested = new AtomicReference<>();
        final Plugin terra = plugin(
            "Terra", true, (world, id) -> null,
            (world, id) -> {
                requested.set(world + ':' + id);
                return id.equals("climate") ? biomeProvider : null;
            }
        );
        final WorldGeneratorCatalog catalog = new WorldGeneratorCatalog(pluginManager(terra), ignored -> { });

        assertSame(biomeProvider, catalog.resolveBiomeProvider(
            "creative", WorldGeneratorReference.parse("Terra:climate")
        ).orElseThrow());
        assertEquals("creative:climate", requested.get());
        assertTrue(catalog.resolveBiomeProvider(
            "creative", WorldGeneratorReference.parse("Terra:missing")
        ).isEmpty());
    }

    private static BiomeProvider biomeProvider() {
        return new BiomeProvider() {
            @Override
            public java.util.List<org.bukkit.block.Biome> getBiomes(
                final org.bukkit.generator.WorldInfo worldInfo
            ) {
                return java.util.List.of(org.bukkit.block.Biome.PLAINS);
            }

            @Override
            public org.bukkit.block.Biome getBiome(
                final org.bukkit.generator.WorldInfo worldInfo,
                final int x,
                final int y,
                final int z
            ) {
                return org.bukkit.block.Biome.PLAINS;
            }
        };
    }

    @Test
    void removesDisabledPluginEvenWhilePaperStillReportsItEnabled() {
        final ChunkGenerator generator = new ChunkGenerator() { };
        final Plugin terra = plugin("Terra", true, (world, id) -> generator);
        final WorldGeneratorCatalog catalog = new WorldGeneratorCatalog(pluginManager(terra), ignored -> { });
        catalog.refresh("probe");

        catalog.pluginDisabled(terra);

        assertTrue(catalog.pluginNames().isEmpty());
    }

    private static Plugin plugin(
        final String name,
        final boolean enabled,
        final BiFunction<String, String, ChunkGenerator> generator
    ) {
        return plugin(name, enabled, generator, (world, id) -> null);
    }

    private static Plugin plugin(
        final String name,
        final boolean enabled,
        final BiFunction<String, String, ChunkGenerator> generator,
        final BiFunction<String, String, BiomeProvider> biomeProvider
    ) {
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> name;
                case "isEnabled" -> enabled;
                case "getDefaultWorldGenerator" -> generator.apply((String) arguments[0], (String) arguments[1]);
                case "getDefaultBiomeProvider" -> biomeProvider.apply((String) arguments[0], (String) arguments[1]);
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static PluginManager pluginManager(final Plugin... plugins) {
        return (PluginManager) Proxy.newProxyInstance(
            PluginManager.class.getClassLoader(),
            new Class<?>[] {PluginManager.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getPlugins" -> plugins;
                case "getPlugin" -> java.util.Arrays.stream(plugins)
                    .filter(plugin -> plugin.getName().equalsIgnoreCase((String) arguments[0]))
                    .findFirst()
                    .orElse(null);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }
}