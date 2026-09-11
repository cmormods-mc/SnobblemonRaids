package com.cobbleraids.spawn;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-definition natural-spawn cooldowns: how long after a boss spawns before that same definition
 * may be chosen again.
 *
 * <p>Split out of RaidSpawnScheduler with the tracker. It is small, but it is state with its own
 * rules -- a cooldown is measured in scheduler ticks, survives the boss it was started by, and is
 * read on every spawn attempt as a filter over ~130 definitions -- and those rules were previously
 * only observable by running a server and waiting.
 *
 * <p>Expired keys are deliberately kept rather than swept: the map is bounded by the number of
 * loaded definitions, and a sweep would be per-attempt work to reclaim a few hundred bytes.
 */
final class RaidSpawnCooldowns {

    private final Map<ResourceLocation, Long> nextAllowedTick = new HashMap<>();

    /** Whether this definition may be selected at {@code nowTick}. */
    boolean isOffCooldown(ResourceLocation definitionId, long nowTick) {
        return nowTick >= nextAllowedTick.getOrDefault(definitionId, 0L);
    }

    /** Starts a cooldown running from {@code nowTick}. */
    void start(ResourceLocation definitionId, long nowTick, int cooldownSeconds) {
        nextAllowedTick.put(definitionId, nowTick + cooldownSeconds * 20L);
    }

    /** Clears one definition's cooldown early. False if it wasn't on cooldown. */
    boolean reset(ResourceLocation definitionId) {
        return nextAllowedTick.remove(definitionId) != null;
    }

    void clear() { nextAllowedTick.clear(); }

    /**
     * Definitions still on cooldown and the seconds left on each, soonest first. Entries whose
     * cooldown has already elapsed are skipped rather than reported as zero -- they are not on
     * cooldown, and the map keeps them.
     */
    List<Map.Entry<ResourceLocation, Long>> remaining(long nowTick) {
        List<Map.Entry<ResourceLocation, Long>> remaining = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Long> entry : nextAllowedTick.entrySet()) {
            long ticksLeft = entry.getValue() - nowTick;
            if (ticksLeft > 0L) remaining.add(Map.entry(entry.getKey(), ticksLeft / 20L));
        }
        remaining.sort(Map.Entry.comparingByValue());
        return remaining;
    }
}
