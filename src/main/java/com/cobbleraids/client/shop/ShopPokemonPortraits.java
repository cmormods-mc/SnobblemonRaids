package com.cobbleraids.client.shop;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.client.gui.PokemonGuiUtilsKt;
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState;
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobbleraids.RaidLog;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

/**
 * Drawing a live Cobblemon model inside a shop cell.
 *
 * <p>Cobblemon ships no 2D sprite for a species -- the only sprites in the jar are vanilla's -- so
 * a Pokemon in the grid is a real 3D model with a real animation state. That is expensive, and the
 * cost is per cell per frame, so two things keep it bounded: the state is cached per species-and-
 * aspect rather than per cell, and the caller only draws the cells that hold Pokemon.
 *
 * <p>Everything is looked up lazily and failures are swallowed. A species id an operator mistyped
 * must leave an empty cell, not a crash screen: the rest of the shop still works, and the purchase
 * path refuses the same id server-side anyway.
 */
final class ShopPokemonPortraits {

    /** One state per species-and-aspect. Sixty-four cells of Charizard animate as one. */
    private static final Map<String, Entry> CACHE = new HashMap<>();
    /** Species that failed to resolve, so a typo is looked up once rather than every frame. */
    private static final Set<String> MISSING = new java.util.HashSet<>();

    private record Entry(RenderablePokemon pokemon, PosableState state) {}

    private ShopPokemonPortraits() {}

    /**
     * Draws {@code species} centred in the given cell.
     *
     * @return false when there is nothing to draw, so the caller can fall back to a label
     */
    static boolean draw(GuiGraphics graphics, String species, boolean shiny,
                        int cellX, int cellY, int cell, float partialTicks) {
        Entry entry = resolve(species, shiny);
        if (entry == null) return false;
        try {
            entry.state().updatePartialTicks(partialTicks);
            graphics.pose().pushPose();
            // Cobblemon draws a profile around the origin, so the origin goes to the cell's centre.
            // Pushed slightly below centre because a model's feet sit near its origin and the head
            // is what should be framed.
            graphics.pose().translate(cellX + cell / 2.0, cellY + cell * 0.78, 0.0);
            PokemonGuiUtilsKt.drawProfilePokemon(
                    entry.pokemon(),
                    graphics.pose(),
                    new Quaternionf().rotateXYZ(0.0F, 0.0F, 0.0F),
                    PoseType.PROFILE,
                    entry.state(),
                    partialTicks,
                    cell * 0.34F,
                    true,
                    true,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.0F, 0.0F);
            graphics.pose().popPose();
            return true;
        } catch (RuntimeException | LinkageError ex) {
            // One bad model must not take the screen with it, and must not retry every frame.
            MISSING.add(key(species, shiny));
            CACHE.remove(key(species, shiny));
            RaidLog.error("Shop could not render " + species + "; drawing a label instead ("
                    + ex.getMessage() + ")");
            return false;
        }
    }

    private static Entry resolve(String species, boolean shiny) {
        String key = key(species, shiny);
        if (MISSING.contains(key)) return null;
        Entry cached = CACHE.get(key);
        if (cached != null) return cached;
        try {
            Species resolved = PokemonSpecies.INSTANCE.getByName(species);
            if (resolved == null) {
                MISSING.add(key);
                return null;
            }
            Entry entry = new Entry(
                    new RenderablePokemon(resolved, shiny ? Set.of("shiny") : Set.of(), ItemStack.EMPTY),
                    new FloatingState());
            CACHE.put(key, entry);
            return entry;
        } catch (RuntimeException | LinkageError ex) {
            MISSING.add(key);
            return null;
        }
    }

    private static String key(String species, boolean shiny) {
        return shiny ? species + "#shiny" : species;
    }

    /** Dropped when the screen closes: these hold Cobblemon render state, not just data. */
    static void clear() {
        CACHE.clear();
        MISSING.clear();
    }
}
