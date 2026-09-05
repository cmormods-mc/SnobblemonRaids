package com.cobbleraids.battle;

import com.cobblemon.mod.common.battles.BattleType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Marker format for the raid transport. The actual actor counts are supplied by the raid factory;
 * this type is intentionally not used to validate the participants like BattleBuilder.pvp2v2 does.
 */
public final class RaidBattleType implements BattleType {
    public static final RaidBattleType INSTANCE = new RaidBattleType();
    private RaidBattleType() {}
    @Override public String getName() { return "raid"; }
    @Override public MutableComponent getDisplayName() { return Component.literal("Raid"); }
    @Override public int getActorsPerSide() { return 1; }
    @Override public int getSlotsPerActor() { return 1; }
}
