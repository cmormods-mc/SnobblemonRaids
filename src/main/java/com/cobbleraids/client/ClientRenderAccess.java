package com.cobbleraids.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;

/**
 * The one place outside a screen or a {@code ClientModInitializer} that touches
 * {@link Minecraft#getInstance()} directly, so the physical-side boundary check (Phase 38) that
 * confines it to {@code com.cobbleraids.client.*} has a single file to allow rather than every
 * client mixin needing its own exemption.
 *
 * <p>Exists specifically so a client mixin never needs {@code @Shadow} for a field/method a mod's
 * own subclass merely inherits from vanilla ({@code EntityRenderer.entityRenderDispatcher}/{@code
 * getFont()}): shadowing those through a third-party class like {@code PokemonRenderer} crashed the
 * game outright ({@code InvalidMixinException ... No refMap loaded}) the one time it was tried, since
 * Mixin's shadow resolution does not reliably remap a vanilla member inherited through a mod's own
 * concrete subclass. Both are trivial global singletons -- {@code EntityRenderer} itself is
 * constructed from exactly these -- so reading them here sidesteps the problem entirely.
 */
public final class ClientRenderAccess {
    private ClientRenderAccess() {}

    public static EntityRenderDispatcher entityRenderDispatcher() {
        return Minecraft.getInstance().getEntityRenderDispatcher();
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /**
     * The plain buffer source, deliberately not whatever {@code MultiBufferSource} an entity's own
     * render call happens to receive. {@code LevelRenderer} swaps that parameter for
     * {@code RenderBuffers.outlineBufferSource()} whenever the entity should render with a coloured
     * outline (vanilla Glowing, or the tinted glow {@code RaidBossGlowService} gives a nearby raid
     * boss) -- and that source repaints every solid pixel of anything drawn through it in the
     * outline's flat colour, ignoring the real texture. A boon icon drawn via the passed-in buffer
     * during {@code renderNameTag} inherits that outline for free, which is why it showed up as a
     * flat coloured blob instead of its own art. Drawing into this buffer instead keeps the icon out
     * of that pass entirely; it still flushes at the normal point in the frame since it is the same
     * object every other entity's own model rendering uses whenever no outline is active.
     */
    public static MultiBufferSource.BufferSource bufferSource() {
        return Minecraft.getInstance().renderBuffers().bufferSource();
    }
}
