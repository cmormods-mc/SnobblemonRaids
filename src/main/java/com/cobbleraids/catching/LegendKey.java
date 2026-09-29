package com.cobbleraids.catching;

import net.minecraft.resources.ResourceLocation;

/** A renowned title paired with the species it was rolled on; see {@link LegendEntry}. */
public record LegendKey(String title, ResourceLocation species) {
}
