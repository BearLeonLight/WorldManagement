package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.audit.AuditAdmission;
import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class IdentityCommandModule implements WorldManagementCommandModule {

    private static final String SHOW_PERMISSION = "worldmanagement.command.identity.show";
    private static final String SYNC_PERMISSION = "worldmanagement.command.identity.sync";
    private static final String ACCEPT_PERMISSION = "worldmanagement.command.identity.accept-replacement";
    private static final String ABANDON_PERMISSION = "worldmanagement.command.identity.abandon";

    private final WorldManagementService service;
    private final WorldNameValidator nameValidator;
    private final AuditService auditService;
    private final MessageService messages;
    private final CommandMessageSender messageSender;

    public IdentityCommandModule(
        final WorldManagementService service,
        final WorldNameValidator nameValidator,
        final AuditService auditService,
        final MessageService messages,
        final CommandMessageSender messageSender
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.nameValidator = Objects.requireNonNull(nameValidator, "nameValidator");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.messageSender = Objects.requireNonNull(messageSender, "messageSender");
    }

    @Override
    public String command() {
        return "identity";
    }

    @Override
    public boolean execute(final CommandSender sender, final String[] arguments) {
        if (arguments.length < 2 || !arguments[0].equalsIgnoreCase("identity")) {
            return false;
        }
        return switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "show" -> show(sender, arguments);
            case "sync" -> synchronize(sender, arguments);
            case "accept-replacement" -> acceptReplacement(sender, arguments);
            case "abandon" -> abandon(sender, arguments);
            default -> {
                send(sender, "command.identity.usage");
                yield true;
            }
        };
    }

    private boolean show(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(SHOW_PERMISSION)) {
            send(sender, "command.permission.identity-show");
            return true;
        }
        final String worldName = worldName(sender, arguments, 3);
        if (worldName == null) {
            return true;
        }
        final WorldMetadata metadata = service.metadataWorld(worldName).orElse(null);
        if (metadata == null) {
            send(sender, "command.identity.not-managed", "world", worldName);
            return true;
        }
        final WorldIdentitySnapshot identity = metadata.identity();
        messageSender.send(sender, messages.component("command.identity.show", Map.of(
            "world", Component.text(metadata.worldName()),
            "key", Component.text(identity.paperKey()),
            "uuid", Component.text(identity.worldUuid().toString()),
            "environment", messages.termComponent(environmentTerm(identity.environment())),
            "seed", Component.text(Long.toString(identity.seed())),
            "structures", booleanTerm(identity.generateStructures()),
            "state", messages.termComponent(identityStateTerm(metadata.identityState())),
            "capability", messages.termComponent(capabilityTerm(metadata.lifecycleCapability())),
            "pending", metadata.pendingIdentity().map(this::identitySummary)
                .orElseGet(() -> messages.termComponent("common.none"))
        )));
        return true;
    }

    private boolean synchronize(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(SYNC_PERMISSION)) {
            send(sender, "command.permission.identity-sync");
            return true;
        }
        final String worldName = worldName(sender, arguments, 3);
        if (worldName == null) {
            return true;
        }
        final WorldMetadata metadata = service.managedWorld(worldName).orElse(null);
        if (metadata == null) {
            send(sender, "command.identity.not-managed", "world", worldName);
            return true;
        }
        if (metadata.identityState() != IdentityVerificationState.SYNC_PENDING
            || metadata.pendingIdentity().isEmpty()) {
            send(sender, "command.identity.sync.not-pending", "world", worldName);
            return true;
        }
        final WorldIdentitySnapshot expected = metadata.pendingIdentity().orElseThrow();
        final String actor = actor(sender);
        final CommandMessageSender.Target target = messageSender.capture(sender);
        admit(actor, "world.identity.sync", worldName, target, () -> service.synchronizeIdentity(
            worldName,
            expected,
            event(actor, "world.identity.sync.success", worldName, "")
        ).whenComplete((result, failure) -> respondSync(target, worldName, result, failure)));
        return true;
    }

    private boolean acceptReplacement(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(ACCEPT_PERMISSION)) {
            send(sender, "command.permission.identity-accept");
            return true;
        }
        if (arguments.length != 5 || !arguments[3].equalsIgnoreCase("confirm")) {
            send(sender, "command.identity.usage");
            return true;
        }
        final boolean keepWarps;
        if (arguments[4].equalsIgnoreCase("keep-warps")) {
            keepWarps = true;
        } else if (arguments[4].equalsIgnoreCase("clear-warps")) {
            keepWarps = false;
        } else {
            send(sender, "command.identity.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[2]);
        if (worldName == null) {
            return true;
        }
        final WorldMetadata metadata = service.managedWorld(worldName).orElse(null);
        if (metadata == null) {
            send(sender, "command.identity.not-managed", "world", worldName);
            return true;
        }
        if (metadata.identityState() != IdentityVerificationState.CONFLICT
            || metadata.pendingIdentity().isEmpty()) {
            send(sender, "command.identity.accept.not-conflict", "world", worldName);
            return true;
        }
        final WorldIdentitySnapshot expected = metadata.pendingIdentity().orElseThrow();
        final String actor = actor(sender);
        final String policy = keepWarps ? "keep-warps" : "clear-warps";
        final CommandMessageSender.Target target = messageSender.capture(sender);
        admit(actor, "world.identity.accept-replacement", worldName, target, () -> service.acceptIdentityReplacement(
            worldName,
            expected,
            keepWarps,
            event(actor, "world.identity.accept-replacement.success", worldName, "warp-policy=" + policy)
        ).whenComplete((result, failure) -> respondAccept(target, worldName, policy, result, failure)));
        return true;
    }

    private boolean abandon(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(ABANDON_PERMISSION)) {
            send(sender, "command.permission.identity-abandon");
            return true;
        }
        if (arguments.length != 4 || !arguments[3].equalsIgnoreCase("confirm")) {
            send(sender, "command.identity.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[2]);
        if (worldName == null) {
            return true;
        }
        final WorldMetadata metadata = service.managedWorld(worldName).orElse(null);
        if (metadata == null) {
            send(sender, "command.identity.not-managed", "world", worldName);
            return true;
        }
        if (metadata.managementState() != WorldManagementState.ACTIVE
            || metadata.identityState() == IdentityVerificationState.VERIFIED) {
            send(sender, "command.identity.abandon.not-allowed", "world", worldName);
            return true;
        }
        final String actor = actor(sender);
        final CommandMessageSender.Target target = messageSender.capture(sender);
        admit(actor, "world.identity.abandon", worldName, target, () -> service.abandonIdentity(
            worldName,
            metadata.version(),
            event(actor, "world.identity.abandon.success", worldName, "")
        ).whenComplete((result, failure) -> respondAbandon(target, worldName, result, failure)));
        return true;
    }

    private void admit(
        final String actor,
        final String action,
        final String worldName,
        final CommandMessageSender.Target target,
        final Runnable mutation
    ) {
        auditService.admit(actor, action + ".admission", worldName, "pending").thenAccept(admission -> {
            if (admission == AuditAdmission.REJECTED) {
                respond(target, "command.audit-rejected");
            } else {
                mutation.run();
            }
        });
    }

    private void respondSync(
        final CommandMessageSender.Target target,
        final String worldName,
        final WorldManagementService.IdentitySyncResult result,
        final Throwable failure
    ) {
        if (failure != null) {
            respond(target, "command.identity.sync.failure", "world", worldName);
            return;
        }
        switch (result.status()) {
            case SYNCHRONIZED -> respond(target, "command.identity.sync.success", "world", worldName);
            case STALE -> respond(target, "command.identity.sync.stale", "world", worldName);
            case NOT_MANAGED -> respond(target, "command.identity.not-managed", "world", worldName);
            case NOT_READY -> respond(target, "command.loading");
        }
    }

    private void respondAccept(
        final CommandMessageSender.Target target,
        final String worldName,
        final String policy,
        final WorldManagementService.IdentityMutationResult result,
        final Throwable failure
    ) {
        if (failure != null) {
            respond(target, "command.identity.accept.failure", "world", worldName);
            return;
        }
        switch (result.status()) {
            case REPLACEMENT_ACCEPTED -> messageSender.send(target, messages.component(
                "command.identity.accept.success",
                Map.of(
                    "world", Component.text(worldName),
                    "policy", messages.termComponent(policyTerm(policy))
                )
            ));
            case INDEX_CONFLICT -> respond(target, "command.identity.accept.index-conflict", "world", worldName);
            case STALE -> respond(target, "command.identity.accept.stale", "world", worldName);
            case NOT_MANAGED -> respond(target, "command.identity.not-managed", "world", worldName);
            case NOT_READY -> respond(target, "command.loading");
            case ABANDONED -> throw new IllegalStateException("Unexpected identity mutation result: " + result.status());
        }
    }

    private void respondAbandon(
        final CommandMessageSender.Target target,
        final String worldName,
        final WorldManagementService.IdentityMutationResult result,
        final Throwable failure
    ) {
        if (failure != null) {
            respond(target, "command.identity.abandon.failure", "world", worldName);
            return;
        }
        switch (result.status()) {
            case ABANDONED -> respond(target, "command.identity.abandon.success", "world", worldName);
            case STALE, INDEX_CONFLICT -> respond(target, "command.identity.abandon.stale", "world", worldName);
            case NOT_MANAGED -> respond(target, "command.identity.not-managed", "world", worldName);
            case NOT_READY -> respond(target, "command.loading");
            case REPLACEMENT_ACCEPTED -> throw new IllegalStateException(
                "Unexpected identity mutation result: " + result.status()
            );
        }
    }

    private String worldName(final CommandSender sender, final String[] arguments, final int expectedLength) {
        if (arguments.length != expectedLength) {
            send(sender, "command.identity.usage");
            return null;
        }
        return validWorldName(sender, arguments[2]);
    }

    private String validWorldName(final CommandSender sender, final String value) {
        try {
            return nameValidator.requireValidName(value);
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.world-name-invalid");
            return null;
        }
    }

    private Component identitySummary(final WorldIdentitySnapshot identity) {
        return messages.component("command.identity.pending-summary", Map.of(
            "key", Component.text(identity.paperKey()),
            "uuid", Component.text(identity.worldUuid().toString()),
            "environment", messages.termComponent(environmentTerm(identity.environment())),
            "seed", Component.text(Long.toString(identity.seed())),
            "structures", booleanTerm(identity.generateStructures())
        ));
    }

    private Component booleanTerm(final boolean value) {
        return messages.termComponent(value ? "common.yes" : "common.no");
    }

    private static String environmentTerm(final io.github.bearl.worldmanagement.world.WorldEnvironment environment) {
        return switch (environment) {
            case NORMAL -> "environment.normal";
            case NETHER -> "environment.nether";
            case THE_END -> "environment.the-end";
            case CUSTOM -> "environment.custom";
        };
    }

    private static String identityStateTerm(final IdentityVerificationState state) {
        return switch (state) {
            case VERIFIED -> "identity.verified";
            case SYNC_PENDING -> "identity.sync-pending";
            case CONFLICT -> "identity.conflict";
        };
    }

    private static String capabilityTerm(
        final io.github.bearl.worldmanagement.world.LifecycleCapability capability
    ) {
        return switch (capability) {
            case MANAGED -> "capability.managed";
            case EXTERNAL_ONLY -> "capability.external-only";
        };
    }

    private static String policyTerm(final String policy) {
        return switch (policy) {
            case "keep-warps" -> "policy.keep-warps";
            case "clear-warps" -> "policy.clear-warps";
            default -> throw new IllegalArgumentException("Unknown identity replacement policy: " + policy);
        };
    }

    private static AuditEvent event(
        final String actor,
        final String action,
        final String worldName,
        final String detail
    ) {
        return new AuditEvent(Instant.now(), Optional.ofNullable(actor), action, worldName, detail);
    }

    private static String actor(final CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
    }

    private void send(final CommandSender sender, final String key, final String... replacements) {
        messageSender.send(sender, messages.component(key, replacements));
    }

    private void respond(final CommandMessageSender.Target target, final String key, final String... replacements) {
        messageSender.send(target, messages.component(key, replacements));
    }
}