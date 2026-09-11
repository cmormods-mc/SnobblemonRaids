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
            boolean intoParty = party.add(caught);
            if (!intoParty) {
                // add() refuses a full party, and silently dropping a caught raid boss would be the
                // worst possible failure here.
                Cobblemon.INSTANCE.getStorage().getPC(player).add(caught);
            }

            RaidPlayerRecords.recordCatch(player.getServer(), player.getUUID());
            player.sendSystemMessage(Component.literal("You caught the raid boss!")
                    .withStyle(ChatFormatting.GOLD));
            if (!intoParty) {
                player.sendSystemMessage(Component.literal("Your party was full, so it went to your PC.")
                        .withStyle(ChatFormatting.YELLOW));
            }
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
