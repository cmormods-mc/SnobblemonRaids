package com.cobbleraids.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-authoritative raid definitions loaded from data/cobbleraids/raids/*.json. */
public final class RaidDefinitionRegistry extends SimplePreparableReloadListener<Map<ResourceLocation, RaidDefinition>> implements IdentifiableResourceReloadListener {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("cobbleraids", "raid_definitions");
    private static final Gson GSON = new Gson();
    private static volatile Map<ResourceLocation, RaidDefinition> DEFINITIONS = Map.of();

    // Command tab completion runs per keystroke, per player. Deriving and sorting these from
    // DEFINITIONS on every one of those was ~130 entries of stream + sort each time, on the server
    // thread, multiplied by however many people are typing. They only ever change when a datapack
    // reload swaps DEFINITIONS, so they are built once there instead and handed out as-is.
    private static volatile List<String> SPECIES_NAMES = List.of();
    private static volatile List<ResourceLocation> SORTED_IDS = List.of();

    public static RaidDefinition get(ResourceLocation id) { return DEFINITIONS.get(id); }
    // Map.of()/Map.copyOf(...) both produce an already-unmodifiable values() view, so DEFINITIONS
    // never needs an extra defensive wrapper here.
    public static Collection<RaidDefinition> all() { return DEFINITIONS.values(); }

    /** Distinct species paths, sorted. Precomputed for tab completion; already immutable. */
    public static List<String> speciesNames() { return SPECIES_NAMES; }

    /** Every definition id, sorted. Precomputed for tab completion; already immutable. */
    public static List<ResourceLocation> sortedIds() { return SORTED_IDS; }

    /** First definition using this species path, or null. Case-insensitive. */
    public static RaidDefinition findSpecies(String pokemonName) {
        for (RaidDefinition definition : DEFINITIONS.values()) {
            if (definition.species().getPath().equalsIgnoreCase(pokemonName)) return definition;
        }
        return null;
    }

    /** Every loaded cobblemon:* definition whose species path matches, case-insensitive, sorted by id. */
    public static List<RaidDefinition> findBySpeciesName(String pokemonName) {
        return all().stream()
                .filter(definition -> definition.species().getNamespace().equals("cobblemon"))
                .filter(definition -> definition.species().getPath().equalsIgnoreCase(pokemonName))
                .sorted(Comparator.comparing(definition -> definition.id().toString()))
                .toList();
    }

    @Override public ResourceLocation getFabricId() { return ID; }

    @Override
    protected Map<ResourceLocation, RaidDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, RaidDefinition> loaded = new LinkedHashMap<>();
        manager.listResources("raids", path -> path.getNamespace().equals("cobbleraids") && path.getPath().endsWith(".json")).forEach((path, resource) -> {
            String prefix = "raids/";
            String relative = path.getPath().startsWith(prefix) ? path.getPath().substring(prefix.length()) : path.getPath();
            if (!relative.endsWith(".json")) return;
            String logicalPath = relative.substring(0, relative.length() - 5);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(path.getNamespace(), logicalPath);
            try (Reader reader = resource.openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                loaded.put(id, RaidDefinition.fromJson(id, json));
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load raid definition " + id, ex);
            }
        });
        return Map.copyOf(loaded);
    }

    @Override
    protected void apply(Map<ResourceLocation, RaidDefinition> prepared, ResourceManager manager, ProfilerFiller profiler) {
        DEFINITIONS = prepared;
        SPECIES_NAMES = prepared.values().stream()
                .map(definition -> definition.species().getPath())
                .distinct()
                .sorted()
                .toList();
        SORTED_IDS = prepared.keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
    }
}
