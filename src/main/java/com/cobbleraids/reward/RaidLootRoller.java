package com.cobbleraids.reward;

import com.cobbleraids.RaidLog;
import com.cobbleraids.config.RaidDefinition;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * Rolls a real Minecraft loot table as a raid reward.
 *
 * <p>This is the cheap way to hand out another mod's loot. Listing items one by one means knowing
 * every id and re-tuning weights by hand; naming a loot table borrows whatever that mod already
 * balanced, and keeps working when the mod updates its own drops.
 *
 * <p><b>Never throws.</b> A raid reward is granted once and the claim is consumed, so a missing
 * table, a table that wants context a raid cannot supply, or a mod that has since been uninstalled
 * must cost the player that table's contents and nothing more. Every failure is a warning naming
 * the table, and the rest of the reward is still granted.
 */
public final class RaidLootRoller {
    private RaidLootRoller() {}

    /**
     * Rolls every table and places the results in the player's inventory.
     *
     * @param context what to name in a warning -- a definition id, or where the roll came from.
     * @return one display line per stack granted, so loot-table drops appear in the claim message
     *         and the reveal screen exactly like hand-listed items do.
     */
    public static List<RaidDefinition.RewardItem> rollAll(
            ServerPlayer player, List<ResourceLocation> tables, Object context) {
        return rollAll(player, tables, context, LootTable.RANDOMIZE_SEED);
    }

    /**
     * As {@link #rollAll(ServerPlayer, List, Object)}, but with the roll fixed by a seed.
     *
     * <p>{@link LootTable#RANDOMIZE_SEED} means "pick your own", which is the unseeded behaviour.
     * Any other value makes the roll reproducible, which is how an unclaimed reward comes back as
     * the same reward after a restart.
     */
    public static List<RaidDefinition.RewardItem> rollAll(
            ServerPlayer player, List<ResourceLocation> tables, Object context, long seed) {
        if (tables.isEmpty()) return List.of();
        List<RaidDefinition.RewardItem> granted = new ArrayList<>();
        for (ResourceLocation tableId : tables) {
            try {
                for (ItemStack stack : roll(player, tableId, context, seed)) {
                    // Read the line before granting: placeItemBackInInventory consumes the stack, so
                    // one inspected afterwards reports minecraft:air and a count of zero.
                    granted.add(new RaidDefinition.RewardItem(
                            BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(), 1.0, 1));
                    player.getInventory().placeItemBackInInventory(stack);
                }
            } catch (RuntimeException ex) {
                // Most often a table whose own parameter set demands context a raid has no way to
                // provide -- an entity table wanting the killed entity, say.
                warn(context, tableId, "could not be rolled (" + ex + ")");
            }
        }
        return granted;
    }

    /**
     * Rolls a table and returns the stacks without granting them, which is also what
     * {@code /cobbleraids debug loot} shows: an operator can see what a table produces before
     * wiring it into a definition, using the same context and the same failure handling, so a
     * preview that works is a reward that will work.
     */
    public static List<ItemStack> roll(ServerPlayer player, ResourceLocation tableId, Object context) {
        return roll(player, tableId, context, LootTable.RANDOMIZE_SEED);
    }

    /** As {@link #roll(ServerPlayer, ResourceLocation, Object)}, with the roll fixed by a seed. */
    public static List<ItemStack> roll(ServerPlayer player, ResourceLocation tableId, Object context, long seed) {
        ServerLevel level = player.serverLevel();
        LootTable table = level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, tableId));
        // Vanilla answers a missing id with the empty table rather than null, so an id that never
        // resolved would otherwise look like a table that legitimately rolled nothing.
        if (table == LootTable.EMPTY) {
            warn(context, tableId, "does not exist; is the mod that owns it installed?");
            return List.of();
        }

        // CHEST is the set almost every "loot" table in the wild declares, and ORIGIN is the only
        // parameter it requires. THIS_ENTITY is supplied as optional so tables that look at the
        // player (for looting, luck, or a predicate) work too, without being required.
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                .create(LootContextParamSets.CHEST);

        List<ItemStack> rolled = new ArrayList<>();
        table.getRandomItems(params, seed, stack -> {
            if (!stack.isEmpty()) rolled.add(stack);
        });
        return rolled;
    }

    /**
     * Whether a loot table is loaded. Used to ask "does this boss have its own specialty table?"
     * without anything having to hold a list of which bosses do -- adding one becomes a datapack
     * change and nothing in the code has to learn about it.
     *
     * <p>Silent by design: a boss with no table of its own is the normal case for 109 of the 130,
     * not a problem to report.
     */
    public static boolean exists(ServerPlayer player, ResourceLocation tableId) {
        if (tableId == null) return false;
        LootTable table = player.serverLevel().getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, tableId));
        return table != LootTable.EMPTY;
    }

    private static void warn(Object context, ResourceLocation tableId, String problem) {
        RaidLog.error("" + context + " reward loot table '" + tableId + "' " + problem);
    }
}
