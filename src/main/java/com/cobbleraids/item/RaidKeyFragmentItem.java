package com.cobbleraids.item;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.RaidLog;
import com.cobbleraids.presentation.RaidTierPresentation;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * A raid key fragment. Right-clicking turns every complete set of six in the held stack into keys.
 *
 * <p>Combining is a use action rather than a recipe because the fragment stacks to six: there is no
 * way to place six of them into a crafting grid from a single slot, so a shapeless recipe would
 * have needed a larger stack and lost the "a full stack is one key" reading.
 *
 * <p>It counts the whole inventory and converts every complete set at once. A fragment stacks to
 * six, so a player holding three keys' worth necessarily has three slots of them, and converting
 * only the held stack would cost a click per slot to no purpose.
 */
public class RaidKeyFragmentItem extends Item {

    private final RaidRarityTier tier;

    public RaidKeyFragmentItem(Properties properties, RaidRarityTier tier) {
        super(properties);
        this.tier = tier;
    }

    public RaidRarityTier tier() {
        return tier;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        // Whole inventory, not the held stack. A fragment stacks to six, so a player holding two
        // keys' worth necessarily has them in two slots, and converting per-stack would mean
        // clicking once per slot for no reason a player could explain.
        int owned = countOwned(player);
        int keys = owned / RaidKeyItems.FRAGMENTS_PER_KEY;
        if (keys <= 0) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable(
                        "item.cobbleraids.raid_key_fragment.need_more",
                        RaidKeyItems.FRAGMENTS_PER_KEY - owned)
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResultHolder.fail(held);
        }
        if (level.isClientSide) {
            // Nothing is predicted client-side; the swap happens on the server and syncs back.
            // Reporting success on both sides is what keeps the arm swing from desyncing.
            return InteractionResultHolder.success(held);
        }
        // Built before anything is spent, the same order the shop purchase path uses: everything
        // that can fail happens before the irreversible step, so there is no state where the
        // fragments are gone and the key was never made.
        ItemStack made = new ItemStack(RaidKeyItems.key(tier), keys);
        if (made.isEmpty()) {
            RaidLog.error("No registered key for tier " + tier.serializedName()
                    + "; leaving the fragments alone");
            return InteractionResultHolder.fail(held);
        }
        take(player, keys * RaidKeyItems.FRAGMENTS_PER_KEY);
        // Before the branch below, so combining sounds the same however the key is delivered.
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.PLAYERS, 0.7F, 1.0F);
        if (held.isEmpty()) {
            // The hand slot is the one we just emptied, and vanilla ends a use by overwriting the
            // hand with ItemStack.EMPTY whenever the stack we return is empty --
            // ServerPlayerGameMode.useItem does setItemInHand(hand, EMPTY) after us. Inventory.add
            // frequently picks that same freshly-empty slot for the key, and vanilla then deletes
            // it: the fragments are spent and nothing comes back. Handing the key over as the
            // result puts it in the hand instead, which is also where the player expects it.
            return InteractionResultHolder.consume(made);
        }
        give(player, made);
        return InteractionResultHolder.consume(held);
    }

    /**
     * Every slot, walked through the container interface rather than {@code getInventory().items}.
     *
     * <p>That field is only the 36 main slots: armour and the offhand are separate compartments.
     * Reading it meant a player holding six fragments in their offhand and nothing elsewhere was
     * told they needed six more, while right-clicking the six they were holding.
     */
    private int countOwned(Player player) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() == this) total += stack.getCount();
        }
        return total;
    }

    /** Removes exactly {@code wanted}; the caller has already proved that many exist. */
    private void take(Player player, int wanted) {
        Inventory inventory = player.getInventory();
        int left = wanted;
        for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() != this) continue;
            int taken = Math.min(left, stack.getCount());
            stack.shrink(taken);
            left -= taken;
        }
    }

    /**
     * Keys that do not fit are dropped rather than voided.
     *
     * <p>The return value of add() is deliberately ignored. It reports whether <em>any</em> of the
     * stack was placed, not all of it, so a partial insert into a nearly full inventory returned
     * true and the remainder -- which add() has already mutated the stack down to -- was silently
     * thrown away. What is left in the stack afterwards is the only thing worth asking about.
     */
    private static void give(Player player, ItemStack keys) {
        player.getInventory().add(keys);
        if (!keys.isEmpty()) {
            player.drop(keys, false);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        lines.add(Component.translatable("item.cobbleraids.raid_key_fragment.tooltip",
                        RaidKeyItems.FRAGMENTS_PER_KEY)
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.cobbleraids.raid_key_fragment.tier",
                        Component.literal(tier.displayName())
                                .withStyle(RaidTierPresentation.color(tier)))
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
