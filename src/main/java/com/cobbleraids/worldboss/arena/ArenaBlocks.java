package com.cobbleraids.worldboss.arena;

import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Registers the two arena marker blocks and their items: {@code cobbleraids:world_boss_spawn} and {@code cobbleraids:world_boss_entrance}.
 * Give one with {@code /give @s cobbleraids:world_boss_spawn} and place it where the boss should stand.
 */
public final class ArenaBlocks {
    private ArenaBlocks() {}

    public static Block spawn;
    public static Block entrance;

    /** Idempotent: a second call would throw on the duplicate id. */
    public static void register() {
        if (spawn != null) return;
        spawn = block("world_boss_spawn", ArenaMarkers.Kind.SPAWN, "World Boss Spawn Marker");
        entrance = block("world_boss_entrance", ArenaMarkers.Kind.ENTRANCE, "World Boss Arena Entrance Marker");
    }

    private static Block block(String path, ArenaMarkers.Kind kind, String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cobbleraids", path);
        Block block = Registry.register(BuiltInRegistries.BLOCK, id, new ArenaMarkerBlock(
                BlockBehaviour.Properties.of().strength(-1.0f, 3_600_000.0f).noCollission().noLootTable().noOcclusion(), kind));
        Registry.register(BuiltInRegistries.ITEM, id, new PolymerBlockItem(block,
                new Item.Properties().component(DataComponents.ITEM_NAME, Component.literal(name)), Items.STRUCTURE_VOID));
        return block;
    }
}
