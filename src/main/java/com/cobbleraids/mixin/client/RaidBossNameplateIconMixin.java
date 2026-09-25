package com.cobbleraids.mixin.client;

import com.cobbleraids.client.renown.RenownBoonIcons;
import com.cobbleraids.fault.RaidFaultBarrier;
import com.cobbleraids.presentation.RaidBossNameplate;
import com.cobbleraids.renown.RenownBoon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a renowned boss's boon icon on its own floating nameplate, not just the reward reveal
 * screen's chamber banner -- the one place a player actually reads the title mid-fight.
 *
 * <p>Vanilla's nameplate is {@link Component} text rendered through the bitmap font, with no
 * facility for an inline texture. Cobblemon does not override {@code renderNameTag}, so this hooks
 * the vanilla method directly and re-derives the exact billboard transform it itself builds
 * (attachment position, camera-facing rotation, the 0.025 font-pixel scale) rather than capturing
 * its locals, so this mixin does not depend on that method's exact bytecode layout -- only on the
 * handful of calls decompiled from {@code EntityRenderer.renderNameTag} to build them the same way.
 *
 * <p>The boon travels with zero new netcode: {@link RaidBossNameplate#of} tucks it into the synced
 * display-name {@link Component} itself (an empty-text sibling's otherwise-unused chat-insertion
 * field), so whatever the client already has for the visible name is enough to also pick the icon.
 */
@Mixin(EntityRenderer.class)
public abstract class RaidBossNameplateIconMixin<T extends Entity> {
    @Shadow @Final protected EntityRenderDispatcher entityRenderDispatcher;

    @Shadow
    public abstract Font getFont();

    @Inject(method = "renderNameTag", at = @At("HEAD"))
    private void cobbleRaids$boonIcon(T entity, Component displayName, PoseStack poseStack,
                                       MultiBufferSource buffer, int packedLight, float partialTick,
                                       CallbackInfo ci) {
        try {
            if (!(entity instanceof PokemonEntity)) return;
            Optional<RenownBoon> boon = RaidBossNameplate.readBoonMarker(displayName);
            if (boon.isEmpty()) return;
            ResourceLocation icon = RenownBoonIcons.iconFor(boon.get());
            if (icon == null) return;
            if (entityRenderDispatcher.distanceToSqr(entity) > 4096.0) return;
            Vec3 attach = entity.getAttachments()
                    .getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
            if (attach == null) return;

            Font font = getFont();
            float halfTextWidth = font.width((FormattedText) displayName) / 2f;
            float size = font.lineHeight;
            float x0 = -halfTextWidth - 2f - size;
            float y0 = -size / 2f;

            // pushPose/popPose must stay paired even if drawing itself throws, or a swallowed
            // exception here leaves every render call after this one working from a pose the game
            // never meant to be on the stack -- see feedback_barriers_need_must_run_cleanup.
            poseStack.pushPose();
            try {
                poseStack.translate(attach.x, attach.y + 0.5, attach.z);
                poseStack.mulPose(entityRenderDispatcher.cameraOrientation());
                poseStack.scale(0.025f, -0.025f, 0.025f);
                Matrix4f matrix = poseStack.last().pose();
                VertexConsumer buf = buffer.getBuffer(RenderType.text(icon));
                // RenderType.text() backface-culls, and this billboard space carries a baked-in Y
                // flip (the -0.025 scale above, matching vanilla's own nametag transform), so which
                // winding order is "front" here isn't obvious without tracing font glyph emission
                // deeper than this mixin should depend on. Submitting both windings costs one extra
                // 4-vertex quad -- once per boon-icon nameplate on screen, not per frame per entity --
                // and guarantees the icon renders regardless of which winding the culler treats as front.
                quad(buf, matrix, x0, y0, size, packedLight, false);
                quad(buf, matrix, x0, y0, size, packedLight, true);
            } finally {
                poseStack.popPose();
            }
        } catch (Exception ex) {
            RaidFaultBarrier.report("mixin:renderNameTag", ex);
        }
    }

    private static void quad(VertexConsumer buf, Matrix4f matrix, float x0, float y0, float size,
                              int packedLight, boolean reversed) {
        float x1 = x0 + size;
        float y1 = y0 + size;
        if (!reversed) {
            vertex(buf, matrix, x0, y0, 0f, 0f, packedLight);
            vertex(buf, matrix, x0, y1, 0f, 1f, packedLight);
            vertex(buf, matrix, x1, y1, 1f, 1f, packedLight);
            vertex(buf, matrix, x1, y0, 1f, 0f, packedLight);
        } else {
            vertex(buf, matrix, x0, y0, 0f, 0f, packedLight);
            vertex(buf, matrix, x1, y0, 1f, 0f, packedLight);
            vertex(buf, matrix, x1, y1, 1f, 1f, packedLight);
            vertex(buf, matrix, x0, y1, 0f, 1f, packedLight);
        }
    }

    private static void vertex(VertexConsumer buf, Matrix4f matrix, float x, float y, float u, float v, int light) {
        buf.addVertex(matrix, x, y, 0f).setColor(255, 255, 255, 255).setUv(u, v).setLight(light);
    }
}
