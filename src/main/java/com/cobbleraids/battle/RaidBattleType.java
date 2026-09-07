package com.cobbleraids.battle;

import com.cobblemon.mod.common.battles.BattleType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Marker format for the raid transport. The actual actor counts are supplied by the raid factory;
 * this type is intentionally not used to validate the participants like BattleBuilder.pvp2v2 does.
 *
 * The counts here deliberately stay at 1. Cobblemon multiplies them into
 * BattleType.getPokemonPerSide(), which Targetable.getAdjacent() uses as the field width for a
 * two-symmetric-sides adjacency model -- a model a raid does not fit, since one boss faces up to
 * four separately-actored players. No width makes every player adjacent to the boss (the boss
 * mirrors to a single position, so players at both ends of the line cannot both be within one step
 * of it), which is why raid adjacency is replaced outright in RaidTargetAdjacencyMixin rather than
 * tuned through these numbers.
 */
public final class RaidBattleType implements BattleType {
    public static final RaidBattleType INSTANCE = new RaidBattleType();
    private RaidBattleType() {}
    @Override public String getName() { return "raid"; }
    @Override public MutableComponent getDisplayName() { return Component.literal("Raid"); }
    @Override public int getActorsPerSide() { return 1; }
    @Override public int getSlotsPerActor() { return 1; }
}
