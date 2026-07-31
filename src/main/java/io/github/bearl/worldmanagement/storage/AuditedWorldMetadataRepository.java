package io.github.bearl.worldmanagement.storage;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.world.WorldMetadata;

/** Storage boundary for metadata changes that must commit with their success audit event. */
public interface AuditedWorldMetadataRepository extends WorldMetadataRepository {

    void createWithAudit(WorldMetadata metadata, AuditEvent event);

    void replaceWithAudit(WorldMetadata metadata, long expectedVersion, AuditEvent event);

    void deleteWithAudit(String worldName, long expectedVersion, AuditEvent event);
}