package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.Context;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.context.MutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.junit.jupiter.api.Test;

final class LuckPermsCachedPermissionLookupTest {

    private static final UUID PLAYER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PERMISSION = "worldmanagement.warp.vip";

    @Test
    void replacesOnlyTheWorldContextBeforeReadingCachedPermissionData() {
        final AtomicReference<QueryOptions> requestedOptions = new AtomicReference<>();
        final CachedPermissionLookup lookup = new LuckPermsCachedPermissionLookup(api(
            queryOptions(contexts("source-world", "hub")),
            Set.of("runtime_creative"),
            requestedOptions
        ));

        assertTrue(lookup.hasPermission(PLAYER_ID, "Runtime_Creative", PERMISSION));
        assertEquals(Set.of("runtime_creative"), requestedOptions.get().context().getValues(DefaultContextKeys.WORLD_KEY));
        assertEquals(Set.of("hub"), requestedOptions.get().context().getValues(DefaultContextKeys.SERVER_KEY));
    }

    @Test
    void doesNotReuseTheSourceWorldPermissionContext() {
        final CachedPermissionLookup lookup = new LuckPermsCachedPermissionLookup(api(
            queryOptions(contexts("source-world", "hub")),
            Set.of("source-world"),
            new AtomicReference<>()
        ));

        assertFalse(lookup.hasPermission(PLAYER_ID, "runtime_creative", PERMISSION));
    }

    @Test
    void failsClosedWithoutALoadedUserOrOnlineQueryOptions() {
        assertFalse(new LuckPermsCachedPermissionLookup(api(null, Set.of("runtime_creative"), new AtomicReference<>()))
            .hasPermission(PLAYER_ID, "runtime_creative", PERMISSION));
        assertFalse(new LuckPermsCachedPermissionLookup(api(
            null,
            Set.of("runtime_creative"),
            new AtomicReference<>(),
            true
        )).hasPermission(PLAYER_ID, "runtime_creative", PERMISSION));
    }

    private static LuckPerms api(
        final QueryOptions queryOptions,
        final Set<String> allowedWorlds,
        final AtomicReference<QueryOptions> requestedOptions
    ) {
        return api(queryOptions, allowedWorlds, requestedOptions, false);
    }

