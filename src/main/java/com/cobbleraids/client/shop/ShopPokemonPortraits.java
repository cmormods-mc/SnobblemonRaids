package com.cobbleraids.client.shop;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.client.gui.PokemonGuiUtilsKt;
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState;
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.util.math.QuaternionUtilsKt;
import com.cobbleraids.RaidLog;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    /**
     * The framing Cobblemon uses for its own grid slots, read out of PC StorageSlot and the pasture
     * list: both anchor a 25-pixel cell at its top plus one, scale the pose stack by 2.5, and pass
     * 4.5 as the profile scale. Expressed here per cell pixel so the same framing holds at each of
     * the layout's three sizes.
     */
    private static final float ANCHOR_BELOW_TOP = 1.0F / 25.0F;
    private static final float STACK_SCALE = 2.5F / 25.0F;
    private static final float PROFILE_SCALE = 4.5F;

    /** Cobblemon's own slot angle: a three-quarter view, not the flat side-on identity gives. */
    private static final Vector3f ANGLE = new Vector3f(13.0F, 35.0F, 0.0F);

    /**
     * Scratch, and reset before every call, because drawProfilePokemon conjugates the quaternion it
     * is handed -- in place, discarding the result -- and hands the same object to the entity render
     * dispatcher. Cobblemon gets away with allocating a fresh one per slot per frame; reusing one
     * without the reset would flip every model's orientation on alternate frames.
     */
    private static final Quaternionf ROTATION = new Quaternionf();

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
        // Clipped to its own cell. A model is sized to fill the cell but its silhouette is not a
        // square, and the grid packs cells a single pixel apart, so any overhang lands on a
        // neighbour's artwork rather than on padding. Cobblemon scissors its PC slots for the same
        // reason.
        graphics.enableScissor(cellX, cellY, cellX + cell, cellY + cell);
        graphics.pose().pushPose();
        try {
            entry.state().updatePartialTicks(partialTicks);
            // Anchored at the top of the cell, not the middle: drawProfilePokemon applies its own
            // profile translation, which carries the body down from the anchor. Anchoring lower
            // pushed every model clear of its cell.
            graphics.pose().translate(cellX + cell / 2.0, cellY + cell * ANCHOR_BELOW_TOP, 0.0);
            graphics.pose().scale(cell * STACK_SCALE, cell * STACK_SCALE, 1.0F);
            PokemonGuiUtilsKt.drawProfilePokemon(
                    entry.pokemon(),
                    graphics.pose(),
                    QuaternionUtilsKt.fromEulerXYZDegrees(ROTATION.identity(), ANGLE),
                    PoseType.PROFILE,
                    entry.state(),
                    partialTicks,
                    PROFILE_SCALE,
                    true,
                    // Ignored while the profile transform is on, and false is what Cobblemon's own
                    // callers leave it at; passing true implied it was doing something.
                    false,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.0F, 0.0F);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            // One bad model must not take the screen with it, and must not retry every frame.
            cache.put(species, UNRESOLVABLE);
            RaidLog.error("Shop could not render " + species + "; drawing a fallback icon instead ("
                    + ex.getMessage() + ")");
            return false;
        } finally {
            // Must run. A barrier that swallows the throw but leaks a pushed pose and an enabled
            // scissor trades one bad cell for a screen clipped to twenty pixels for the rest of the
            // session.
            graphics.pose().popPose();
            graphics.disableScissor();
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
