package com.cobbleraids.catching;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.renown.RenownBoon;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * A server-wide "first ever" record: the first time this exact renowned title was beaten on this
 * exact species, anywhere on the server. Keyed by {@link LegendKey} rather than species alone --
 * the same title text can be rolled independently onto different species over time (see
 * {@code RenownPools.draw}), so "first ever Relentless" would conflate unrelated bosses; "first ever
 * Relentless Gyarados" is a specific, checkable claim.
 *
 * <p>{@code victorIds}/{@code victorNames} are snapshotted at the moment of victory, not re-resolved
 * later -- the same "store values, not pointers to live state" discipline {@link BossSnapshot} and
 * {@link com.cobbleraids.renown.RaidRenown} both follow. A name frozen at record time outlives a
 * player's later name change or a `/reload`; re-resolving later would also require the player to
 * still be online. Bounded at {@code CobbleRaidsConfig.VALIDATED_MAX_HUMAN_PLAYERS} (4) by
 * construction, since a raid's own party size is capped there.
 */
public record LegendEntry(
        String title,
        ResourceLocation species,
        RaidRarityTier tier,
        RenownBoon boon,
        long defeatedAtEpochMs,
        List<UUID> victorIds,
        List<String> victorNames
) {
    public LegendEntry {
        victorIds = List.copyOf(victorIds);
        victorNames = List.copyOf(victorNames);
        if (victorIds.size() != victorNames.size()) {
            throw new IllegalArgumentException("victorIds and victorNames must be the same length");
        }
    }

    public LegendKey key() {
        return new LegendKey(title, species);
    }
}
