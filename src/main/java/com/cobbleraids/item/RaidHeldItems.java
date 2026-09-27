package com.cobbleraids.item;

import com.cobblemon.mod.common.CobblemonItemComponents;
import com.cobblemon.mod.common.item.components.HeldItemEffectComponent;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Held items obtainable only from raid loot -- see the raid-exclusive-held-items plan.
 *
 * <p>{@link #GUARDIAN_SCALE} carries a real Cobblemon {@link HeldItemEffectComponent}: a data
 * component (not a namespace restriction) that tells Cobblemon's held-item bridge which Showdown
 * item id to send into battle for this exact ItemStack -- see
 * {@code BaseCobblemonHeldItemManager.showdownId}, which reads this component first and only falls
 * back to guessing an id from a {@code cobblemon:}-namespaced registry name when it is absent. That
 * is what lets a mod outside Cobblemon's own namespace carry a real battle effect at all, and it is
 * the same mechanism {@code minecraft:charcoal} already rides in {@link
 * com.cobbleraids.config.RaidBossTraits}'s held-item allowlist. The effect itself
 * ("survive lethal damage at reduced HP, once") lives in {@code showdown/mods/items.js}, installed
 * by {@code ShowdownIntegrationInstaller} the same way {@code raid-patch.js} already is.
 *
 * <p>{@link #RAID_CORE} carries no such component: its effect is a Java-side Raid Points bonus
 * checked at reward-grant time ({@code RaidRewardGrantEngine.awardPoints}), not a battle mechanic,
 * so it has nothing to tell Showdown.
 */
public final class RaidHeldItems {
    private RaidHeldItems() {}

    /** Must match {@code showdown/mods/items.js}'s own key for Guardian Scale exactly. */
    public static final String GUARDIAN_SCALE_SHOWDOWN_ID = "cobbleraidsguardianscale";

    private static Item raidCore;
    private static Item guardianScale;

    public static void register() {
        // Idempotent for the same reason RaidKeyItems.register() is: a second call would throw on
        // the duplicate id and take the whole mod down at load.
        if (raidCore != null) return;
        raidCore = register("raid_core", new Item(new Item.Properties().stacksTo(1)));
        guardianScale = register("guardian_scale", new Item(new Item.Properties().stacksTo(1)
                .component(CobblemonItemComponents.HELD_ITEM_EFFECT,
                        new HeldItemEffectComponent(GUARDIAN_SCALE_SHOWDOWN_ID, true))));
    }

    private static Item register(String path, Item item) {
        return Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath("cobbleraids", path), item);
    }

    /** Null before {@link #register()} runs, which is only possible off the server thread. */
    public static Item raidCore() {
        return raidCore;
    }

    public static Item guardianScale() {
        return guardianScale;
    }
}
