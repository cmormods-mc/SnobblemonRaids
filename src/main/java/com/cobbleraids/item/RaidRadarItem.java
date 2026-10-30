package com.cobbleraids.item;

import com.cobbleraids.radar.RaidRadarService;
import eu.pb4.polymer.core.api.item.PolymerItem;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/** Uses the vanilla filled-map renderer when open; no client code or core shaders. */
public final class RaidRadarItem extends Item implements PolymerItem {
    public static final RaidRadarItem INSTANCE = new RaidRadarItem();
    private static final String OPEN = "cobbleraids_radar_open";
    private RaidRadarItem() { super(new Properties().stacksTo(1)); }
    public static void register() {
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("cobbleraids", "raid_radar"), INSTANCE);
    }
    public static boolean isOpen(ItemStack stack) {
        return stack.is(INSTANCE) && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean(OPEN);
    }
    @Override public Item getPolymerItem(ItemStack stack, ServerPlayer player) {
        return isOpen(stack) ? Items.FILLED_MAP : Items.COMPASS;
    }
    @Override public ItemStack getPolymerItemStack(ItemStack stack, TooltipFlag flag,
                                                  HolderLookup.Provider lookup, ServerPlayer player) {
        ItemStack visual = PolymerItem.super.getPolymerItemStack(stack, flag, lookup, player);
        // Polymer 0.9.19's default component allow-list does not copy MAP_ID.
        // Without this the held map has no texture despite receiving the correct pixel packets.
        if (isOpen(stack)) visual.set(DataComponents.MAP_ID, stack.get(DataComponents.MAP_ID));
        return visual;
    }
    @Override public Component getName(ItemStack stack) { return Component.literal("Raid Radar"); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Use: raid charges / radar menu").withStyle(ChatFormatting.AQUA));
        lines.add(Component.literal("Sneak + use: next raid").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Topography of loaded terrain; direct bearing").withStyle(ChatFormatting.DARK_GRAY));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            boolean open = isOpen(stack);
            if (open && player.isShiftKeyDown()) {
                RaidRadarService.nextTarget(serverPlayer);
            } else {
                com.cobbleraids.radar.RaidRadarMenu.open(serverPlayer,hand);
            }
            player.getCooldowns().addCooldown(this, 10);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    public static void toggle(ServerPlayer player,InteractionHand hand) {
        ItemStack stack=player.getItemInHand(hand);
        if(!stack.is(INSTANCE)) return;
        boolean open=isOpen(stack);
        if(!open && !stack.has(DataComponents.MAP_ID)) stack.set(DataComponents.MAP_ID,player.server.overworld().getFreeMapId());
        if(!open) {
            ItemStack other=player.getItemInHand(hand==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);
            if(isOpen(other)) CustomData.update(DataComponents.CUSTOM_DATA,other,tag -> tag.putBoolean(OPEN,false));
        }
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag -> tag.putBoolean(OPEN,!open));
        RaidRadarService.forget(player.getUUID());
        player.displayClientMessage(Component.literal(open?"Raid Radar closed":"Raid Radar online - sneak + use to cycle raids").withStyle(ChatFormatting.AQUA),true);
    }

}
