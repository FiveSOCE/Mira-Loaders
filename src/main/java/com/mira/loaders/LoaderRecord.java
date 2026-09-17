package com.mira.loaders;

import java.util.UUID;

public record LoaderRecord(
        UUID id,
        UUID owner,
        UUID worldId,
        int x,
        int y,
        int z,
        long expiresAt
) {
    public int chunkX() { return x >> 4; }
    public int chunkZ() { return z >> 4; }
    public boolean active(long now) { return expiresAt > now; }
    public LoaderRecord withExpiresAt(long value) {
        return new LoaderRecord(id, owner, worldId, x, y, z, value);
    }
}
