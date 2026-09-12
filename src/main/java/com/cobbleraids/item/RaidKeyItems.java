package com.cobbleraids.item;

import com.cobbleraids.config.RaidRarityTier;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The only items CobbleRaids registers: a key fragment and a key, one pair per rarity tier.
 *
 * <p>These exist to be spent somewhere else. A tier's key opens that tier's crate in a separate
 * mod, so the contract that matters is the pair of item tags -- {@code #cobbleraids:raid_keys} and
 * the per-tier {@code #cobbleraids:raid_keys/<tier>} -- rather than the ids below. A crate mod that
 * matches the tag needs no compile-time dependency on this one and keeps working if a datapack
 * later adds a key of its own.
 *
 * <p>They live here rather than in the crate mod because raids are the only source. An item in the
 * crate mod would have to be named from a loot table through the optional-table wrapper, since
 * Minecraft rejects an entire loot table that names an unregistered id -- and a reward that
 * silently stops dropping when a crate mod is uninstalled is the failure this project has already
 * paid for once.
 *
 * <p>A fragment stacks to {@link #FRAGMENTS_PER_KEY}, so a full stack is exactly one key and a
 * player reads their progress off the stack count without a tooltip. That is also why combining is
 * a right-click rather than a recipe: at a stack size of six there is no way to put six in a
 * crafting grid from one slot anyway.
 */
public final class RaidKeyItems {
    private RaidKeyItems() {}

    /** Fragments in a key. Also the fragment's max stack size, deliberately. */
    public static final int FRAGMENTS_PER_KEY = 6;

    private static final Map<RaidRarityTier, Item> FRAGMENTS = new EnumMap<>(RaidRarityTier.class);
    private static final Map<RaidRarityTier, Item> KEYS = new EnumMap<>(RaidRarityTier.class);

    private static final ResourceKey<CreativeModeTab> TAB_KEY = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath("cobbleraids", "raid_keys"));

    public static void register() {
        // Idempotent because a second call would throw on the duplicate id and take the whole mod
        // down at load; the entrypoint is the only caller, but a registry is exactly the sort of
        // thing a future refactor calls twice.
        if (!FRAGMENTS.isEmpty()) return;
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            Item key = register(keyId(tier), new Item(new Item.Properties().stacksTo(16)));
            KEYS.put(tier, key);
            FRAGMENTS.put(tier, register(fragmentId(tier),
                    new RaidKeyFragmentItem(new Item.Properties().stacksTo(FRAGMENTS_PER_KEY), tier)));
        }
        // FabricItemGroup, not CreativeModeTab.builder(): vanilla's takes a (Row, int) slot
        // position reserved for its own tabs, which a mod has no business claiming.
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_KEY, FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.cobbleraids.raid_keys"))
                .icon(() -> new ItemStack(KEYS.get(RaidRarityTier.LEGENDARY)))
                .displayItems((parameters, output) -> {
                    for (RaidRarityTier tier : RaidRarityTier.values()) {
                        output.accept(FRAGMENTS.get(tier));
                        output.accept(KEYS.get(tier));
                    }
                })
                .build());
    }

    private static Item register(ResourceLocation id, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, id, item);
    }

    public static ResourceLocation fragmentId(RaidRarityTier tier) {
        return ResourceLocation.fromNamespaceAndPath(
                "cobbleraids", tier.serializedName() + "_raid_key_fragment");
    }

    public static ResourceLocation keyId(RaidRarityTier tier) {
        return ResourceLocation.fromNamespaceAndPath(
                "cobbleraids", tier.serializedName() + "_raid_key");
    }

    /** Null before {@link #register()} runs, which is only possible off the server thread. */
    public static Item key(RaidRarityTier tier) {
        return KEYS.get(tier);
    }

    public static Item fragment(RaidRarityTier tier) {
        return FRAGMENTS.get(tier);
    }
}
