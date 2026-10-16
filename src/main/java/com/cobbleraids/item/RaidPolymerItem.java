package com.cobbleraids.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * An item that exists only on the server. A client is told it is {@code base}, a vanilla item, with
 * a custom model number; the resource pack Polymer builds ({@link RaidItemPack}) maps that number to
 * this mod's own model and texture. A client therefore needs nothing installed to hold one.
 */
public class RaidPolymerItem extends Item implements PolymerItem {
    private final Item base;
    private final PolymerModelData model;
    private final Component name;
    private final List<Component> tooltip;

    /**
     * {@code modelPath} is the file under {@code assets/cobbleraids/models/item/}, without extension.
     * {@code displayName} is sent as plain text: a client without this mod has no language file that
     * could translate a key, so a translatable name would show up as the raw key.
     */
    public RaidPolymerItem(Properties properties, Item base, String modelPath, String displayName) {
        this(properties, base, modelPath, displayName, List.of());
    }

    /** {@code tooltip} is shown under the name, as plain text for the same reason as the name. */
    public RaidPolymerItem(Properties properties, Item base, String modelPath, String displayName, List<String> tooltip) {
        super(properties);
        this.base = base;
        this.name = Component.literal(displayName);
        this.tooltip = tooltip.stream().map(line -> (Component) Component.literal(line).withStyle(ChatFormatting.GRAY)).toList();
        this.model = PolymerResourcePackUtils.requestModel(base,
                ResourceLocation.fromNamespaceAndPath("cobbleraids", "item/" + modelPath));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.addAll(tooltip);
    }

    @Override
    public Component getName(ItemStack stack) {
        return name;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, ServerPlayer player) {
        return base;
    }

    @Override
    public int getPolymerCustomModelData(ItemStack stack, ServerPlayer player) {
        return model.value();
    }
}
