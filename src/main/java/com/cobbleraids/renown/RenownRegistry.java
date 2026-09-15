package com.cobbleraids.renown;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads renown word lists from {@code data/<namespace>/renown/*.json} in any namespace, so a
 * server's own datapack can add names without touching this jar.
 *
 * <p>Files are read in id order, which makes duplicate resolution -- first spelling wins --
 * the same on every boot rather than whatever order the resource manager happened to list them.
 */
public final class RenownRegistry extends SimplePreparableReloadListener<RenownPools>
        implements IdentifiableResourceReloadListener {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("cobbleraids", "renown");
    private static volatile RenownPools POOLS = RenownPools.EMPTY;

    public static RenownPools pools() { return POOLS; }

    @Override public ResourceLocation getFabricId() { return ID; }

    @Override
    protected RenownPools prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, JsonObject> files = new TreeMap<>();
        manager.listResources("renown", path -> path.getPath().endsWith(".json")).forEach((path, resource) -> {
            try (Reader reader = resource.openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) files.put(path.toString(), parsed.getAsJsonObject());
                else RaidLog.error("Skipping renown file {}: the top level must be an object.", path);
            } catch (Exception ex) {
                // Same rule as raid definitions: a broken file takes itself out, never the reload.
                RaidLog.error("Skipping malformed renown file {}; the other word lists still load.", path, ex);
            }
        });
        return RenownPools.parse(files, message -> RaidLog.warn("Renown word lists: {}", message));
    }

    @Override
    protected void apply(RenownPools prepared, ResourceManager manager, ProfilerFiller profiler) {
        POOLS = prepared;
        if (prepared.names().isEmpty() || prepared.epithets().isEmpty()) {
            RaidLog.warn("Renown word lists are empty ({} name(s), {} epithet(s)); no boss can be renowned.",
                    prepared.names().size(), prepared.epithets().size());
        } else {
            RaidLog.info("{} renown name(s) and {} epithet(s) loaded.", prepared.names().size(), prepared.epithets().size());
        }
    }
}
