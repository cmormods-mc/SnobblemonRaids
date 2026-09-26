package com.cobbleraids.mixin.client;

import com.cobbleraids.client.ClientRenderAccess;
import com.cobbleraids.client.renown.RenownBoonClientCache;
import com.cobbleraids.client.renown.RenownBoonIcons;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.renown.RenownBoon;
import com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a renowned boss's boon icon on its own floating nameplate, not just the reward reveal
 * screen's chamber banner and the in-battle tile -- the one place a player actually reads the title
 * mid-fight.
 *
 * <p>Targets {@code PokemonRenderer.renderNameTag} specifically, not vanilla's
 * {@code EntityRenderer.renderNameTag}. Two earlier versions of this mixin hooked the vanilla method
 * and never once fired for a real reason: {@code PokemonRenderer.shouldShowName} is hardcoded to
 * {@code false}, so vanilla's own always-on nameplate path never runs for a Pokemon at all. What a
 * player actually sees when looking at one is a completely different, Cobblemon-specific label,
 * gated by a private {@code shouldRenderLabel} check (crosshair on the entity -- matching "when
 * hovering over it") and rendered through {@code PokemonRenderer}'s <em>own</em> {@code
 * renderNameTag} override, a distinct method body vanilla's version never delegates to. This mixin
 * hooks that one, and re-derives its exact transform (distance-based dynamic scale, not vanilla's
 * fixed 0.025) rather than vanilla's, so the icon actually lines up with the label it is next to.
 *
 * <p>Draws through {@code RenderType.entityCutoutNoCull} rather than {@code RenderType.text}: the
 * icon is a standalone texture, not a font-atlas glyph, and "no cull" means the billboard's winding
 * order does not matter. That vertex format ({@code DefaultVertexFormat.NEW_ENTITY}) needs an
 * overlay and a normal in addition to position/colour/UV/light.
 *
 * <p>The boon itself does not travel on the display name at all -- an earlier version tried tucking
 * it into the name's own {@code Style} (an otherwise-unused chat-insertion field), but
 * {@code PokemonEntity.setCustomName} flattens whatever {@code Component} it is given down to a bare
 * string before storing it, discarding every {@code Style} attribute along with it. See
 * {@link RenownBoonClientCache} for the actual channel: a dedicated packet, keyed by the Pokemon's
 * own UUID, resolved fresh on every render rather than cached on the entity.
 *
 * <p>No {@code @Shadow} here for {@code entityRenderDispatcher}/{@code getFont()} even though
 * {@code EntityRenderer} (their real declaring class) is a superclass of the target: a first attempt
 * crashed the game at startup ({@code InvalidMixinException ... was not located in the target class
 * ... No refMap loaded}) because Mixin's shadow resolution does not reliably remap a vanilla member
 * inherited through a *mod's own* concrete subclass -- unlike targeting the vanilla class directly,
 * which every other mixin in this codebase does safely. Both are trivial global singletons
 * ({@code EntityRenderer}'s own fields are set from exactly these at construction), so
 * {@link ClientRenderAccess} reads them directly, sidestepping the whole problem instead of
 * fighting it.
 *
 * <p>Draws into {@link ClientRenderAccess#bufferSource()}, not the {@code buffer} parameter this
 * method actually receives: {@code LevelRenderer} swaps that parameter for the outline buffer
 * source whenever the entity should render with a coloured outline (vanilla Glowing, or the tinted
 * glow {@code RaidBossGlowService} gives a nearby raid boss), and that source repaints every solid
 * pixel drawn through it in the outline's flat colour -- confirmed live as the icon rendering as a
 * plain colour blob instead of its own art. See {@link ClientRenderAccess#bufferSource()}'s own doc.
 *
 * <p>{@code halfTextWidth} is measured from the {@code text} parameter, but the label this method
 * actually draws is {@code resolveBaseLabel(entity)} (roughly the same text) with an optional
 * " Lv. N" suffix appended afterward if the server shows entity levels -- which is not reflected in
 * {@code text} at all, so measuring from it alone underestimates the real label width whenever that
 * suffix is showing (confirmed live: every tested boss had it). The gap below is padded well past a
 * three-digit level's width rather than replicating Cobblemon's private label-building exactly.
 */
@Mixin(PokemonRenderer.class)
public abstract class RaidBossNameplateIconMixin {
    @Inject(method = "renderNameTag", at = @At("TAIL"))
    private void cobbleRaids$boonIcon(PokemonEntity entity, Component text, PoseStack poseStack,
                                       MultiBufferSource buffer, int packedLight, float tickDelta,
                                       CallbackInfo ci) {
        try {
            Optional<RenownBoon> boon = RenownBoonClientCache.forPokemonUuid(entity.getPokemon().getUuid());
            if (boon.isEmpty()) return;
            ResourceLocation icon = RenownBoonIcons.iconFor(boon.get());
            if (icon == null) return;

            EntityRenderDispatcher entityRenderDispatcher = ClientRenderAccess.entityRenderDispatcher();
            double distSqr = entityRenderDispatcher.distanceToSqr(entity);
            if (distSqr > 4096.0) return;

            // Mirrors PokemonRenderer.renderNameTag's own transform exactly (same remap/lerp
            // constants), since it uses a distance-scaled size and offset instead of vanilla's
            // fixed 0.025 scale -- reusing vanilla's transform here would draw the icon in the
            // wrong place relative to the label it is meant to sit beside.
            double scale = clamp(remap(distSqr, -16.0, 96.0, 0.0, 1.0), 0.65, 1.5);
            double u = remap(scale, 0.65, 1.5, 0.0, 1.0);
            double sizeScale = lerp(u, 0.5, 1.0);
            double offsetScale = lerp(u, 0.0, 1.0);
            double entityHeight = entity.getBoundingBox().getYsize() + 0.5;

            Font font = ClientRenderAccess.font();
            float halfTextWidth = font.width((FormattedText) text) / 2f;
            float iconSize = font.lineHeight;
            // Extra clearance for the possible " Lv. NNN" suffix the real label may carry that
            // halfTextWidth above does not account for -- see this class's own doc comment.
            float levelSuffixClearance = 45f;
            float x0 = -halfTextWidth - levelSuffixClearance - iconSize;
            float y0 = -iconSize / 2f;

            // pushPose/popPose must stay paired even if drawing itself throws, or a swallowed
            // exception here leaves every render call after this one working from a pose the game
            // never meant to be on the stack -- see feedback_barriers_need_must_run_cleanup.
            poseStack.pushPose();
            try {
                poseStack.translate(0.0, entityHeight, 0.0);
                poseStack.mulPose(entityRenderDispatcher.cameraOrientation());
                poseStack.translate(0.0, offsetScale / 2.0, -(scale + offsetScale));
                poseStack.scale((float) (0.025 * sizeScale), (float) (-0.025 * sizeScale), (float) sizeScale);
                Matrix4f matrix = poseStack.last().pose();
                VertexConsumer buf = ClientRenderAccess.bufferSource().getBuffer(RenderType.entityCutoutNoCull(icon));
                quad(buf, matrix, x0, y0, iconSize, packedLight);
            } finally {
                poseStack.popPose();
            }
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:renderNameTag", ex);
        }
    }

    private static double remap(double value, double fromLow, double fromHigh, double toLow, double toHigh) {
        return toLow + (value - fromLow) * (toHigh - toLow) / (fromHigh - fromLow);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static void quad(VertexConsumer buf, Matrix4f matrix, float x0, float y0, float size, int packedLight) {
        float x1 = x0 + size;
        float y1 = y0 + size;
        vertex(buf, matrix, x0, y0, 0f, 0f, packedLight);
        vertex(buf, matrix, x0, y1, 0f, 1f, packedLight);
        vertex(buf, matrix, x1, y1, 1f, 1f, packedLight);
        vertex(buf, matrix, x1, y0, 1f, 0f, packedLight);
    }

    private static void vertex(VertexConsumer buf, Matrix4f matrix, float x, float y, float u, float v, int light) {
        buf.addVertex(matrix, x, y, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(0f, 0f, 1f);
    }
}
