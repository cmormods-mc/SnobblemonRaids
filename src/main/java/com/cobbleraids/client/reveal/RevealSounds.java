package com.cobbleraids.client.reveal;

import com.cobbleraids.config.RaidRarityTier;
import com.cobblemon.mod.common.CobblemonSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/** Client-only reveal sound trigger. Reuses Cobblemon's existing ball send-out sounds -- no new assets. */
final class RevealSounds {
    private RevealSounds() {}

    static void playOpen(RaidRarityTier tier) {
        SoundEvent sound = switch (tier) {
            case LEGENDARY, MYTHICAL -> CobblemonSounds.POKE_BALL_SHINY_SEND_OUT;
            case STARTER, POWERHOUSE -> CobblemonSounds.POKE_BALL_SEND_OUT;
        };
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0f));
    }
}
