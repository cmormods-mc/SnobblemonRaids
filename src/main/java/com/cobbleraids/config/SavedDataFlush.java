package com.cobbleraids.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Writes one SavedData to disk now, and nothing else.
 *
 * <p>{@code DimensionDataStorage.save()} walks every store of every mod that happens to be dirty.
 * Measured on a 24-client rig, calling it from each reward claim averaged 51 ms per call with a
 * worst case of 1.2 s, all of it synchronous on the server thread -- more than a whole tick for
 * every claim, when the only thing the claim needed made durable was its own queue.
 *
 * <p>The path is the one DimensionDataStorage itself uses for the overworld
 * ({@code <world>/data/<id>.dat}), and {@code SavedData.save} writes only when the store is dirty
 * and then clears the flag, so the ordinary autosave does not write the same file a second time.
 * The durability of the store being flushed is exactly what it was; what changes is that other
 * stores no longer ride along, and they keep their own autosave guarantee.
 */
public final class SavedDataFlush {
    private SavedDataFlush() {}

    public static void flush(MinecraftServer server, SavedData store, String fileId) throws IOException {
        Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        Files.createDirectories(dataDir);
        store.save(dataDir.resolve(fileId + ".dat").toFile(), server.registryAccess());
    }
}
