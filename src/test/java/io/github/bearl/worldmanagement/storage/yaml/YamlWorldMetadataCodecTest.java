package io.github.bearl.worldmanagement.storage.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.storage.UnsupportedStorageSchemaException;
import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.Rank;
import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.RequestedWorldType;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldLoadState;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldWarp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class YamlWorldMetadataCodecTest {

  private static final UUID WORLD_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID PLAYER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void roundTripsTheCompleteSchemaOneAggregate() {
    final WorldIdentitySnapshot accepted = new WorldIdentitySnapshot(
      "minecraft:creative", WORLD_UUID, WorldEnvironment.NORMAL, 42L, true
    );
    final WorldIdentitySnapshot pending = new WorldIdentitySnapshot(
      "minecraft:creative", WORLD_UUID, WorldEnvironment.NORMAL, 84L, false
    );
    final WorldMetadata metadata = new WorldMetadata(
      "creative",
      "<gradient:#00aa00:#ffffff>創意 世界</gradient>",
      accepted,
      IdentityVerificationState.SYNC_PENDING,
      LifecycleCapability.MANAGED,
      Optional.of(pending),
      Optional.of(RequestedWorldType.FLAT),
      WorldManagementState.ACTIVE,
      WorldLoadState.LOADED,
      PLAYER_UUID.toString(),
      true,
      new AccessControl(AccessMode.WHITELIST, Set.of(PLAYER_UUID)),
      Map.of(
        WorldMetadata.OWNER_RANK, new Rank("World Owner", Set.of()),
        WorldMetadata.GUEST_RANK, new Rank("Guest", Set.of()),
        "BUILDER", new Rank("Builder", Set.of(RankPermission.BUILD))
      ),
      Map.of(PLAYER_UUID, "BUILDER"),
      Map.of("spawn", new WorldWarp(
        "spawn", 1, 64, 2, 90, 0, WarpVisibility.PRIVATE,
        Set.of(PLAYER_UUID), Set.of("BUILDER"), "worldmanagement.warp.spawn"
      )),
      7
    );
    final YamlWorldMetadataCodec codec = new YamlWorldMetadataCodec();

    final String encoded = codec.encode(metadata);

    assertTrue(encoded.contains("schema-version: 1"));
    assertEquals(metadata, codec.decode(encoded));
  }

  @Test
  void acceptsOnlyAnExplicitSchemaOneVersion() {
    final YamlWorldMetadataCodec codec = new YamlWorldMetadataCodec();
    final String encoded = codec.encode(WorldMetadata.createDefault("creative", true));

    assertThrows(StorageException.class,
      () -> codec.decode(encoded.replace("schema-version: 1", "schema-version: 0")));
    assertThrows(StorageException.class,
      () -> codec.decode(encoded.replace("schema-version: 1", "schema-version: -1")));
    assertThrows(StorageException.class,
      () -> codec.decode(encoded.replace("schema-version: 1\n", "")));
    assertThrows(UnsupportedStorageSchemaException.class,
      () -> codec.decode(encoded.replace("schema-version: 1", "schema-version: 2")));
    assertThrows(UnsupportedStorageSchemaException.class,
      () -> codec.decode(encoded.replace("schema-version: 1", "schema-version: 4")));
  }
}