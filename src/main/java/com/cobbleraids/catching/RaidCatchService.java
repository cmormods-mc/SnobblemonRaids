package com.cobbleraids.catching;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.CobbleRaidsConfigManager;
import com.cobbleraids.config.RaidDefinition;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Turns a won raid into a chance at keeping the boss.
 *
 * <p><b>A catch here copies the boss's own Pokemon rather than capturing the entity.</b> That is a
 * deliberate choice, not a shortcut. The boss entity is invulnerable, immobile, locked out of
 * ordinary battles and discarded the moment the raid finalizes -- four separate mixins exist to keep
 * it that way, and each closed a real bug. Making it ball-catchable would mean unpicking all of them
 * at exactly the moment it is being destroyed. Copying its Pokemon instead means the player keeps
 * the boss they actually fought, with its real IVs, nature, ability and shininess, and none of that
 * machinery is disturbed.
 *
 * <p>Nothing here decides <em>whether</em> a player catches -- see {@link RaidCatchPolicy}.
 */
public final class RaidCatchService {

    private RaidCatchService() {}

    /**
     * Rolls for one player and, on success, puts the boss in their party.
     *
     * @return true if they caught it.
     */
    public static boolean tryCatch(ServerPlayer player, RaidDefinition definition, Pokemon boss,
                                   double contributionPercentage, int participantCount) {
        if (player == null || definition == null || boss == null || !RaidCatchPolicy.enabled()) return false;

        RaidPlayerRecord record = RaidPlayerRecords.get(player.getUUID());
        RaidCatchPolicy.Context context =
                new RaidCatchPolicy.Context(definition, contributionPercentage, participantCount);
        double chance = RaidCatchPolicy.active().chanceFor(record, context);
        // At or below zero means the mechanic is declining outright, so say nothing: a "you failed"
        // message for a tier that was never catchable is just noise after every raid.
        if (chance <= 0.0) return false;

        // Checked before the roll, so a player with nowhere to put the boss hears that instead of
        // winning a roll for a Pokemon that then cannot be kept.
        var storage = Cobblemon.INSTANCE.getStorage();
        if (storage.getParty(player).getFirstAvailablePosition() == null
                && storage.getPC(player).getFirstAvailablePosition() == null) {
            player.sendSystemMessage(Component.literal(
                            "Your party and PC are both full, so there was no room to catch the raid boss.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        boolean caught = ThreadLocalRandom.current().nextDouble() < chance;
        if (CobbleRaidsConfigManager.get().debugLogging()) {
            RaidLog.info("Catch roll for {} on {}: chance={}, caught={}",
                    player.getGameProfile().getName(), definition.id(), chance, caught);
        }
        if (!caught) {
            player.sendSystemMessage(Component.literal(String.format(
                            "The boss broke free. (%.0f%% chance)", chance * 100.0))
                    .withStyle(ChatFormatting.GRAY));
            return false;
        }
        return award(player, definition, boss);
    }

    private static boolean award(ServerPlayer player, RaidDefinition definition, Pokemon boss) {
        try {
            // A copy, never the boss's own instance: that one is still wired into the finishing
            // battle and is about to be discarded with its entity.
            Pokemon caught = boss.clone(true, player.registryAccess());
            // Uncatchable belongs to the boss standing in the world, not to the copy a player earns.
            UncatchableProperty.INSTANCE.catchable().apply(caught);
            caught.heal();

            PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
            // Cobblemon's PlayerPartyStore.add already sends a full party's Pokemon to the PC and
            // tells the player so. False means the PC refused too, which tryCatch checks for before
            // rolling -- so reaching here means storage filled up in between. Nothing was delivered,
            // so nothing may be counted or announced as a catch.
            if (!party.add(caught)) {
                RaidLog.error("Caught boss {} could not be stored for {}: party and PC are full",
                        definition.id(), player.getGameProfile().getName());
                player.sendSystemMessage(Component.literal(
                                "You caught the boss, but your party and PC are full, so it could not be kept.")
                        .withStyle(ChatFormatting.RED));
                return false;
            }

            RaidPlayerRecords.recordCatch(player.getServer(), player.getUUID());
            player.sendSystemMessage(Component.literal("You caught the raid boss!")
                    .withStyle(ChatFormatting.GOLD));
            return true;
        } catch (RuntimeException ex) {
            // A failed award must not take the reward screen or the raid's finalization with it.
            RaidLog.error("Failed to award caught boss {} to {}",
                    definition.id(), player.getGameProfile().getName(), ex);
            player.sendSystemMessage(Component.literal(
                            "You caught the boss, but it could not be added. Tell an administrator.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
    }
}
