package com.cobbleraids.client.shop;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The Raid Capture Protocol chamber's species preview: this package's own sprite/model provider,
 * exposed to {@code client.capture} without making {@link ShopSpeciesIcons} or
 * {@link ShopPokemonPortraits} themselves public.
 */
public final class CapturePokemonPreview {
    private CapturePokemonPreview() {}

    /** @return false when there is nothing to draw, so the caller can fall back to its own placeholder */
    public static boolean draw(GuiGraphics g, ResourceLocation species, boolean shiny,
                                int x, int y, int size, float partialTick) {
        ResourceLocation icon = ShopSpeciesIcons.texture(species.getPath(), shiny);
        if (icon != null) {
            int height = size * ShopSpeciesIcons.HEIGHT / ShopSpeciesIcons.WIDTH;
            g.blit(icon, x, y + (size - height) / 2, size, height, 0, 0,
                    ShopSpeciesIcons.WIDTH, ShopSpeciesIcons.HEIGHT, ShopSpeciesIcons.WIDTH, ShopSpeciesIcons.HEIGHT);
            return true;
        }
        return ShopPokemonPortraits.draw(g, species.getPath(), shiny, x, y, size, partialTick);
    }

    /** Releases whatever live-model render state {@link ShopPokemonPortraits} may be holding. */
    public static void clear() {
        ShopPokemonPortraits.clear();
    }
}
