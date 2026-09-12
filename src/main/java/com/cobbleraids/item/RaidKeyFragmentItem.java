package com.cobbleraids.item;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.presentation.RaidTierPresentation;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
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
        take(player, keys * RaidKeyItems.FRAGMENTS_PER_KEY);
        give(player, new ItemStack(RaidKeyItems.key(tier), keys));
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.PLAYERS, 0.7F, 1.0F);
        return InteractionResultHolder.consume(held);
    }

    private int countOwned(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == this) total += stack.getCount();
        }
        return total;
    }

    /** Removes exactly {@code wanted}; the caller has already proved that many exist. */
    private void take(Player player, int wanted) {
        int left = wanted;
        for (ItemStack stack : player.getInventory().items) {
            if (left <= 0) break;
            if (stack.getItem() != this) continue;
            int taken = Math.min(left, stack.getCount());
            stack.shrink(taken);
            left -= taken;
        }
    }

    /** Keys that do not fit are dropped rather than voided; add() mutates the remainder in place. */
    private static void give(Player player, ItemStack keys) {
        if (!player.getInventory().add(keys) && !keys.isEmpty()) {
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
