package com.cobbleraids.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Handing a player a quantity of an item that may exceed one stack.
 *
 * <p>Was duplicated verbatim between the reward engine and the shop before being collapsed here --
 * split into max-size stacks, place each one via {@code Inventory.placeItemBackInInventory}, which
 * already drops whatever will not fit at the player's feet rather than destroying it. That drop
 * behavior lives inside the vanilla method itself (confirmed by disassembling it), not in this
 * class, so there is nothing further to do here for a full inventory.
 */
public final class ItemGiving {
    private ItemGiving() {}

    public static void giveStacked(ServerPlayer player, Item item, int count) {
        int remaining = count;
        while (remaining > 0) {
            ItemStack stack = new ItemStack(item);
            int amount = Math.min(remaining, stack.getMaxStackSize());
            stack.setCount(amount);
            player.getInventory().placeItemBackInInventory(stack);
            remaining -= amount;
        }
    }
}