    private static LuckPerms api(
        final QueryOptions queryOptions,
        final Set<String> allowedWorlds,
        final AtomicReference<QueryOptions> requestedOptions,
        final boolean noOnlineQueryOptions
    ) {
        final CachedPermissionData permissions = proxy(CachedPermissionData.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "checkPermission" -> allowedWorlds.contains(
                requestedOptions.get().context().getValues(DefaultContextKeys.WORLD_KEY).iterator().next()
            ) ? Tristate.TRUE : Tristate.FALSE;
            default -> throw new AssertionError("Unexpected cached permission method: " + method.getName());
        });
        final CachedDataManager cachedData = proxy(CachedDataManager.class, (proxy, method, arguments) -> {
            if (method.getName().equals("getPermissionData") && arguments != null && arguments.length == 1) {
                requestedOptions.set((QueryOptions) arguments[0]);
                return permissions;
            }
            throw new AssertionError("Unexpected cached data method: " + method.getName());
        });
        final User user = proxy(User.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getCachedData" -> cachedData;
            case "getUniqueId" -> PLAYER_ID;
            default -> throw new AssertionError("Unexpected user method: " + method.getName());
        });
        final UserManager users = proxy(UserManager.class, (proxy, method, arguments) -> {
            if (method.getName().equals("getUser")) {
                return queryOptions == null && !noOnlineQueryOptions ? null : user;
            }
            throw new AssertionError("Unexpected user manager method: " + method.getName());
        });
        final ContextManager contexts = proxy(ContextManager.class, (proxy, method, arguments) -> {
            if (method.getName().equals("getQueryOptions")) {
                return noOnlineQueryOptions ? Optional.empty() : Optional.of(queryOptions);
            }
            throw new AssertionError("Unexpected context manager method: " + method.getName());
        });
        return proxy(LuckPerms.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getUserManager" -> users;
            case "getContextManager" -> contexts;
            default -> throw new AssertionError("Unexpected LuckPerms method: " + method.getName());
        });
    }

    private static QueryOptions queryOptions(final ContextState context) {
        return proxy(QueryOptions.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "mode" -> QueryMode.CONTEXTUAL;
            case "context" -> context.immutable();
            case "toBuilder" -> queryOptionsBuilder(context.copy());
            case "flags" -> Set.of();
            case "options" -> Map.of();
            case "flag" -> true;
            case "option" -> Optional.empty();
            default -> throw new AssertionError("Unexpected query options method: " + method.getName());
        });
    }

    private static QueryOptions.Builder queryOptionsBuilder(final ContextState initialContext) {
        final AtomicReference<ContextState> current = new AtomicReference<>(initialContext);
        final AtomicReference<QueryOptions.Builder> builder = new AtomicReference<>();
        builder.set(proxy(QueryOptions.Builder.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "context" -> {
                current.set(ContextState.from((ContextSet) arguments[0]));
                yield builder.get();
            }
            case "build" -> queryOptions(current.get());
            case "mode", "flag", "flags", "option" -> builder.get();
            default -> throw new AssertionError("Unexpected query options builder method: " + method.getName());
        }));
        return builder.get();
    }

    private static ContextState contexts(final String world, final String server) {
        return new ContextState(Map.of(
            DefaultContextKeys.WORLD_KEY, Set.of(world),
            DefaultContextKeys.SERVER_KEY, Set.of(server)
        ));
    }

    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private record ContextEntry(String key, String value) implements Context {

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public String getValue() {
            return value;
        }
    }

    private static final class ContextState {

        private final Map<String, Set<String>> values;

        private ContextState(final Map<String, Set<String>> values) {
            this.values = new LinkedHashMap<>();
            values.forEach((key, contexts) -> this.values.put(
                normalize(key), contexts.stream().map(ContextState::normalize).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
            ));
        }

        private ContextState copy() {
            return new ContextState(values);
        }

        private static ContextState from(final ContextSet contexts) {
            final Map<String, Set<String>> copied = new LinkedHashMap<>();
            for (final Context entry : contexts.toSet()) {
                copied.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).add(entry.getValue());
            }
            return new ContextState(copied);
        }

        private ImmutableContextSet immutable() {
            return proxy(ImmutableContextSet.class, (proxy, method, arguments) -> contextSetInvocation(proxy, method.getName(), arguments, true));
        }

        private MutableContextSet mutable() {
            return proxy(MutableContextSet.class, (proxy, method, arguments) -> contextSetInvocation(proxy, method.getName(), arguments, false));
        }

        private Object contextSetInvocation(
            final Object proxy,
            final String method,
            final Object[] arguments,
            final boolean immutable
        ) {
            return switch (method) {
                case "isImmutable" -> immutable;
                case "mutableCopy" -> copy().mutable();
                case "immutableCopy" -> immutable();
                case "toSet" -> entries();
                case "toMap" -> map();
                case "containsKey" -> values.containsKey(normalize((String) arguments[0]));
                case "getValues" -> values.getOrDefault(normalize((String) arguments[0]), Set.of());
                case "add" -> {
                    if (immutable) {
                        throw new UnsupportedOperationException();
                    }
                    add((String) arguments[0], (String) arguments[1]);
                    yield null;
                }
                case "removeAll" -> {
                    if (immutable) {
                        throw new UnsupportedOperationException();
                    }
                    values.remove(normalize((String) arguments[0]));
                    yield null;
                }
                default -> throw new AssertionError("Unexpected context set method: " + method);
            };
        }

        private void add(final String key, final String value) {
            values.computeIfAbsent(normalize(key), ignored -> new LinkedHashSet<>()).add(normalize(value));
        }

        private Set<Context> entries() {
            final Set<Context> entries = new LinkedHashSet<>();
            values.forEach((key, contexts) -> contexts.forEach(value -> entries.add(new ContextEntry(key, value))));
            return Set.copyOf(entries);
        }

        private Map<String, Set<String>> map() {
            final Map<String, Set<String>> copied = new LinkedHashMap<>();
            values.forEach((key, contexts) -> copied.put(key, Set.copyOf(contexts)));
            return Map.copyOf(copied);
        }

        private static String normalize(final String value) {
            return value.toLowerCase(Locale.ROOT);
        }
    }
}