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
 * cost is per cell per frame, so three things keep it bounded: the state is cached per species and
 * aspect rather than per cell, so nine Charizards animate as one; the caller only draws the cells
 * that hold Pokemon; and nothing on this path allocates.
 *
 * <p>That last one is why there are two maps rather than one keyed by a composed string. A key
 * built per cell per frame is a small allocation sixty times a second for as long as the screen is
 * open, and it buys nothing. A species that cannot be resolved is stored in the same map as
 * {@link #UNRESOLVABLE}, so a typo is looked up once instead of retried every frame -- and that
 * needs no separate set and no key either.
 *
 * <p>Failures are swallowed. A species id an operator mistyped must leave a fallback icon, not a
 * crash screen: the rest of the shop still works, and the purchase path refuses the same id
 * server-side anyway.
 *
 * <p>Sizes here are Minecraft's logical GUI pixels, so a cell is eighteen to twenty-two rather than
 * the ninety the earlier full-texture screen had. The model is scaled from the cell, so it follows
 * whatever size the layout picked, and the game's GUI Scale multiplies the result afterwards.
 */
final class ShopPokemonPortraits {

    /** Identity, and shared: rotating by nothing still allocated a quaternion every frame. */
    private static final Quaternionf NO_ROTATION = new Quaternionf();

    private static final Map<String, Entry> PLAIN = new HashMap<>();
    private static final Map<String, Entry> SHINY = new HashMap<>();

    /** Marks a species this client cannot render, so it is never looked up twice. */
    private static final Entry UNRESOLVABLE = new Entry(null, null);

    private record Entry(RenderablePokemon pokemon, PosableState state) {}

    private ShopPokemonPortraits() {}

    /**
     * Draws {@code species} centred in the given cell.
     *
     * @return false when there is nothing to draw, so the caller can fall back to an icon
     */
    static boolean draw(GuiGraphics graphics, String species, boolean shiny,
                        int cellX, int cellY, int cell, float partialTicks) {
        Map<String, Entry> cache = shiny ? SHINY : PLAIN;
        Entry entry = resolve(cache, species, shiny);
        if (entry == null) return false;
        try {
            entry.state().updatePartialTicks(partialTicks);
            graphics.pose().pushPose();
            // Cobblemon draws a profile around the origin, so the origin goes to the cell's centre.
            // Pushed below centre because a model's feet sit near its origin and the head is what
            // should be framed.
            graphics.pose().translate(cellX + cell / 2.0, cellY + cell * 0.80, 0.0);
            PokemonGuiUtilsKt.drawProfilePokemon(
                    entry.pokemon(),
                    graphics.pose(),
                    NO_ROTATION,
                    PoseType.PROFILE,
                    entry.state(),
                    partialTicks,
                    cell * 0.62F,
                    true,
                    true,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.0F, 0.0F);
            graphics.pose().popPose();
            return true;
        } catch (RuntimeException | LinkageError ex) {
            // One bad model must not take the screen with it, and must not retry every frame.
            cache.put(species, UNRESOLVABLE);
            RaidLog.error("Shop could not render " + species + "; drawing a fallback icon instead ("
                    + ex.getMessage() + ")");
            return false;
        }
    }

    private static Entry resolve(Map<String, Entry> cache, String species, boolean shiny) {
        Entry cached = cache.get(species);
        if (cached == UNRESOLVABLE) return null;
        if (cached != null) return cached;
        try {
            Species resolved = PokemonSpecies.INSTANCE.getByName(species);
            if (resolved == null) {
                cache.put(species, UNRESOLVABLE);
                return null;
            }
            Entry entry = new Entry(
                    new RenderablePokemon(resolved, shiny ? Set.of("shiny") : Set.of(), ItemStack.EMPTY),
                    new FloatingState());
            cache.put(species, entry);
            return entry;
        } catch (RuntimeException | LinkageError ex) {
            cache.put(species, UNRESOLVABLE);
            return null;
        }
    }

    /**
     * Dropped when the screen goes away: these hold Cobblemon render state, and a Species from the
     * client's registry, which does not survive a world change.
     */
    static void clear() {
        PLAIN.clear();
        SHINY.clear();
    }
}
