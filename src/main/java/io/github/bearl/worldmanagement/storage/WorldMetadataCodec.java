package io.github.bearl.worldmanagement.storage;

import io.github.bearl.worldmanagement.world.WorldMetadata;

/** Serializes a complete metadata aggregate for storage adapters. */
public interface WorldMetadataCodec {

    String encode(WorldMetadata metadata);

    WorldMetadata decode(String serialized);
}