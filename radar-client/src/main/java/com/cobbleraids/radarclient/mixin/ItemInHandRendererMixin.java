package com.cobbleraids.radarclient.mixin;

import com.cobbleraids.radarclient.RadarHologramClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void cobbleraids$renderRadar(AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
            PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo callback) {
        boolean floating = RadarHologramClient.matches(player.getMainHandItem())
                || RadarHologramClient.matches(player.getOffhandItem());
        if (!floating) return;
        // The world renderer draws the level floating projector once; suppress held-item poses.
        callback.cancel();
    }
}
