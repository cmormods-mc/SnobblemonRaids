package com.cobbleraids.config;

import com.cobbleraids.RaidLog;
import com.cobbleraids.reward.plan.RewardPlanResolver;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        List<ResourceLocation> rejected = new ArrayList<>();
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
                // Skip the file rather than failing prepare(). Throwing here fails the whole resource
                // reload, and a dedicated server that cannot complete its initial reload refuses to
                // start -- so one malformed json in any third-party datapack, in a namespace anyone
                // can write to, would take the server down rather than take itself out of the pool.
                rejected.add(id);
                RaidLog.error("Skipping malformed raid definition {}; the other definitions still load.", id, ex);
            }
        });
        if (!rejected.isEmpty()) {
            // One summary line as well: the per-file errors above are easy to scroll past, and an
            // operator whose raid never spawns needs to find out here rather than in-game.
            RaidLog.error("{} raid definition(s) were skipped as malformed and will not spawn: {}", rejected.size(),
                    rejected.stream().map(ResourceLocation::toString).sorted().collect(Collectors.joining(", ")));
        }
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
        reportRewardPaths(prepared);
    }

    /**
     * One line saying how many definitions the reward policy actually governs.
     *
     * <p>A definition that still names rewards of its own quietly ignores the policy, which is the
     * shape an unfinished migration leaves behind: nothing is broken, nothing is logged, and the
     * boss simply keeps handing out its old loot. Counting them at load turns that into something
     * an operator can see, and RaidConsistencyAudit names the stragglers individually.
     */
    private static void reportRewardPaths(Map<ResourceLocation, RaidDefinition> prepared) {
        List<String> legacy = prepared.entrySet().stream()
                .filter(entry -> isSelfDescribing(entry.getValue()))
                .map(entry -> entry.getKey().toString())
                .sorted()
                .toList();
        if (legacy.isEmpty()) {
            RaidLog.info("{} raid definition(s) loaded, all governed by the reward policy.", prepared.size());
        } else {
            RaidLog.warn("{} of {} raid definition(s) name rewards of their own and ignore the reward"
                    + " policy: {}", legacy.size(), prepared.size(), String.join(", ", legacy));
        }
    }

    /** True when this definition describes its own rewards; see RewardPlanResolver for the rule. */
    public static boolean isSelfDescribing(RaidDefinition definition) {
        RaidDefinition.Rewards rewards = definition.rewards();
        if (rewards == null) return false;
        if (RewardPlanResolver.isSelfDescribing(rewards, null)) return true;
        for (RaidDefinition.RewardChoice choice : rewards.choices().values()) {
            if (RewardPlanResolver.isSelfDescribing(rewards, choice)) return true;
        }
        return false;
    }
}
